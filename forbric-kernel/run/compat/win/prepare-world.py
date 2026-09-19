#!/usr/bin/env python3
"""Acknowledge the experimental-world prompt in an explicit test save, preserving all other NBT bytes.

This edits only Data/confirmedExperimentalSettings (TAG_Byte), the flag read and
written by the current carrier's PrimaryLevelData. It does not change lifecycle,
datapacks, version, seed or terrain. Run only after the server has saved and stopped.
"""
import argparse
import gzip
import os
from pathlib import Path
import struct
import sys


FLAG = b'confirmedExperimentalSettings'
DATA = (b'Data',)
TARGET = DATA + (FLAG,)


class NbtPositions:
    """Validate and walk NBT while retaining its original encoding, including modified UTF-8 strings."""
    def __init__(self, raw):
        self.raw = raw
        self.position = 0
        self.flag_offsets = []
        self.data_ends = []

    def take(self, count):
        if count < 0 or self.position + count > len(self.raw):
            raise ValueError('truncated NBT payload')
        start = self.position
        self.position += count
        return self.raw[start:self.position]

    def number(self, format):
        return struct.unpack('>' + format, self.take(struct.calcsize('>' + format)))[0]

    def string(self):
        return self.take(self.number('H'))

    def length(self):
        length = self.number('i')
        if length < 0:
            raise ValueError('negative NBT collection length')
        return length

    def payload(self, tag, path, depth):
        if depth > 512:
            raise ValueError('NBT nesting exceeds 512 levels')
        if tag in (1, 2, 3, 4, 5, 6):
            self.take({1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}[tag])
        elif tag == 8:
            self.string()
        elif tag in (7, 11, 12):
            self.take(self.length() * {7: 1, 11: 4, 12: 8}[tag])
        elif tag == 9:
            element = self.number('B')
            count = self.length()
            if not 0 <= element <= 12 or element == 0 and count:
                raise ValueError('invalid NBT list element type')
            # List entries are not named children of their parent compound.
            for index in range(count):
                self.payload(element, path + (index,), depth + 1)
        elif tag == 10:
            while True:
                kind = self.number('B')
                if kind == 0:
                    if path == DATA:
                        self.data_ends.append(self.position - 1)
                    break
                name = self.string()
                child = path + (name,)
                if child == DATA and kind != 10:
                    raise ValueError('root Data must be TAG_Compound')
                if child == TARGET:
                    if kind != 1:
                        raise ValueError('Data/confirmedExperimentalSettings must be TAG_Byte')
                    self.flag_offsets.append(self.position)
                self.payload(kind, child, depth + 1)
        else:
            raise ValueError('unknown NBT tag type: ' + str(tag))

    def scan(self):
        if self.number('B') != 10:
            raise ValueError('level.dat root must be TAG_Compound')
        self.string()  # The root name is not a nested path component.
        self.payload(10, (), 0)
        if self.position != len(self.raw):
            raise ValueError('trailing bytes after NBT root')
        if len(self.data_ends) != 1 or len(self.flag_offsets) > 1:
            raise ValueError('missing or ambiguous Data/confirmedExperimentalSettings location')
        return self


def confirm(raw):
    positions = NbtPositions(raw).scan()
    if positions.flag_offsets:
        offset = positions.flag_offsets[0]
        if raw[offset] == 1:
            return raw, False
        return raw[:offset] + b'\x01' + raw[offset + 1:], True
    # PrimaryLevelData defaults an absent flag to false; insert the named byte in Data only.
    offset = positions.data_ends[0]
    entry = b'\x01' + struct.pack('>H', len(FLAG)) + FLAG + b'\x01'
    return raw[:offset] + entry + raw[offset:], True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('level_dat', type=Path, help='Explicit test save level.dat; never searches for saves')
    parser.add_argument('--check', action='store_true', help='Read only; return 1 when acknowledgement is absent')
    parser.add_argument('--output', type=Path, help='Write a prepared copy instead of changing the input')
    args = parser.parse_args()
    if args.check and args.output:
        parser.error('--check and --output are mutually exclusive')
    original = args.level_dat.read_bytes()
    compressed = original.startswith(b'\x1f\x8b')
    raw = gzip.decompress(original) if compressed else original
    updated, changed = confirm(raw)
    if args.check:
        print('confirmedExperimentalSettings=' + ('0' if changed else '1'))
        return int(changed)
    destination = args.output or args.level_dat
    if changed or destination != args.level_dat:
        encoded = gzip.compress(updated, mtime=0) if compressed and changed else (updated if changed else original)
        temporary = None
        try:
            candidate = destination.with_name('.' + destination.name + '.' + os.urandom(8).hex())
            descriptor = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            temporary = candidate
            with os.fdopen(descriptor, 'wb') as output:
                output.write(encoded)
            temporary.chmod(args.level_dat.stat().st_mode & 0o777)
            temporary.replace(destination)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()
    print(f'confirmedExperimentalSettings=1 changed={str(changed).lower()} file={destination}')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, EOFError, ValueError, struct.error) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(2)
