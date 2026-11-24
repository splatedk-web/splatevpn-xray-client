#!/usr/bin/env python3
"""Upgrade bundled Xray to the reviewed official release, verifying every ABI first."""
import argparse
import concurrent.futures
import datetime
import io
import json
import re
import zipfile
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location("restore_xray", Path(__file__).with_name("refresh-xray.py"))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
ROOT = restore.ROOT

def main():
    lock_path = ROOT / "NATIVE_COMPONENTS.json"
    document = json.loads(lock_path.read_text(encoding="utf-8-sig"))
    old = document["xray"]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="Reviewed official stable version, e.g. 26.11.9")
    version = parser.parse_args().version
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Expected a stable version number")
    tag = "v" + version
    if version == old["version"]:
        print("Already on requested Xray", version)
        return
    page = restore.download(f"https://github.com/XTLS/Xray-core/releases/tag/{tag}").decode("utf-8")
    commits = set(re.findall(r"/XTLS/Xray-core/commit/([a-f0-9]{40})", page))
    if len(commits) != 1 or '"prerelease":true' in page:
        raise ValueError("Could not resolve the reviewed upstream release commit")
    commit = commits.pop()
    def stage(item):
        abi, platform = item
        destination = (ROOT / platform["path"]).resolve()
        destination.relative_to(ROOT / "app/src/main/jniLibs")
        if restore.sha256(destination.read_bytes()) != platform["sha256"]:
            raise ValueError(f"Existing {abi} differs from lock; preserve and review it")
        name = platform["archive"].rsplit("/", 1)[1]
        url = f"https://github.com/XTLS/Xray-core/releases/download/{tag}/{name}"
        archive = restore.download(url)
        digest = restore.sha256(archive)
        published = restore.download(url + ".dgst").decode("ascii")
        checksum = re.search(r"(?mi)^SHA2-256=\s*([a-f0-9]{64})\s*$", published)
        if not checksum or checksum[1].lower() != digest:
            raise ValueError(f"Upstream archive checksum mismatch: {abi}")
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            executable = source.read("xray")
        if not executable.startswith(b"\x7fELF"):
            raise ValueError("Expected ELF executable")
        updated = dict(platform, archive=url, sha256=restore.sha256(executable),
            archiveSha256=digest, releaseAssetDigest="sha256:" + digest, digestSource="upstream .dgst", publishedDigestVerified=True)
        return abi, destination, executable, updated
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        staged = list(pool.map(stage, old["platforms"].items()))
    backup = ROOT / "build/core-update" / (old["version"] + "-to-" + version)
    backup.mkdir(parents=True, exist_ok=True)
    (backup / "NATIVE_COMPONENTS.json").write_bytes(lock_path.read_bytes())
    for abi, destination, executable, platform in staged:
        (backup / (abi + "-libxray.so")).write_bytes(destination.read_bytes())
    for abi, destination, executable, platform in staged:
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(executable)
        temporary.replace(destination)
        print(abi, version, platform["sha256"], flush=True)
    document["checkedOn"] = datetime.date.today().isoformat()
    document["xray"] = dict(old, version=version, commit=commit, source=f"https://github.com/XTLS/Xray-core/releases/tag/{tag}",
        prerelease=False, platforms={abi: platform for abi, _, _, platform in staged})
    lock_path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    for relative in ("app/build.gradle.kts", "README.md"):
        path = ROOT / relative
        path.write_text(path.read_text(encoding="utf-8").replace(old["version"], version), encoding="utf-8")

if __name__ == "__main__":
    main()

