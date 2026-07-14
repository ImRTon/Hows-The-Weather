"""Inspect GRIB2 messages over HTTP without downloading the full model file."""

from __future__ import annotations

import argparse
import struct
import urllib.error
import urllib.request


def fetch_range(url: str, start: int, end: int) -> bytes:
    request = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"})
    with urllib.request.urlopen(request) as response:
        return response.read()


def sections(prefix: bytes):
    cursor = 16
    while cursor + 5 <= len(prefix):
        length = struct.unpack_from(">I", prefix, cursor)[0]
        if length < 5 or cursor + length > len(prefix):
            return
        yield prefix[cursor + 4], prefix[cursor : cursor + length]
        cursor += length


def inspect(url: str, header_bytes: int) -> None:
    offset = 0
    index = 0
    while True:
        try:
            header = fetch_range(url, offset, offset + header_bytes - 1)
        except urllib.error.HTTPError as error:
            if error.code == 416:
                break
            raise
        if len(header) < 16 or header[:4] != b"GRIB":
            break
        total_length = struct.unpack_from(">Q", header, 8)[0]
        section_map = {number: data for number, data in sections(header)}
        product = section_map.get(4, b"")
        grid = section_map.get(3, b"")
        representation = section_map.get(5, b"")
        category = product[9] if len(product) > 10 else None
        parameter = product[10] if len(product) > 10 else None
        product_template = struct.unpack_from(">H", product, 7)[0] if len(product) >= 9 else None
        representation_template = (
            struct.unpack_from(">H", representation, 9)[0] if len(representation) >= 11 else None
        )
        grid_template = struct.unpack_from(">H", grid, 12)[0] if len(grid) >= 14 else None
        if category == 2 or index < 3:
            print(
                f"index={index} offset={offset} length={total_length} "
                f"category={category} parameter={parameter} "
                f"gridTemplate={grid_template} productTemplate={product_template} "
                f"dataTemplate={representation_template}"
            )
            if category == 2:
                print(f"section4={product.hex()}")
                if product[22:23] == b"\x67":
                    print(f"section3={grid.hex()}")
                    print(f"section5={representation.hex()}")
        offset += total_length
        index += 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("--header-bytes", type=int, default=16_384)
    args = parser.parse_args()
    inspect(args.url, args.header_bytes)


if __name__ == "__main__":
    main()
