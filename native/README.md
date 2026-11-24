# HEV: per-flow Android application identity

This directory vendors HEV 2.18.0 plus Splate's `splate-app-identity-v1` patch. Exact upstream and dependency commits, NDK version and archive checksums are pinned in `HEV_IDENTITY_BUILD.json`. `HEV_SOURCES.sha256` locks the actual Windows-materialized build sources. Original upstream licenses and copyright headers are retained. The packaged APK also includes dependency notices in `assets/licenses/`.

## Building on Windows

1. Download Android NDK **r27d / 27.3.13750724** from [Android's official download page](https://github.com/android/ndk/wiki/Unsupported-Downloads#r27d). The official Google CDN alternative URL and SHA-1/SHA-256 are in the manifest. Check the archive before extracting. It can be extracted under `build/native-toolchain/` without modifying the system Android SDK.
2. Run `powershell -File native/build-hev.ps1 -NdkPath C:/path/to/android-ndk-r27d` from the project root. Without `-NdkPath`, the script uses `build/native-toolchain/android-ndk-r27d`. `-SkipInstall` builds/checks ELF alignment without replacing packaged libraries.
3. The script verifies the source lock, builds arm64-v8a / armeabi-v7a / x86_64 for API 26, checks every ELF LOAD alignment is 16 KiB, and installs the JNI shared library plus standalone executable into `app/src/main/jniLibs`. The standalone executable is stored as `libhevsockstun.so` for diagnostics and excluded from APK packaging. The app uses `libhev-socks5-tunnel.so` through JNI.

Check a packaged APK with `python scripts/check_apk_16k.py PATH_TO_APK`. This checks the 64-bit ELF LOAD/RELRO layout and rejects an accidentally packaged standalone HEV executable. Actual 16 KiB device execution is a separate check.
4. Update HEV hashes in `NATIVE_COMPONENTS.json` after an intentional source/build change. Normal Gradle/APK builds use the checked-in native libraries and do not download or build this toolchain.

Source edits require an explicit source-lock refresh after review. Do not mechanically overwrite the lock to accept unknown upstream changes. Host-specific debug/build IDs may make archive hashes differ across build locations; the pinned source and toolchain are the source of truth.

## Patch boundary

* `hev-socks5-session*.c/.h`: capture the original IP protocol and Android local/remote address/port before opening the replacement SOCKS connection. lwIP's `remote` is the Android application's source and lwIP's `local` is the requested destination.
* `third-part/lwip/src/core/udp.c`: group UDP sessions by the full source/destination tuple and initialize the destination before the first session callback. Upstream's source-only grouping would reuse a SOCKS identity across destinations.
* `hev-jni.c/.h`: expose identity API version 1 and a nullable flow resolver. JNI calls run on a dedicated genuine pthread stack, not HEV's small coroutine stacks. Resolve once before each new flow's SOCKS connection. Null, exception, invalid username or thread failure rejects that flow. The authenticated SOCKS password remains the private per-tunnel YAML password.
* The existing native start/stop ABI is retained as void legacy wrappers; boolean-returning identity start/stop methods have distinct names. Old diagnostic HEV libraries remain usable when application matching is inactive. Identity mode requires API version 1.

The Java callback is `TProxyService.FlowIdentityResolver.resolve(protocol, localAddress, localPort, remoteAddress, remotePort): String?`. A returned printable ASCII username (1–255 bytes) identifies an authenticated account in the separate private SOCKS inbound; it never contains package names or UID values on the wire to the remote VPN server. The resolver global reference outlives the worker and is removed only after the TUN and identity pthreads have joined.

The Xray executable is unmodified. Its documented authenticated SOCKS username and `routing.user` interface carries the identity locally. Future Xray upgrades require the JVM/native compatibility tests and runtime self-check; the JNI identity API and source-lock checks protect the HEV side separately.

`HevAppIdentityNativeTest` exercises actual JNI/HEV using a local fake TUN and SOCKS endpoint. The Android VPN end-to-end test additionally checks Android's real UID lookup between two APKs. Keep both when updating native components.

To upgrade Xray to an explicitly reviewed upstream release, run `python native/update-xray.py --version VERSION`. The updater verifies all three archives against upstream `.dgst` SHA-256 checksums before replacement, keeps the previous binaries and manifest under `build/core-update/`, and updates the native lock and displayed version. Verify installed hashes with `python native/refresh-xray.py --check`, then run the native compatibility checks above.
