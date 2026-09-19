#!/usr/bin/env python3
"""Classify Minecraft's own PNG screenshot with a shared, stdlib-only criterion.

More than two visible colours in a thumbnail bounded by 64 pixels means DREW;
otherwise BLACK means nothing recognisably drawn (including a uniform solid frame).
Unsupported or malformed images never count as rendered. Exit codes: 0/1/2.
Supports non-interlaced grayscale, RGB, indexed, grayscale-alpha and RGBA PNGs.
"""
import argparse
from pathlib import Path
import struct
import sys
import zlib


def png_pixels(path):
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    offset, header, palette, transparency, compressed, ended = 8, None, None, b"", bytearray(), False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("truncated PNG chunk")
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + size
        if end > len(data):
            raise ValueError("truncated PNG chunk body")
        body = data[offset + 8:end - 4]
        crc = struct.unpack_from(">I", data, end - 4)[0]
        if zlib.crc32(kind + body) & 0xffffffff != crc:
            raise ValueError("PNG checksum mismatch")
        if header is None and kind != b"IHDR":
            raise ValueError("missing IHDR")
        if kind == b"IHDR":
            if header is not None or size != 13:
                raise ValueError("invalid IHDR")
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            if not body or len(body) % 3 or len(body) > 768:
                raise ValueError("invalid palette")
            palette = [tuple(body[index:index + 3]) for index in range(0, len(body), 3)]
        elif kind == b"tRNS":
            transparency = body
        elif kind == b"IDAT":
            compressed.extend(body)
        elif kind == b"IEND":
            if body:
                raise ValueError("invalid IEND")
            ended = True
            break
        elif kind[0] & 32 == 0:
            raise ValueError("unsupported critical PNG chunk")
        offset = end
    if not ended or header is None or not compressed:
        raise ValueError("incomplete PNG")
    width, height, depth, color_type, compression, filtering, interlace = header
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
    if channels is None or depth not in depths[color_type]:
        raise ValueError("unsupported PNG colour type or bit depth")
    if not width or not height or width * height > 32_000_000:
        raise ValueError("invalid or oversized PNG dimensions")
    if compression or filtering or interlace:
        raise ValueError("unsupported PNG compression, filter method or interlace")
    if color_type == 3 and palette is None:
        raise ValueError("indexed PNG has no palette")
    stride = (width * channels * depth + 7) // 8
    bpp = max(1, (channels * depth + 7) // 8)
    expected = height * (stride + 1)
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, expected + 1)
    if len(raw) != expected or not decoder.eof or decoder.unused_data:
        raise ValueError("invalid PNG pixel data length")

    # Decode every row because filters depend on the previous row, but retain only
    # uniformly spaced thumbnail samples, so colour counts do not scale with resolution.
    scale = min(1.0, 64 / max(width, height))
    sample_width = max(1, round(width * scale))
    sample_height = max(1, round(height * scale))
    xs = [min(width - 1, (2 * index + 1) * width // (2 * sample_width)) for index in range(sample_width)]
    ys = {min(height - 1, (2 * index + 1) * height // (2 * sample_height)) for index in range(sample_height)}
    previous, colours, position = bytearray(stride), set(), 0
    maximum = (1 << depth) - 1
    for y in range(height):
        filter_type = raw[position]
        position += 1
        row = bytearray(raw[position:position + stride])
        position += stride
        if filter_type > 4:
            raise ValueError("invalid PNG row filter")
        if filter_type:
            for index in range(stride):
                left = row[index - bpp] if index >= bpp else 0
                up = previous[index]
                upper_left = previous[index - bpp] if index >= bpp else 0
                if filter_type == 1:
                    prediction = left
                elif filter_type == 2:
                    prediction = up
                elif filter_type == 3:
                    prediction = (left + up) // 2
                else:
                    pa, pb, pc = abs(up - upper_left), abs(left - upper_left), abs(left + up - 2 * upper_left)
                    prediction = left if pa <= pb and pa <= pc else (up if pb <= pc else upper_left)
                row[index] = (row[index] + prediction) & 255
        if y in ys:
            for x in xs:
                if depth < 8:
                    shift = 8 - depth - (x * depth) % 8
                    samples = [(row[x * depth // 8] >> shift) & maximum]
                elif depth == 8:
                    samples = list(row[x * channels:(x + 1) * channels])
                else:
                    samples = list(struct.unpack_from(">" + "H" * channels, row, x * channels * 2))
                alpha = maximum
                if color_type == 3:
                    entry = samples[0]
                    if entry >= len(palette):
                        raise ValueError("palette index out of bounds")
                    rgb = palette[entry]
                    alpha = transparency[entry] if entry < len(transparency) else 255
                    alpha_maximum = 255
                else:
                    rgb = (samples[0],) * 3 if color_type in (0, 4) else tuple(samples[:3])
                    if color_type in (4, 6):
                        alpha = samples[-1]
                    elif transparency:
                        count = 1 if color_type == 0 else 3
                        if len(transparency) != count * 2:
                            raise ValueError("invalid transparency chunk")
                        if tuple(samples) == struct.unpack(">" + "H" * count, transparency):
                            alpha = 0
                    rgb = tuple(value * 255 // maximum for value in rgb)
                    alpha_maximum = maximum
                # Fully transparent coloured pixels are not visible evidence of drawing.
                colours.add(tuple(value * alpha // alpha_maximum for value in rgb))
        previous = row
    return width, height, colours


def classify(path):
    """The same callable is used by local gates and the remote bisect driver."""
    try:
        width, height, colours = png_pixels(path)
        return {"verdict": "DREW" if len(colours) > 2 else "BLACK",
                "width": width, "height": height, "colours": len(colours)}
    except (OSError, ValueError, IndexError, struct.error, zlib.error) as exc:
        return {"verdict": "UNSUPPORTED", "reason": str(exc)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("png", help="Minecraft screenshot")
    args = parser.parse_args()
    result = classify(args.png)
    print(" ".join("%s=%s" % item for item in result.items()))
    return {"DREW": 0, "BLACK": 1, "UNSUPPORTED": 2}[result["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
