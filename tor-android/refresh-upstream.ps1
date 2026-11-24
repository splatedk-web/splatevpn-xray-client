$ErrorActionPreference = 'Stop'
$torVersion = '0.4.9.14'
$torAarSha256 = '28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd'
$torMavenBase = "https://repo.maven.apache.org/maven2/info/guardianproject/tor-android/$torVersion"
$torStaging = Join-Path ([System.IO.Path]::GetTempPath()) ('splate-tor-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $torStaging | Out-Null

# Keep the upstream artifacts in the temporary directory for independent inspection.
$torModuleResponse = Invoke-WebRequest "$torMavenBase/tor-android-$torVersion.module"
$torModuleText = if ($torModuleResponse.Content -is [byte[]]) {
    [Text.Encoding]::UTF8.GetString($torModuleResponse.Content)
} else { $torModuleResponse.Content }
$torMetadata = $torModuleText | ConvertFrom-Json
$torPublishedFiles = @($torMetadata.variants | ForEach-Object { $_.files })

function Get-VerifiedTorArtifact([string]$fileName, [string]$sha256) {
    $published = @($torPublishedFiles | Where-Object { $_.name -eq $fileName })
    if ($published.Count -eq 0 -or @($published | Where-Object { $_.sha256 -ne $sha256 }).Count -gt 0) {
        throw "Upstream metadata does not match pinned SHA-256: $fileName"
    }
    $download = Join-Path $torStaging $fileName
    Invoke-WebRequest "$torMavenBase/$fileName" -OutFile $download
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
        throw "Downloaded artifact does not match pinned SHA-256: $fileName"
    }
    return $download
}

$torAar = Get-VerifiedTorArtifact "tor-android-$torVersion.aar" $torAarSha256
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Copy-TorZipEntry([System.IO.Compression.ZipArchive]$archive, [string]$entryName, [string]$relativeTarget) {
    $entry = $archive.GetEntry($entryName)
    if ($null -eq $entry) { throw "Missing upstream artifact entry: $entryName" }
    $target = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $relativeTarget))
    if (-not $target.StartsWith($PSScriptRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Vendored target escapes module directory'
    }
    New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($target)) | Out-Null
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
}

$torAarZip = [System.IO.Compression.ZipFile]::OpenRead($torAar)
try {
    foreach ($torAbi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
        Copy-TorZipEntry $torAarZip "jni/$torAbi/libtor.so" "src/main/jniLibs/$torAbi/libtor.so"
    }
} finally { $torAarZip.Dispose() }
Invoke-WebRequest "https://raw.githubusercontent.com/guardianproject/tor-android/$torVersion/LICENSE" -OutFile (Join-Path $PSScriptRoot 'LICENSE')
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'src/main/assets') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LICENSE') -Destination (Join-Path $PSScriptRoot 'src/main/assets/tor-LICENSE.txt')
Write-Output "Verified and refreshed Tor $torVersion from $torStaging"
