#!/usr/bin/env python3
"""从 MP4 的 hvcC 里取出 HEVC SPS，解析 VUI 的 video_full_range_flag /
colour_primaries / transfer_characteristics / matrix_coeffs 与 bit_depth。

用途：判断手机 Log 视频到底是 full range(0..1023) 还是 limited range(64..940)，
以及有没有写 color metadata。cv2 会把 yuv420p10le 当 8UC1 截断，拿不到准确码值。
"""
import sys, struct


class BR:
    def __init__(self, data):
        self.d = data
        self.pos = 0

    def u(self, n):
        v = 0
        for _ in range(n):
            byte = self.d[self.pos >> 3]
            bit = (byte >> (7 - (self.pos & 7))) & 1
            v = (v << 1) | bit
            self.pos += 1
        return v

    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
            if z > 32:
                raise ValueError("bad ue")
        return (1 << z) - 1 + (self.u(z) if z else 0)

    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k & 1 else -(k // 2)


def unescape(b):
    out = bytearray()
    i = 0
    while i < len(b):
        if i + 2 < len(b) and b[i] == 0 and b[i + 1] == 0 and b[i + 2] == 3:
            out += b[i:i + 2]
            i += 3
        else:
            out.append(b[i])
            i += 1
    return bytes(out)


def find_box(data, name):
    """返回该 box 的 payload 起始偏移（扫描法，够用）。"""
    idx = data.find(name)
    if idx < 0:
        return None
    # box: size(4) type(4)
    size = struct.unpack_from(">I", data, idx - 4)[0]
    return idx - 4, idx + 4, size


def parse_ptl(r, profile_present, max_sub_minus1):
    if profile_present:
        r.u(2); r.u(1); r.u(5)          # space tier idc
        r.u(32); r.u(48)                 # compatibility + constraint
        r.u(8)                           # level
    sub_profile = [0] * max_sub_minus1
    sub_level = [0] * max_sub_minus1
    for i in range(max_sub_minus1):
        sub_profile[i] = r.u(1); sub_level[i] = r.u(1)
    if max_sub_minus1 > 0:
        for i in range(max_sub_minus1, 8):
            r.u(2)
    for i in range(max_sub_minus1):
        if sub_profile[i]:
            r.u(88)
        if sub_level[i]:
            r.u(8)


def st_ref_pic_set(r, idx, num_sets, num_delta_pocs):
    inter = r.u(1) if idx != 0 else 0
    if inter:
        if idx == num_sets:
            r.ue()
        r.u(1); r.ue()
        ref = idx - 1
        nd = num_delta_pocs[ref] if ref >= 0 else 0
        cnt = 0
        for _ in range(nd + 1):
            used = r.u(1)
            if not used:
                r.u(1)
            else:
                cnt += 1
        return cnt + (1 if False else 0)
    else:
        neg = r.ue(); pos = r.ue()
        for _ in range(neg):
            r.ue(); r.u(1)
        for _ in range(pos):
            r.ue(); r.u(1)
        return neg + pos


def parse_sps(nal):
    r = BR(unescape(nal[2:]))           # 跳过 2 字节 NAL header
    r.u(4)                              # sps_video_parameter_set_id
    max_sub_minus1 = r.u(3)
    r.u(1)                              # temporal_id_nesting
    parse_ptl(r, True, max_sub_minus1)
    r.ue()                              # sps_seq_parameter_set_id
    chroma = r.ue()
    if chroma == 3:
        r.u(1)
    r.ue(); r.ue()                      # width height
    if r.u(1):                          # conformance_window_flag
        r.ue(); r.ue(); r.ue(); r.ue()
    bd_l = r.ue() + 8
    bd_c = r.ue() + 8
    r.ue()                              # log2_max_pic_order_cnt_lsb_minus4
    sub_ord = r.u(1)
    start = 0 if sub_ord else max_sub_minus1
    for _ in range(start, max_sub_minus1 + 1):
        r.ue(); r.ue(); r.ue()
    r.ue(); r.ue(); r.ue(); r.ue()      # CTB / TB sizes
    r.ue(); r.ue()                      # hierarchy depths
    if r.u(1):                          # scaling_list_enabled_flag
        if r.u(1):
            # scaling_list_data
            for size_id in range(4):
                if size_id == 3:
                    n = 2
                else:
                    n = 6
                for _ in range(n):
                    if not r.u(1):
                        r.ue()
                    else:
                        coef_num = min(64, 1 << (4 + (size_id << 1)))
                        if size_id > 1:
                            r.se()
                        for _ in range(coef_num):
                            r.se()
    r.u(1)                              # amp_enabled
    r.u(1)                              # sao_enabled
    if r.u(1):                          # pcm_enabled
        r.u(4); r.u(4); r.ue(); r.ue(); r.u(1)
    num_sets = r.ue()
    deltas = []
    for i in range(num_sets):
        deltas.append(st_ref_pic_set(r, i, num_sets, deltas))
    if r.u(1):                          # long_term_ref_pics_present_flag
        num_lt = r.ue()
        for _ in range(num_lt):
            r.ue(); r.u(1); r.u(1)
    r.u(1)                              # temporal_mvp
    r.u(1)                              # strong_intra_smoothing
    out = {"bit_depth_luma": bd_l, "bit_depth_chroma": bd_c, "chroma_format": chroma}
    if not r.u(1):
        return out
    # vui_parameters
    if r.u(1):                          # aspect_ratio_info_present
        if r.u(8) == 255:
            r.u(16); r.u(16)
    if r.u(1):                          # overscan_info
        r.u(1)
    if r.u(1):                          # video_signal_type_present
        out["video_format"] = r.u(3)
        out["video_full_range_flag"] = r.u(1)
        if r.u(1):                      # colour_description_present
            out["colour_primaries"] = r.u(8)
            out["transfer_characteristics"] = r.u(8)
            out["matrix_coeffs"] = r.u(8)
    else:
        out["video_full_range_flag"] = None
    return out


def main(path):
    data = open(path, "rb").read()
    loc = find_box(data, b"hvcC")
    if not loc:
        print("找不到 hvcC")
        return 1
    start, payload, size = loc
    p = payload
    p += 1 + 1 + 4 + 6 + 1          # configurationVersion..level
    p += 2 + 1 + 1 + 1 + 1          # min_spatial_seg, parallelism, chroma, bitdepth l/c
    p += 2 + 1                       # avgFrameRate, packed byte
    num_arrays = data[p]; p += 1
    print("hvcC @%d, NAL 数组数=%d" % (start, num_arrays))
    for _ in range(num_arrays):
        hdr = data[p]; p += 1
        nal_type = hdr & 0x3F
        cnt = struct.unpack_from(">H", data, p)[0]; p += 2
        for _ in range(cnt):
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            nal = data[p:p + ln]; p += ln
            if nal_type == 33:
                print("SPS: %s" % parse_sps(nal))
            else:
                print("  NAL type %d  len %d" % (nal_type, ln))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
