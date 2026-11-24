"""Check packaged 64-bit ELF LOAD/RELRO alignment: python scripts/check_apk_16k.py APP.apk."""
import argparse
import struct
import zipfile


def check(apk):
    failures = []
    checked = 0
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
                continue
            if name.endswith("/libhevsockstun.so"):
                failures.append(f"{name}: unused standalone HEV executable must not be packaged")
            data = archive.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                failures.append(f"{name}: expected little-endian ELF64")
                continue
            try:
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                if size < 56 or not count or offset + size * count > len(data):
                    raise ValueError("invalid program header table")
                headers = [struct.unpack_from("<IIQQQQQQ", data, offset + i * size) for i in range(count)]
                loads = [p for p in headers if p[0] == 1]
                if not loads:
                    raise ValueError("no LOAD segments")
                for p in loads:
                    if p[7] < 16384 or (p[3] - p[2]) % 16384:
                        failures.append(f"{name}: LOAD alignment {p[7]} or offset is incompatible with 16 KiB")
                for relro in (p for p in headers if p[0] == 0x6474E552):
                    end = relro[3] + relro[6]
                    if end % 16384 and any(p[1] & 2 and p[3] <= relro[3] and p[3] + p[6] > end for p in loads):
                        failures.append(f"{name}: RELRO shares an unaligned trailing page with writable data")
                checked += 1
            except (struct.error, ValueError) as error:
                failures.append(f"{name}: {error}")
    if not checked:
        failures.append("No 64-bit native libraries found")
    for failure in failures:
        print("FAIL", failure)
    print(f"{'FAIL' if failures else 'PASS'}: {checked} ELF64 files checked in {apk}")
    return not failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    raise SystemExit(0 if check(parser.parse_args().apk) else 1)
