#!/usr/bin/env python3
"""zipalign: rewrite a zip so every entry's data starts on a 4-byte boundary.

Equivalent to `zipalign -f 4`. Written in Python because the Android
build-tools zip (dl.google.com) is not reachable from the sandbox.
"""
import struct, sys, os

ALIGN = 4
LFH = "<4sHHHHHIIIHH"        # 30 bytes + name + extra
CDH = "<4sHHHHHHIIIHHHHHII"   # 46 bytes + name + extra + comment


def read_zip(path):
    with open(path, "rb") as f:
        data = f.read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("no EOCD - not a zip")
    n = struct.unpack_from("<H", data, eocd + 10)[0]
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    entries = []
    p = cd_off
    for _ in range(n):
        if data[p:p + 4] != b"PK\x01\x02":
            raise SystemExit("bad central directory at %d" % p)
        (sig, vmade, vneed, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen, clen, disk, iattr, eattr, lho) = struct.unpack_from(CDH, data, p)
        q = p + 46
        name = data[q:q + nlen]
        entries.append(dict(vmade=vmade, vneed=vneed, flags=flags, method=method,
                            mtime=mtime, mdate=mdate, crc=crc, csize=csize,
                            usize=usize, name=name, iattr=iattr, eattr=eattr,
                            lho=lho))
        p = q + nlen + elen + clen
    return data, entries


def data_offset(data, lho):
    nlen, elen = struct.unpack_from("<HH", data, lho + 26)
    return lho + 30 + nlen + elen


def verify(path, align=ALIGN):
    data, entries = read_zip(path)
    bad = []
    for e in entries:
        if data_offset(data, e["lho"]) % align:
            bad.append(e["name"].decode("utf-8", "replace"))
    return len(entries), bad


def main(src, dst):
    data, entries = read_zip(src)
    payloads = []
    for e in entries:
        start = data_offset(data, e["lho"])
        payloads.append(data[start:start + e["csize"]])

    out = bytearray()
    new_entries = []
    for e, payload in zip(entries, payloads):
        base = len(out) + 30 + len(e["name"])
        pad = (ALIGN - (base % ALIGN)) % ALIGN
        extra = b""
        if pad:
            if pad < 4:
                pad += ALIGN       # need >=4 bytes for an extra-field header
            extra = struct.pack("<HH", 0, pad - 4) + b"\x00" * (pad - 4)
        lho = len(out)
        out += struct.pack(LFH, b"PK\x03\x04", e["vneed"], e["flags"], e["method"],
                           e["mtime"], e["mdate"], e["crc"], e["csize"], e["usize"],
                           len(e["name"]), len(extra))
        out += e["name"] + extra + payload
        ne = dict(e)
        ne["lho"] = lho
        new_entries.append(ne)

    cd_off = len(out)
    for e in new_entries:
        out += struct.pack(CDH, b"PK\x01\x02", e["vmade"], e["vneed"], e["flags"],
                           e["method"], e["mtime"], e["mdate"], e["crc"], e["csize"],
                           e["usize"], len(e["name"]), 0, 0, 0, e["iattr"], e["eattr"],
                           e["lho"])
        out += e["name"]
    cd_size = len(out) - cd_off
    out += struct.pack("<4sHHHHIIH", b"PK\x05\x06", 0, 0, len(new_entries),
                       len(new_entries), cd_size, cd_off, 0)

    with open(dst, "wb") as f:
        f.write(bytes(out))

    n, bad = verify(dst)
    print("  %s: %d entries, %d B, misaligned=%d%s"
          % (os.path.basename(dst), n, len(out), len(bad),
             (" " + ",".join(bad[:5])) if bad else ""))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
