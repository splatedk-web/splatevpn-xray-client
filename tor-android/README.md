# Tor Android 0.4.9.14

This Android library packages the **unmodified official native Tor executables**
for arm64-v8a, armeabi-v7a and x86_64. Upstream distributes them as `libtor.so`,
which Android extracts to the executable native-library directory. The application
starts Tor with `ProcessBuilder` and communicates through a loopback control port
with cookie authentication. Tor is never loaded into the application's JVM.

Separate processes provide a fresh native lifetime for every connection and let
the VPN service terminate Tor reliably when bootstrap is cancelled. Integration
tests exercise cancellation and repeated starts. No upstream Java service or JNI
wrapper is used or modified.

The complete upstream AAR requires Android API 37. This module extracts only the
native binaries and packages them with the existing API 35 build. It does not
alter upstream AAR metadata or downgrade Tor. Native Tor is not rebuilt from C.

- Artifact: `info.guardianproject:tor-android:0.4.9.14`.
- AAR SHA-256: `28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd`.
- [Published checksum metadata](https://repo.maven.apache.org/maven2/info/guardianproject/tor-android/0.4.9.14/tor-android-0.4.9.14.module).
- [Upstream source and build instructions](https://github.com/guardianproject/tor-android/tree/0.4.9.14).

`LICENSE` is copied from that upstream tag and packaged as `assets/tor-LICENSE.txt`.
See it for Tor and bundled component notices. The Java control protocol client is
upstream `info.guardianproject:jtorctl:0.4.5.7`.

Run `./refresh-upstream.ps1` in PowerShell to reproduce the native inputs. The
script checks the pinned AAR hash against both the download and upstream metadata
before extracting libraries. A Tor update requires reviewing its published hash,
native ABI compatibility and process/control behaviour.
