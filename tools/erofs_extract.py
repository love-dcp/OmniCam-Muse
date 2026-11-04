#!/usr/bin/env python3
"""Minimal read-only EROFS reader (Windows-friendly, no erofs-utils needed).

OPPO ships ColorOS 17 partitions as EROFS, which neither 7-Zip nor WSL (blocked
in this sandbox) can open.  This walks the directory tree directly:

    python tools/erofs_extract.py list  <img> [--path /my_product/app]
    python tools/erofs_extract.py tree  <img> --path / --depth 3
    python tools/erofs_extract.py find  <img> --name 'libai_camera*'
    python tools/erofs_extract.py grep  <img> --pattern 'aicomposition'
    python tools/erofs_extract.py cat   <img> --path /system/build.prop
    python tools/erofs_extract.py dump  <img> --out <dir> [--path /] [--name pat]

Data layouts handled: FLAT_PLAIN, FLAT_INLINE, COMPRESSED_FULL (lz4 / lzma,
clusters up to 1 MiB).  Chunk-based images (rare on Android) are reported and
skipped rather than silently truncated.
"""
import argparse
import fnmatch
import lzma
import mmap
import os
import struct
import sys
from pathlib import Path

import lz4.block

EROFS_SUPER_OFFSET = 1024
MAGIC = 0xE0F5E1E2

# i_format -> datalayout (EROFS_I_DATALAYOUT_MASK, 3 bits at bit 1)
VERSION_BIT = 0
DATALAYOUT_BIT = 1
LAYOUT_PLAIN = 0
LAYOUT_COMPRESSED_FULL = 1
LAYOUT_FLAT_INLINE = 2
LAYOUT_COMPRESSED_COMPACT = 3
LAYOUT_CHUNK = 4

# lcluster types
T_PLAIN, T_HEAD1, T_NONHEAD, T_HEAD2 = 0, 1, 2, 3
LI_D0_CBLKCNT = 1 << 15

# z_erofs map header h_advise bits
ADVISE_COMPACTED_2B = 0x0001
ADVISE_BIG_PCLUSTER_1 = 0x0002
ADVISE_BIG_PCLUSTER_2 = 0x0004

# feature_incompat
INCOMPAT_ZERO_PADDING = 0x00000001
INCOMPAT_COMPR_CFGS = 0x00000002
INCOMPAT_BIG_PCLUSTER = 0x00000002
INCOMPAT_LZ4_0PADDING = 0x00000004
INCOMPAT_ZTAILPACKING = 0x00000010
INCOMPAT_FRAGMENTS = 0x00000020
INCOMPAT_DEDUPE = 0x00000040
INCOMPAT_XATTR_PREFIXES = 0x00000080
INCOMPAT_DEVICE_TABLE = 0x00000100
INCOMPAT_COMPR_HEAD2 = 0x00000200
INCOMPAT_ZTAILPACKING_DEPTH = 0x00000800
INCOMPAT_48BIT = 0x00002000


class ErofsError(Exception):
    pass


def lz4_block_decode(src, prefix=b""):
    """Pure-python LZ4 block decoder with an optional prefix window.

    z_erofs partial references let a pcluster's stream address bytes that were
    produced by the *previous* pcluster, so the C binding (which has no dict
    support) cannot handle them.
    """
    out = bytearray(prefix)
    base = len(out)
    i, n = 0, len(src)
    while i < n:
        token = src[i]
        i += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]
                i += 1
                lit += b
                if b != 255:
                    break
        if i + lit > n:
            raise ErofsError("lz4: truncated literals")
        out += src[i:i + lit]
        i += lit
        if i >= n:
            break
        off = src[i] | (src[i + 1] << 8)
        i += 2
        ml = token & 15
        if ml == 15:
            while True:
                b = src[i]
                i += 1
                ml += b
                if b != 255:
                    break
        ml += 4
        start = len(out) - off
        if start < 0:
            raise ErofsError("lz4: offset before start")
        if off >= ml:
            out += out[start:start + ml]
        else:
            for k in range(ml):
                out.append(out[start + k])
    return bytes(out[base:])


