#!/usr/bin/env python3
"""读取 MP4 轨道尺寸与方向矩阵，用于定位旋转元数据叠加问题。"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


CONTAINERS = {
    b"moov",
    b"trak",
    b"mdia",
    b"minf",
    b"stbl",
    b"edts",
    b"dinf",
}


def iter_boxes(data: bytes, start: int, end: int):
    offset = start
    while offset + 8 <= end:
        size = struct.unpack_from(">I", data, offset)[0]
        box_type = data[offset + 4 : offset + 8]
        header_size = 8
        if size == 1:
            if offset + 16 > end:
                return
            size = struct.unpack_from(">Q", data, offset + 8)[0]
            header_size = 16
        elif size == 0:
            size = end - offset
        if size < header_size or offset + size > end:
            return
        yield box_type, offset, offset + header_size, offset + size
        offset += size


def signed_fixed(value: int) -> float:
    if value & 0x80000000:
        value -= 1 << 32
    return value / 65536.0


def rotation_from_matrix(values: tuple[int, ...]) -> int:
    a, b, _, c, d, _, _, _, _ = values
    fa = signed_fixed(a)
    fb = signed_fixed(b)
    fc = signed_fixed(c)
    fd = signed_fixed(d)
    candidates = (
        (0, (0.0, 1.0, -1.0, 0.0)),
        (90, (0.0, 1.0, -1.0, 0.0)),
        (90, (1.0, 0.0, 0.0, 1.0)),
        (180, (-1.0, 0.0, 0.0, -1.0)),
        (270, (0.0, -1.0, 1.0, 0.0)),
    )
    actual = (round(fa), round(fb), round(fc), round(fd))
    if actual == (0, 1, -1, 0):
        return 90
    if actual == (0, -1, 1, 0):
        return 270
    if actual == (-1, 0, 0, -1):
        return 180
    if actual == (1, 0, 0, 1):
        return 0
    return -1


def parse_tkhd(data: bytes, payload: int, end: int) -> dict:
    version = data[payload]
    if version == 0:
        matrix_offset = payload + 4 + 4 + 4 + 4 + 4 + 4 + 8 + 8
        width_offset = matrix_offset + 36
    elif version == 1:
        matrix_offset = payload + 4 + 8 + 8 + 4 + 4 + 8 + 8 + 8
        width_offset = matrix_offset + 36
    else:
        return {"version": version}
    if width_offset + 8 > end:
        return {"version": version, "truncated": True}
    matrix = struct.unpack_from(">9i", data, matrix_offset)
    width = struct.unpack_from(">I", data, width_offset)[0] / 65536.0
    height = struct.unpack_from(">I", data, width_offset + 4)[0] / 65536.0
    return {
        "version": version,
        "matrix": tuple(signed_fixed(value) for value in matrix),
        "rotation": rotation_from_matrix(matrix),
        "display_width": width,
        "display_height": height,
    }


def parse_mdhd(data: bytes, payload: int) -> dict:
    version = data[payload]
    if version == 0:
        timescale = struct.unpack_from(">I", data, payload + 12)[0]
        duration = struct.unpack_from(">I", data, payload + 16)[0]
    else:
        timescale = struct.unpack_from(">I", data, payload + 20)[0]
        duration = struct.unpack_from(">Q", data, payload + 24)[0]
    return {
        "version": version,
        "timescale": timescale,
        "duration_units": duration,
        "duration_seconds": duration / timescale if timescale else None,
    }


def parse_hdlr(data: bytes, payload: int, end: int) -> dict:
    name = b""
    if payload + 24 <= end:
        raw_name = data[payload + 24 : end]
        name = raw_name.split(b"\0", 1)[0]
    return {
        "handler": data[payload + 8 : payload + 12].decode("latin1", "replace"),
        "name": name.decode("utf-8", "replace"),
    }


def parse_stsd(data: bytes, payload: int, end: int) -> list[dict]:
    if payload + 8 > end:
        return []
    count = struct.unpack_from(">I", data, payload + 4)[0]
    entries = []
    cursor = payload + 8
    for _ in range(count):
        if cursor + 8 > end:
            break
        size = struct.unpack_from(">I", data, cursor)[0]
        if size < 8 or cursor + size > end:
            break
        entry_type = data[cursor + 4 : cursor + 8]
        entry = {
            "type": entry_type.decode("latin1", "replace"),
            "encoded_width": None,
            "encoded_height": None,
        }
        if cursor + 36 <= end and entry_type not in {b"mp4a", b"ac-3", b"ec-3"}:
            entry["encoded_width"] = struct.unpack_from(">H", data, cursor + 32)[0]
            entry["encoded_height"] = struct.unpack_from(">H", data, cursor + 34)[0]
        entries.append(entry)
        cursor += size
    return entries


def inspect_track(data: bytes, payload: int, end: int) -> dict:
    track = {}
    for box_type, box_start, child_payload, child_end in iter_boxes(
        data, payload, end
    ):
        if box_type == b"tkhd":
            track["tkhd"] = parse_tkhd(data, child_payload, child_end)
        elif box_type == b"mdia":
            for mdia_type, _, mdia_payload, mdia_end in iter_boxes(
                data, child_payload, child_end
            ):
                if mdia_type == b"mdhd":
                    track["mdhd"] = parse_mdhd(data, mdia_payload)
                elif mdia_type == b"hdlr":
                    track["hdlr"] = parse_hdlr(data, mdia_payload, mdia_end)
                elif mdia_type == b"minf":
                    for minf_type, _, minf_payload, minf_end in iter_boxes(
                        data, mdia_payload, mdia_end
                    ):
                        if minf_type != b"stbl":
                            continue
                        for stbl_type, _, stbl_payload, stbl_end in iter_boxes(
                            data, minf_payload, minf_end
                        ):
                            if stbl_type == b"stsd":
                                track["stsd"] = parse_stsd(
                                    data, stbl_payload, stbl_end
                                )
    return track


def inspect_file(path: Path) -> dict:
    data = path.read_bytes()
    result = {"path": str(path), "size": len(data), "tracks": []}
    for box_type, _, payload, end in iter_boxes(data, 0, len(data)):
        if box_type == b"moov":
            for moov_type, _, moov_payload, moov_end in iter_boxes(
                data, payload, end
            ):
                if moov_type == b"trak":
                    result["tracks"].append(
                        inspect_track(data, moov_payload, moov_end)
                    )
    return result


def print_report(reports: list[dict]) -> None:
    for report in reports:
        print(f"文件: {report['path']}")
        print(f"大小: {report['size']} bytes")
        for index, track in enumerate(report["tracks"], 1):
            tkhd = track.get("tkhd", {})
            mdhd = track.get("mdhd", {})
            hdlr = track.get("hdlr", {})
            entries = track.get("stsd", [])
            print(f"轨道 {index}:")
            print(f"  handler: {hdlr.get('handler')} ({hdlr.get('name')})")
            print(
                "  时长: {duration:.3f}s".format(
                    duration=float(mdhd.get("duration_seconds") or 0.0)
                )
            )
            for entry in entries:
                print(
                    "  样本: {type} {width}x{height}".format(
                        type=entry.get("type"),
                        width=entry.get("encoded_width"),
                        height=entry.get("encoded_height"),
                    )
                )
            print(
                "  tkhd 显示尺寸: {width:g}x{height:g}".format(
                    width=float(tkhd.get("display_width") or 0.0),
                    height=float(tkhd.get("display_height") or 0.0),
                )
            )
            print(f"  tkhd 旋转: {tkhd.get('rotation')}°")
            print(
                "  tkhd 矩阵: "
                + ", ".join(f"{value:g}" for value in tkhd.get("matrix", ()))
            )
        print()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    print_report([inspect_file(path) for path in args.files])


if __name__ == "__main__":
    main()
