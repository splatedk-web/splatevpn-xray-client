"""Reproducible local Android AWG build. Never installs or changes the host toolchain."""
from pathlib import Path
import hashlib, json, os, subprocess
ROOT = Path(__file__).resolve().parents[1]
GO = ROOT / 'build/native-toolchain/go/bin/go.exe'
NDK = ROOT / 'build/native-toolchain/android-ndk-r27d/toolchains/llvm/prebuilt/windows-x86_64/bin'
ABIS = {'arm64-v8a': ('arm64', 'aarch64-linux-android26-clang.cmd', None), 'armeabi-v7a': ('arm', 'armv7a-linux-androideabi26-clang.cmd', '7'), 'x86_64': ('amd64', 'x86_64-linux-android26-clang.cmd', None)}
manifest = {}
for abi, (arch, cc, arm) in ABIS.items():
    env = dict(os.environ, GOTOOLCHAIN='local', GOMAXPROCS='4', GOMODCACHE=str(ROOT/'build/native-toolchain/gomod'), GOCACHE=str(ROOT/'build/native-toolchain/gocache'), GOOS='android', GOARCH=arch, CGO_ENABLED='1', CC=str(NDK/cc))
    if arm: env['GOARM'] = arm
    target = ROOT/'app/src/main/jniLibs'/abi/'libamneziawg.so'
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(GO), 'build', '-buildvcs=false', '-mod=readonly', '-trimpath', '-buildmode=pie', '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384', '-o', str(target), '.'], cwd=ROOT/'native/amneziawg', env=env, check=True)
    manifest[abi] = {'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'size': target.stat().st_size}
    print(abi, manifest[abi], flush=True)
(ROOT/'native/amneziawg/build-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
