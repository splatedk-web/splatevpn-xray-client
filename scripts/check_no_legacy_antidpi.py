"""Check removal of the standalone Anti-DPI feature from sources and an optional APK."""
from pathlib import Path
import argparse
import re
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--apk", type=Path)
args = parser.parse_args()
pattern = re.compile(r"AntiDpi|anti_dpi|ByeDpi|byedpi|ciadpi|START_LOCAL_BYPASS|STOP_LOCAL_BYPASS", re.I)
errors = []
for tier in ("main", "test", "androidTest"):
    for path in (root / "app/src" / tier).rglob("*"):
        if path.suffix in (".kt", ".java", ".xml") and path.is_file() and pattern.search(path.read_text(encoding="utf-8")):
            errors.append(str(path.relative_to(root)))
for path in (root / "app/src/main/jniLibs").glob("*/libbyedpi.so"):
    errors.append(str(path.relative_to(root)))
if args.apk:
    with zipfile.ZipFile(args.apk) as archive:
        errors.extend(name for name in archive.namelist() if "byedpi" in name.lower())
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                if re.search(rb"(?:com/danieldk/splatevpn/(?:data|vpn|ui/components|ui/theme)/AntiDpi|io/github/dovecoteescapee/byedpi)", archive.read(name)):
                    errors.append(f"{name}: legacy feature classes")
        if b"anti_dpi_" in archive.read("resources.arsc"):
            errors.append("resources.arsc: legacy feature resource names")
for error in errors:
    print("FAIL:", error)
print("FAIL" if errors else "PASS: no legacy Anti-DPI sources, resources or native libraries")
raise SystemExit(bool(errors))
