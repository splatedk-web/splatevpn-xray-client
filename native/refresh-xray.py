#!/usr/bin/env python3
"""Restore the reviewed Xray release from NATIVE_COMPONENTS.json, without changing its lock."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent.parent


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Splate-native-restore/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check installed binaries offline; do not download or replace files")
    args = parser.parse_args()
    lock = json.loads((ROOT / "NATIVE_COMPONENTS.json").read_text(encoding="utf-8-sig"))["xray"]
    if args.check:
        for abi, platform in lock["platforms"].items():
            data = (ROOT / platform["path"]).read_bytes()
            if sha256(data) != platform["sha256"]:
                raise ValueError(f"Installed binary differs from lock: {abi}")
            print(f"{abi}: verified Xray {lock['version']}")
        return

    release = json.loads(download(f"https://api.github.com/repos/XTLS/Xray-core/releases/tags/v{lock['version']}"))
    if release["draft"] or release["prerelease"] != lock["prerelease"]:
        raise ValueError("Release status differs from lock")
    assets = {asset["name"]: asset for asset in release["assets"]}
    staged = []
    for abi, platform in lock["platforms"].items():
        archive_url = platform["archive"]
        expected_prefix = f"https://github.com/XTLS/Xray-core/releases/download/v{lock['version']}/"
        if not archive_url.startswith(expected_prefix):
            raise ValueError(f"Non-upstream asset URL: {abi}")
        archive_name = archive_url.rsplit("/", 1)[1]
        asset = assets[archive_name]
        if asset["browser_download_url"] != archive_url or asset.get("digest") != f"sha256:{platform['archiveSha256']}":
            raise ValueError(f"Official asset digest differs from lock: {abi}")
        archive = download(archive_url)
        published = download(archive_url + ".dgst").decode("ascii")
        digest = re.search(r"(?mi)^SHA2-256=\s*([a-f0-9]{64})\s*$", published)
        if sha256(archive) != platform["archiveSha256"] or not digest or digest[1].lower() != platform["archiveSha256"]:
            raise ValueError(f"Archive checksum mismatch: {abi}")
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            executable = source.read("xray")
        if not executable.startswith(b"\x7fELF") or sha256(executable) != platform["sha256"]:
            raise ValueError(f"Executable checksum mismatch: {abi}")
        destination = (ROOT / platform["path"]).resolve()
        destination.relative_to(ROOT / "app" / "src" / "main" / "jniLibs")
        staged.append((abi, destination, executable))

    # Validate every ABI before replacing any reviewed binary.
    for abi, destination, executable in staged:
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(executable)
        temporary.replace(destination)
        print(f"{abi}: installed verified Xray {lock['version']}")


if __name__ == "__main__":
    main()
