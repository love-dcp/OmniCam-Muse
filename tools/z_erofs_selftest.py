#!/usr/bin/env python3
"""Decode z_erofs COMPRESSED_COMPACT inodes (layout 3) and reconstruct files.

Only used to sanity-check the decoder against real ColorOS images; the
production path is tools/erofs_extract.py.
"""
import struct
import sys

sys.path.insert(0, "tools")
from erofs_extract import Erofs  # noqa: E402

T_PLAIN, T_HEAD1, T_NONHEAD, T_HEAD2 = 0, 1, 2, 3
CBLKCNT = 1 << 15


class Decoder:
    def __init__(self, fs, inode):
        self.fs = fs
        self.ino = inode
        iloc = fs._iloc(inode.nid)
        xl = 12 + 4 * (inode.xattr_icount - 1) if inode.xattr_icount else 0
        self.hdr_off = iloc + inode.inode_len + xl
        h_frag, h_advise, h_alg, h_clus = struct.unpack(
            "<IHBB", fs.mm[self.hdr_off:self.hdr_off + 8])
        self.advise = h_advise
        self.alg = h_alg & 0xF
        self.lclusterbits = fs.blkszbits + (h_clus & 0xF)
        self.lcsize = 1 << self.lclusterbits
        self.ebase = (self.hdr_off + 8 + 7) & ~7
        self.total = (inode.size + self.lcsize - 1) >> self.lclusterbits
        self.c4b_initial = ((32 - self.ebase % 32) // 4) & 7
        self.c2b = 0
        if (self.advise & 1) and self.c4b_initial < self.total:
            self.c2b = ((self.total - self.c4b_initial) // 16) * 16
        self.lobits_2b = self.lclusterbits  # 12-bit lo + 2-bit type = 14 bits

    def _pack(self, lcn):
        """Return (pack_start, i, vcnt, shift)."""
        pos = self.ebase
        shift = 2
        if lcn >= self.c4b_initial:
            pos += self.c4b_initial * 4
            lcn -= self.c4b_initial
            if lcn < self.c2b:
                shift = 1
            else:
                pos += self.c2b * 2
                lcn -= self.c2b
        pos += lcn * (1 << shift)
        vcnt = 2 if shift == 2 else 16
        packsz = vcnt << shift
        base = pos - (pos & (packsz - 1))
        return base, (pos - base) >> shift, vcnt, shift

    def _decode_bits(self, base, i, vcnt, shift):
        encodebits = (((vcnt << shift) - 4) * 8) // vcnt
        # COMPACTED_2B images pack every lcluster as 12-bit lo + 2-bit type;
        # CBLKCNT (bit 15) only exists in the legacy pure-4B layout.
        lobits = self.lclusterbits if (self.advise & 1) else 16
        byte_at = base + (encodebits * i) // 8
        v = struct.unpack_from("<I", self.fs.mm, byte_at)[0] >> ((encodebits * i) & 7)
        lo = v & ((1 << lobits) - 1)
        typ = (v >> lobits) & 3
        return lo, typ, encodebits, lobits

    def lcluster(self, lcn):
        """Return dict(type, cofs, pblk, cblks)."""
        base, i, vcnt, shift = self._pack(lcn)
        lo, typ, encodebits, lobits = self._decode_bits(base, i, vcnt, shift)
        if typ == T_NONHEAD:
            return {"type": typ, "cofs": self.lcsize, "pblk": None, "cblks": 0,
                    "lo": lo}
        cofs = lo
        # pblk = base pblk of pack + number of preceding heads/pclusters
        nblk = 0
        big = bool(self.advise & 0x0002)
        j = i
        if not big:
            nblk = 1
            while j > 0:
                j -= 1
                lo2, t2, _, _ = self._decode_bits(base, j, vcnt, shift)
                if t2 == T_NONHEAD:
                    j -= lo2
                if j >= 0:
                    nblk += 1
        else:
            nblk = 0
            while j > 0:
                j -= 1
                lo2, t2, _, _ = self._decode_bits(base, j, vcnt, shift)
                if t2 == T_NONHEAD:
                    if lo2 & CBLKCNT:
                        j -= 1
                        nblk += lo2 & ~CBLKCNT
                        continue
                    j -= lo2 - 2
                    continue
                nblk += 1
        packbase = struct.unpack_from("<I", self.fs.mm,
                                      base + (vcnt << shift) - 4)[0]
        return {"type": typ, "cofs": cofs, "pblk": packbase + nblk, "cblks": 0,
                "lo": lo}

    def compressed_blocks(self, lcn, info):
        """How many physical blocks this pcluster occupies."""
        big1 = bool(self.advise & 0x0002)
        big2 = bool(self.advise & 0x0004)
        typ = info["type"]
        if (typ == T_HEAD1 and not big1) or (typ in (T_PLAIN, T_HEAD2) and not big2) \
                or ((lcn + 1) << self.lclusterbits) >= self.ino.size:
            return 1
        nxt = self.lcluster(lcn + 1)
        if nxt["type"] == T_NONHEAD and (nxt["lo"] & CBLKCNT):
            return nxt["lo"] & ~CBLKCNT
        return 1


def main():
    img = sys.argv[1]
    path = sys.argv[2]
    fs = Erofs(img)
    ino, _nid = fs.resolve(path)
    d = Decoder(fs, ino)
    print(f"{path}: size={ino.size} lclusterbits={d.lclusterbits} "
          f"advise=0x{d.advise:04x} alg={d.alg} ebase%32={d.ebase % 32} "
          f"total={d.total} c4b={d.c4b_initial} c2b={d.c2b}")
    for lcn in list(range(min(12, d.total))) + [d.total - 1]:
        info = d.lcluster(lcn)
        info["cblks"] = d.compressed_blocks(lcn, info)
        print(f"  lcn={lcn:<6} {info}")


if __name__ == "__main__":
    main()
