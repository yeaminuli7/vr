#!/usr/bin/env python3
"""Strip the MethodParameters attribute from .class files.

D8 8.2.2 throws a NullPointerException while reading method-level
MethodParameters attributes (it hits ~197 media3 classes). The attribute is
reflection metadata only - removing it has no runtime effect.

Usage: stripattr.py <dir-with-class-files>
"""
import os, sys, struct

# Constant pool entry payload sizes, by tag. 1 = Utf8 (variable), 5/6 = long/double.
SIZES = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 3: 4, 4: 4, 5: 8, 6: 8,
         15: 3, 17: 4, 18: 4, 9: 4, 10: 4, 11: 4, 12: 4}


class R:
    def __init__(self, b):
        self.b = b
        self.i = 0

    def u1(self):
        v = self.b[self.i]; self.i += 1; return v

    def u2(self):
        v = struct.unpack_from(">H", self.b, self.i)[0]; self.i += 2; return v

    def u4(self):
        v = struct.unpack_from(">I", self.b, self.i)[0]; self.i += 4; return v

    def skip(self, n):
        self.i += n


def parse_pool(r):
    """pool[i] is the Utf8 string for Utf8 entries, else None."""
    count = r.u2()
    pool = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            ln = r.u2()
            pool[i] = r.b[r.i:r.i + ln].decode("utf-8", "replace")
            r.skip(ln)
        elif tag in (5, 6):
            r.skip(8)
            i += 1          # long/double consume two pool slots
        else:
            sz = SIZES.get(tag)
            if sz is None:
                raise ValueError("bad cp tag %d at %d" % (tag, r.i))
            r.skip(sz)
        i += 1
    return pool


def process(data):
    """Return (new_bytes, removed_count)."""
    r = R(data)
    r.skip(8)                                   # magic, minor, major
    pool = parse_pool(r)
    r.skip(6)                                   # access_flags, this, super
    r.skip(2 * r.u2())                          # interfaces

    def skip_attrs():
        for _ in range(r.u2()):
            r.u2(); r.skip(r.u4())

    def read_attrs():
        out = []
        for _ in range(r.u2()):
            ni = r.u2()
            ln = r.u4()
            start = r.i
            r.skip(ln)
            out.append((ni, start - 6, r.i))    # include 6-byte header
        return out

    for _ in range(r.u2()):                     # fields
        r.skip(6)
        skip_attrs()

    methods = []
    for _ in range(r.u2()):
        r.skip(6)                               # access, name, descriptor
        count_pos = r.i
        attrs = read_attrs()
        methods.append((count_pos, attrs))

    dropped = []
    for count_pos, attrs in methods:
        for ni, s, e in attrs:
            if pool[ni] == "MethodParameters":
                dropped.append((s, e))

    if not dropped:
        return data, 0

    # Splice out dropped ranges (each is a complete attribute incl. its header).
    out = bytearray()
    prev = 0
    for s, e in sorted(dropped):
        out += data[prev:s]
        prev = e
    out += data[prev:]

    dropped_set = set(dropped)
    for count_pos, attrs in methods:
        keep = sum(1 for (_, s, e) in attrs if (s, e) not in dropped_set)
        # count_pos is an offset in the ORIGINAL file; shift left by every
        # byte range spliced out before it.
        shift = sum(e - s for (s, e) in dropped if e <= count_pos)
        struct.pack_into(">H", out, count_pos - shift, keep)

    return bytes(out), len(dropped)


def main():
    root = sys.argv[1]
    total = files = 0
    for dp, _, names in os.walk(root):
        for n in names:
            if not n.endswith(".class"):
                continue
            p = os.path.join(dp, n)
            with open(p, "rb") as f:
                data = f.read()
            try:
                new, removed = process(data)
            except Exception as e:
                print("  SKIP %s: %s" % (n, e))
                continue
            if removed:
                with open(p, "wb") as f:
                    f.write(new)
                total += removed
                files += 1
    print("  stripped %d MethodParameters attrs from %d class files" % (total, files))


if __name__ == "__main__":
    main()
