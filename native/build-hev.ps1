param(
    [string]$NdkPath = '',
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = Join-Path $PSScriptRoot 'hev-socks5-tunnel'
if (-not $NdkPath) { $NdkPath = Join-Path $projectRoot 'build/native-toolchain/android-ndk-r27d' }
$ndkBuild = Join-Path $NdkPath 'ndk-build.cmd'
if (-not (Test-Path -LiteralPath $ndkBuild)) {
    throw 'Supply -NdkPath pointing to Android NDK r27d (27.3.13750724). See native/README.md.'
}
$ndkVersion = Get-Content -LiteralPath (Join-Path $NdkPath 'source.properties') -Raw
if ($ndkVersion -notmatch '(?m)^Pkg.Revision\s*=\s*27\.3\.13750724\s*$') { throw 'Pinned NDK r27d is required.' }

# The vendored source contains real headers on Windows, not Git symlink placeholders.
foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot 'HEV_SOURCES.sha256')) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed source lock entry: $line" }
    $expectedHash = $Matches[1]
    $sourceFile = [IO.Path]::GetFullPath((Join-Path $sourceRoot $Matches[2]))
    if (-not $sourceFile.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe source path: $sourceFile"
    }
    $actualHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw "Source changed: $sourceFile. Review and update the source lock deliberately." }
}

$buildArgs = @(
    '-C', $sourceRoot,
    'NDK_PROJECT_PATH=.', 'APP_BUILD_SCRIPT=Android.mk', 'NDK_APPLICATION_MK=Application.mk',
    'APP_ABI=x86_64 arm64-v8a armeabi-v7a', 'APP_PLATFORM=android-26',
    'APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service -DCLSNAME=TProxyService',
    'APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true', 'REV_ID=d9dca26-splate-app-identity-v1', '-j4'
)
& $ndkBuild @buildArgs
if ($LASTEXITCODE -ne 0) { throw "HEV build failed with exit code $LASTEXITCODE" }

$readelf = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    foreach ($entry in @(@('libhev-socks5-tunnel.so', 'libhev-socks5-tunnel.so'), @('hev-socks5-tunnel-bin', 'libhevsockstun.so'))) {
        $builtFile = Join-Path $sourceRoot "libs/$abi/$($entry[0])"
        $segments = (& $readelf -l $builtFile) | Where-Object { $_ -match '^\s*LOAD\s' }
        if (-not $segments -or ($segments | Where-Object { $_ -notmatch '\s0x4000\s*$' })) {
            throw "Missing 16 KiB ELF load-segment alignment: $builtFile"
        }
        if (-not $SkipInstall) {
            $destination = Join-Path $projectRoot "app/src/main/jniLibs/$abi/$($entry[1])"
            Copy-Item -LiteralPath $builtFile -Destination $destination -Force
            Get-FileHash -LiteralPath $destination -Algorithm SHA256 | Format-List
        }
    }
}