class Inode:
    __slots__ = ("nid", "fmt", "version", "layout", "mode", "size", "u",
                 "xattr_icount", "mtime", "uid", "gid", "nlink", "inode_len")

    @property
    def kind(self):
        m = self.mode & 0o170000
        return {0o040000: "dir", 0o100000: "file", 0o120000: "sym",
                0o020000: "chr", 0o060000: "blk", 0o010000: "fifo",
                0o140000: "sock"}.get(m, "?")


class Erofs:
    def __init__(self, path):
        self.path = str(path)
        fh = open(self.path, "rb")
        self.mm = mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ)
        self._fh = fh
        sb = self.mm[EROFS_SUPER_OFFSET:EROFS_SUPER_OFFSET + 128]
        magic = struct.unpack_from("<I", sb, 0)[0]
        if magic != MAGIC:
            raise ErofsError(f"bad magic 0x{magic:08x}")
        self.feat_compat = struct.unpack_from("<I", sb, 8)[0]
        self.blkszbits = sb[12]
        self.root_nid = struct.unpack_from("<H", sb, 14)[0]
        self.blocks = struct.unpack_from("<I", sb, 36)[0]
        self.meta_blkaddr = struct.unpack_from("<I", sb, 40)[0]
        self.feat_incompat = struct.unpack_from("<I", sb, 80)[0]
        self.blksz = 1 << self.blkszbits
        self.islotbits = self.blkszbits - 5
        self.slots_per_block = 1 << self.islotbits

    # ---------------- low level ----------------
    def _iloc(self, nid):
        blk = nid >> self.islotbits
        slot = nid & (self.slots_per_block - 1)
        return (self.meta_blkaddr + blk) * self.blksz + slot * 32

    def inode(self, nid):
        off = self._iloc(nid)
        raw = self.mm[off:off + 64]
        if len(raw) < 32:
            raise ErofsError(f"inode {nid} out of range")
        fmt = struct.unpack_from("<H", raw, 0)[0]
        i = Inode()
        i.nid = nid
        i.fmt = fmt
        i.version = fmt & 1
        i.layout = (fmt >> DATALAYOUT_BIT) & 7
        i.xattr_icount = struct.unpack_from("<H", raw, 2)[0]
        if i.version == 0:
            i.mode, i.nlink, i.size, _res, i.u, _ino = struct.unpack_from(
                "<HHIIII", raw, 4)
            i.uid = struct.unpack_from("<H", raw, 24)[0]
            i.gid = struct.unpack_from("<H", raw, 26)[0]
            i.mtime = 0
            self._inode_len = 32
        else:
            i.mode = struct.unpack_from("<H", raw, 4)[0]
            i.size = struct.unpack_from("<Q", raw, 8)[0]
            i.u = struct.unpack_from("<I", raw, 16)[0]
            i.uid = struct.unpack_from("<I", raw, 24)[0]
            i.gid = struct.unpack_from("<I", raw, 28)[0]
            i.mtime = struct.unpack_from("<q", raw, 32)[0]
            i.nlink = struct.unpack_from("<I", raw, 44)[0]
            self._inode_len = 64
        # copy to instance attr (avoid class-level leak)
        i.inode_len = 32 if i.version == 0 else 64
        return i

    def _xattr_len(self, inode):
        c = inode.xattr_icount
        if c == 0:
            return 0
        return ((c - 1) * 4 + 12) if c > 1 else 12

    # ---------------- data ----------------
    def _piece_from(self, blkaddr, size):
        start = blkaddr * self.blksz
        return self.mm[start:start + size]

    def read(self, inode):
        if inode.kind == "dir" and inode.layout == LAYOUT_PLAIN:
            return self._piece_from(inode.u, inode.size)
        if inode.layout == LAYOUT_PLAIN:
            return self._piece_from(inode.u, inode.size)
        if inode.layout == LAYOUT_FLAT_INLINE:
            blocks = inode.size // self.blksz
            tail = inode.size % self.blksz
            buf = bytearray()
            if blocks:
                buf += self._piece_from(inode.u, blocks * self.blksz)
            if tail:
                # tail lives right after the inode record
                pos = self._iloc(inode.nid) + inode.inode_len + \
                    self._xattr_len(inode)
                buf += self.mm[pos:pos + tail]
            return bytes(buf)
        if inode.layout in (LAYOUT_COMPRESSED_FULL, LAYOUT_COMPRESSED_COMPACT):
            return self._read_compressed(inode)
        raise ErofsError(f"unsupported data layout {inode.layout}")

    def _read_compressed(self, inode):
        """Rebuild a z_erofs compressed file.

        Both index layouts are supported:
          * COMPRESSED_FULL    -- 8-byte entries, ebase = round_up(base,8)+8
          * COMPRESSED_COMPACT -- packed 12/16-bit entries with an optional
                                  2-byte packing region (COMPACTED_2B)

        Each pcluster (head lcluster) decodes to a run that starts at the
        *logical* offset ``lcn * lcsize + clusterofs``; consecutive pclusters
        tile the file exactly, so no overlap arbitration is needed.
        """
        iloc = self._iloc(inode.nid)
        xl = 12 + 4 * (inode.xattr_icount - 1) if inode.xattr_icount else 0
        base = iloc + inode.inode_len + xl
        mhdr_off = (base + 7) & ~7
        h_frag, h_advise, h_alg, h_clus = struct.unpack(
            "<IHBB", self.mm[mhdr_off:mhdr_off + 8])
        alg = h_alg & 0x0F
        lclusterbits = self.blkszbits + (h_clus & 0x0F)
        lcsize = 1 << lclusterbits
        compact = inode.layout == LAYOUT_COMPRESSED_COMPACT
        ebase = ((base + 8) + 7) & ~7
        total = (inode.size + lcsize - 1) >> lclusterbits
        lobits = lclusterbits if (compact and h_advise & ADVISE_COMPACTED_2B) else 16
        c4b_initial = ((32 - ebase % 32) // 4) & 7 if compact else 0
        c2b = 0
        if compact and (h_advise & ADVISE_COMPACTED_2B) and c4b_initial < total:
            c2b = ((total - c4b_initial) // 16) * 16

        cache = {}

        def decode_bits(pack_base, i, vcnt, shift):
            encodebits = (((vcnt << shift) - 4) * 8) // vcnt
            at = pack_base + (encodebits * i) // 8
            v = struct.unpack_from("<I", self.mm, at)[0] >> ((encodebits * i) & 7)
            return v & ((1 << lobits) - 1), (v >> lobits) & 3

        def lcluster(lcn):
            hit = cache.get(lcn)
            if hit is not None:
                return hit
            if not compact:
                pos = ebase + lcn * 8
                di_advise, di_cofs = struct.unpack_from("<HH", self.mm, pos)
                typ = di_advise & 3
                if typ == T_NONHEAD:
                    d0, d1 = struct.unpack_from("<HH", self.mm, pos + 4)
                    res = (typ, lcsize, None, d0, d1)
                else:
                    (blkaddr,) = struct.unpack_from("<I", self.mm, pos + 4)
                    res = (typ, di_cofs, blkaddr, 0, 0)
                cache[lcn] = res
                return res

            pos = ebase
            shift = 2
            n = lcn
            if n >= c4b_initial:
                pos += c4b_initial * 4
                n -= c4b_initial
                if n < c2b:
                    shift = 1
                else:
                    pos += c2b * 2
                    n -= c2b
            pos += n * (1 << shift)
            vcnt = 2 if shift == 2 else 16
            packsz = vcnt << shift
            pack_base = pos - (pos & (packsz - 1))
            i = (pos - pack_base) >> shift
            lo, typ = decode_bits(pack_base, i, vcnt, shift)
            if typ == T_NONHEAD:
                if i + 1 != vcnt:
                    delta0 = lo
                else:  # last slot stores delta[1]; derive delta[0] backwards
                    lo2, t2 = decode_bits(pack_base, i - 1, vcnt, shift)
                    delta0 = 0 if t2 != T_NONHEAD else (1 if lo2 & LI_D0_CBLKCNT else lo2 + 1)
                res = (typ, lcsize, None, delta0, lo if i + 1 == vcnt else 0)
                cache[lcn] = res
                return res

            big = bool(h_advise & ADVISE_BIG_PCLUSTER_1)
            nblk = 0 if big else 1
            j = i
            while j > 0:
                j -= 1
                lo2, t2 = decode_bits(pack_base, j, vcnt, shift)
                if t2 == T_NONHEAD:
                    if big and (lo2 & LI_D0_CBLKCNT):
                        j -= 1
                        nblk += lo2 & ~LI_D0_CBLKCNT
                        continue
                    j -= (lo2 - 2) if big else lo2
                if j >= 0:
                    nblk += 1
            (packpblk,) = struct.unpack_from("<I", self.mm,
                                             pack_base + packsz - 4)
            res = (typ, lo, packpblk + nblk, 0, 0)
            cache[lcn] = res
            return res

        def compressed_blocks(lcn, typ):
            big1 = bool(h_advise & ADVISE_BIG_PCLUSTER_1)
            big2 = bool(h_advise & ADVISE_BIG_PCLUSTER_2)
            if (typ == T_HEAD1 and not big1) or \
                    (typ in (T_PLAIN, T_HEAD2) and not big2) or \
                    ((lcn + 1) << lclusterbits) >= inode.size:
                return 1
            ntyp, _ncofs, _npblk, d0, _d1 = lcluster(lcn + 1)
            if ntyp == T_NONHEAD and (d0 & LI_D0_CBLKCNT):
                return d0 & ~LI_D0_CBLKCNT
            return 1

        # Collect every head first: a pcluster's logical run is delimited by the
        # next head's start, which is what PLAIN (uncompressed) runs need.
        heads = []
        for lcn in range(total):
            typ, cofs, pblk, _d0, _d1 = lcluster(lcn)
            if typ == T_NONHEAD or pblk is None:
                continue
            start = (lcn << lclusterbits) + cofs
            if start >= inode.size:
                continue
            heads.append((lcn, typ, start, pblk))

        out = bytearray(inode.size)
        filled = 0
        misses = 0
        for idx, (lcn, typ, start, pblk) in enumerate(heads):
            cofs = start - (lcn << lclusterbits)
            if idx + 1 < len(heads):
                span = heads[idx + 1][2] - start
            else:
                span = inode.size - start
            if span <= 0:
                continue
            dec = None
            if typ == T_PLAIN:
                dec = self.mm[pblk * self.blksz:pblk * self.blksz + span]
            else:
                # Keep growing the input window until the decoded run covers the
                # whole pcluster: one block usually decodes to < span bytes and
                # lz4 happily returns the short result instead of failing.
                want = min(span, 12 << 20)
                prev = -1
                for attempt in range(1, 1024):
                    raw = self.mm[pblk * self.blksz:(pblk + attempt) * self.blksz]
                    try:
                        if alg == 0:
                            cur = lz4.block.decompress(
                                raw, uncompressed_size=min(inode.size - start,
                                                           12 << 20))
                        elif alg == 1:
                            cur = lzma.LZMADecompressor(
                                lzma.FORMAT_RAW,
                                filters=[{"id": lzma.FILTER_LZMA1,
                                          "dict_size": 1 << 23}]).decompress(raw)
                        else:
                            raise ErofsError(
                                f"compression algorithm {alg} unsupported")
                    except Exception:
                        break
                    if len(cur) <= prev:
                        break
                    dec, prev = cur, len(cur)
                    if len(cur) >= want:
                        break
            if not dec and typ != T_PLAIN and alg == 0:
                # partial reference: the stream starts with a back-reference
                # (token 0x00, offset up to 65535) into data produced by the
                # previous pcluster, so decode with a 64 KiB prefix window.
                lcstart = lcn << lclusterbits
                back = min(65536, lcstart)
                prefix = bytes(out[lcstart - back:lcstart + cofs])
                cblks = compressed_blocks(lcn, typ)
                for attempt in range(cblks, min(cblks + 32, 1024)):
                    raw = self.mm[pblk * self.blksz:(pblk + attempt) * self.blksz]
                    cands = []
                    for skip in (0, 1, 2, 3):
                        try:
                            full = lz4_block_decode(raw[skip:], prefix)
                            body = full[back + cofs:]
                            if body:
                                cands.append(body)
                        except Exception:
                            pass
                    if cands:
                        dec = min(cands, key=lambda b: abs(len(b) - span))
                        break
            if not dec:
                # A handful of pclusters carry no compression at all even though
                # the index says HEAD (mkfs can flip a cluster to inline/plain).
                # Fall back to a literal copy and let the caller verify.
                dec = self.mm[pblk * self.blksz:pblk * self.blksz + span]
                misses += 1
                if misses <= 100:
                    print(f"[dbg] plain-fallback lcn={lcn} typ={typ} cofs={cofs} "
                          f"pblk={pblk} span={span} "
                          f"hdr={self.mm[pblk * self.blksz:pblk * self.blksz + 8].hex()}",
                          file=sys.stderr)
            end = min(inode.size, start + len(dec))
            out[start:end] = dec[:end - start]
            filled += end - start
        if misses:
            print(f"[warn] {misses} pcluster(s) failed to decode", file=sys.stderr)
        if filled < inode.size:
            # A trailing hole (all-zero pcluster) is legal; anything bigger than
            # one lcluster means the index walk went off the rails.
            missing = inode.size - filled
            if missing > (lcsize << 1):
                print(f"[warn] incomplete decode: {missing} of {inode.size} bytes "
                      f"missing (partial-reference clusters)", file=sys.stderr)
        return bytes(out)

    # ---------------- directory ----------------
    def listdir(self, inode):
        data = self.read(inode)
        out = []
        pos = 0
        bsz = self.blksz
        while pos < len(data):
            blk = data[pos:pos + bsz]
            base = pos
            idx = 0
            entries = []
            while idx * 12 + 12 <= len(blk):
                nid, nameoff, ftype, _r = struct.unpack_from("<QHBB", blk, idx * 12)
                if nid == 0 and nameoff == 0:
                    break
                if nameoff >= bsz or nameoff < (idx + 1) * 12:
                    break
                entries.append((nid, nameoff, ftype))
                idx += 1
                if nameoff == bsz:  # sentinel: no more names in block
                    idx -= 1
                    break
            for j, (nid, nameoff, ftype) in enumerate(entries):
                end = entries[j + 1][1] if j + 1 < len(entries) else len(blk)
                name = blk[nameoff:end].split(b"\0")[0]
                out.append((name.decode("utf-8", "replace"), nid, ftype))
            pos += bsz
        return out

    def walk(self, path="/"):
        node = self.resolve(path)
        if node is None:
            raise ErofsError(f"no such path {path}")
        yield path, node[0], node[1]
        stack = [(path, node[0])]
        while stack:
            base, ino = stack.pop()
            if ino.kind != "dir":
                continue
            try:
                kids = self.listdir(ino)
            except ErofsError:
                continue
            for name, nid, _ft in kids:
                if name in (".", ".."):
                    continue
                child = self.inode(nid)
                full = base.rstrip("/") + "/" + name
                yield full, child, nid
                if child.kind == "dir":
                    stack.append((full, child))

    def resolve(self, path):
        ino = self.inode(self.root_nid)
        nid = self.root_nid
        for part in [p for p in path.split("/") if p]:
            if ino.kind != "dir":
                return None
            found = None
            for name, cnid, _ft in self.listdir(ino):
                if name == part:
                    found = cnid
                    break
            if found is None:
                return None
            nid = found
            ino = self.inode(nid)
        return ino, nid


def human(n):
    for u in ("B", "K", "M", "G"):
        if n < 1024:
            return f"{n:.0f}{u}"
        n /= 1024
    return f"{n:.1f}T"


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("img")
    p.add_argument("action", choices=["list", "tree", "find", "grep", "cat", "dump", "info"])
    p.add_argument("--path", default="/")
    p.add_argument("--depth", type=int, default=4)
    p.add_argument("--name", default="*")
    p.add_argument("--pattern", default=None)
    p.add_argument("--out", default=None)
    p.add_argument("--limit", type=int, default=0)
    args = p.parse_args(argv)

    fs = Erofs(args.img)
    if args.action == "info":
        print(f"{args.img}: blk={fs.blksz} root_nid={fs.root_nid} "
              f"meta_blk={fs.meta_blkaddr} blocks={fs.blocks} "
              f"incompat=0x{fs.feat_incompat:08x} compat=0x{fs.feat_compat:08x}")
        return 0

    if args.action == "list":
        ino = fs.resolve(args.path)[0]
        for name, nid, ft in fs.listdir(ino):
            child = fs.inode(nid)
            extra = ""
            if child.kind == "sym":
                try:
                    extra = " -> " + fs.read(child).decode("utf-8", "replace")
                except ErofsError:
                    extra = " -> ?"
            print(f"{child.kind:4} {human(child.size):>9} {name}{extra}")
        return 0

    if args.action == "cat":
        ino = fs.resolve(args.path)[0]
        sys.stdout.buffer.write(fs.read(ino))
        return 0

    if args.action == "tree":
        base = args.path.rstrip("/")
        for full, ino, _nid in fs.walk(args.path):
            rel = full[len(base):] or "/"
            if rel.count("/") > args.depth:
                continue
            print(f"{ino.kind:4} {human(ino.size):>9} {rel}")
        return 0

    if args.action == "find":
        n = 0
        for full, ino, _nid in fs.walk(args.path):
            if fnmatch.fnmatch(os.path.basename(full), args.name):
                print(f"{ino.kind:4} {human(ino.size):>9} {full}")
                n += 1
                if args.limit and n >= args.limit:
                    break
        return 0

    if args.action == "grep":
        needle = args.pattern.encode()
        for full, ino, _nid in fs.walk(args.path):
            if ino.kind != "file" or ino.size == 0 or ino.size > 400 << 20:
                continue
            try:
                data = fs.read(ino)
            except ErofsError:
                continue
            at = data.find(needle)
            if at != -1:
                print(f"[hit] {full} ({human(ino.size)}) @{at}")
        return 0

    if args.action == "dump":
        outroot = Path(args.out)
        n = 0
        fails = 0
        for full, ino, _nid in fs.walk(args.path):
            if ino.kind != "file":
                continue
            if not fnmatch.fnmatch(os.path.basename(full), args.name):
                continue
            dst = outroot / full.lstrip("/")
            dst.parent.mkdir(parents=True, exist_ok=True)
            try:
                data = fs.read(ino)
            except ErofsError as exc:
                print(f"[skip] {full}: {exc}")
                fails += 1
                continue
            dst.write_bytes(data)
            n += 1
        print(f"dumped {n} file(s), {fails} skipped -> {outroot}")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
