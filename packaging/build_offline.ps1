[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RuntimeDir,
    [string]$BuildInfo = '',
    [string]$UsbRoot = '',
    [string]$BootstrapConfig = ''
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
if ([string]::IsNullOrWhiteSpace($BuildInfo)) { $BuildInfo=Join-Path $projectRoot 'build\foundation-build.json' }
$build=(Get-Content -LiteralPath $BuildInfo -Raw -Encoding UTF8 | ConvertFrom-Json)
$appPath=(Resolve-Path -LiteralPath $build.app).Path
$expectedBuildRoot=Join-Path $projectRoot 'build'
if (-not $appPath.StartsWith($expectedBuildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected application output path' }
if ((Get-Item -LiteralPath $appPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Application output must not be a link' }
$version=(Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
if ($build.version -ne $version) { throw 'Build version is stale; rebuild first' }
$sourceCommit=(& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$' -or $build.sourceCommit -ne $sourceCommit -or $build.sourceDirty) { throw 'Build source revision is stale or dirty; commit and rebuild before packaging' }
$sourceChanges=@(& git -C $projectRoot status --porcelain --untracked-files=normal)
if ($LASTEXITCODE -ne 0 -or $sourceChanges.Count -ne 0) { throw 'Source worktree must be clean before packaging' }
$privateBootstrapPath=''
if (-not [string]::IsNullOrWhiteSpace($BootstrapConfig)) {
    $privateBootstrapPath=(Resolve-Path -LiteralPath $BootstrapConfig).Path
    $privateBootstrapFile=Get-Item -LiteralPath $privateBootstrapPath
    if ($privateBootstrapFile.PSIsContainer -or ($privateBootstrapFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $privateBootstrapFile.Length -gt 8192) { throw 'Bootstrap config must be a regular file of at most 8 KB' }
}
$runtimePath=(Resolve-Path -LiteralPath $RuntimeDir).Path
if ((Get-Item -LiteralPath $runtimePath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Runtime folder must not be a link' }
$runtimeNames=@('microsoft-jdk-21.0.12-linux-aarch64.tar.gz','microsoft-jdk-21.0.12-linux-x64.tar.gz')
# Reuse exactly the two runtime archives already delivered with PR0.7, not an arbitrary renamed binary.
$runtimeHashes=@('c61cadbc8ad4f950131dc260f0cdfd8d4d1f200fb16f8ac6a2611f17a77ab301','f2a84ad31ebeaf3a26252dd86a4a8e1b74aefb6bfc8e55fd20190110d1353c0f')
for ($index=0; $index -lt $runtimeNames.Count; $index++) {
    $runtimeFile=Get-Item -LiteralPath (Join-Path $runtimePath $runtimeNames[$index])
    if ($runtimeFile.PSIsContainer -or ($runtimeFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Runtime must be a regular archive' }
    if ((Get-FileHash -LiteralPath $runtimeFile.FullName -Algorithm SHA256).Hash -ne $runtimeHashes[$index]) { throw "Runtime checksum mismatch: $($runtimeFile.Name)" }
}
$bundleName="zhihui-xinguan-offline-$version-kylin"
$dist=Join-Path $projectRoot 'dist'
if ((Test-Path -LiteralPath $dist) -and ((Get-Item -LiteralPath $dist).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Release directory must not be a link' }
$bundle=Join-Path $dist $bundleName
$archive=Join-Path $dist "$bundleName.tar.gz"
if ((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) { throw 'Release already exists; keep it and use a new version or a distinct release path' }
$payload=Join-Path $bundle 'payload'
New-Item -ItemType Directory -Path $payload -Force | Out-Null
& tar.exe -czf (Join-Path $payload 'app.tar.gz') -C $appPath app web templates start.sh VERSION README_zh.md THIRD_PARTY_NOTICES.md dependencies.lock.json bootstrap.example.properties
if ($LASTEXITCODE -ne 0) { throw 'Application archive failed' }
foreach ($name in $runtimeNames) { Copy-Item -LiteralPath (Join-Path $runtimePath $name) -Destination $payload }
if ($privateBootstrapPath) {
    Copy-Item -LiteralPath $privateBootstrapPath -Destination (Join-Path $payload 'bootstrap.local.properties')
    Write-Host 'PRIVATE_BUNDLE: contains local initialization credentials; never upload this bundle to GitHub or a public release.'
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.sh') -Destination $bundle
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\离线安装与升级.md') -Destination (Join-Path $bundle '安装测试说明.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\发布检查.md') -Destination (Join-Path $bundle '测试记录.md')
[IO.File]::WriteAllText((Join-Path $bundle 'SOURCE_COMMIT'),$sourceCommit+"`n",[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination $bundle
$checks=@()
foreach ($file in (Get-ChildItem -LiteralPath $payload -File | Sort-Object Name)) { $checks+="{0}  payload/{1}" -f (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(),$file.Name }
[IO.File]::WriteAllText((Join-Path $bundle 'SHA256SUMS'),($checks -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
& tar.exe -czf $archive -C $dist $bundleName
if ($LASTEXITCODE -ne 0) { throw 'Complete archive failed' }
$archiveHash=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$archive.sha256","$archiveHash  $bundleName.tar.gz`n",[Text.UTF8Encoding]::new($false))
if (-not [string]::IsNullOrWhiteSpace($UsbRoot)) {
    $usbPath=(Resolve-Path -LiteralPath $UsbRoot).Path
    if ((Get-Item -LiteralPath $usbPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'USB destination must not be a link' }
    $driveId=([IO.Path]::GetPathRoot($usbPath)).TrimEnd('\')
    $disk=Get-CimInstance Win32_LogicalDisk | Where-Object DeviceID -eq $driveId
    if ($null -eq $disk -or $disk.DriveType -ne 2) { throw 'Requested USB target is not a removable disk' }
    $target=Join-Path $usbPath $bundleName
    if (Test-Path -LiteralPath $target) { throw 'USB release folder already exists; no existing version was overwritten' }
    $sourceFiles=@(Get-ChildItem -LiteralPath $bundle -File -Recurse)
    if (@(Get-ChildItem -LiteralPath $bundle -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) { throw 'Release cannot contain links' }
    $needed=($sourceFiles | Measure-Object Length -Sum).Sum
    if ($disk.FreeSpace -lt $needed+100MB) { throw 'Insufficient USB free space' }
    Copy-Item -LiteralPath $bundle -Destination $target -Recurse
    if (@(Get-ChildItem -LiteralPath $target -File -Recurse).Count -ne $sourceFiles.Count) { throw 'USB file count mismatch' }
    foreach ($sourceFile in $sourceFiles) {
        $relative=$sourceFile.FullName.Substring($bundle.Length+1)
        $copied=Join-Path $target $relative
        if ((Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash) { throw "USB verification failed: $relative" }
    }
    Write-Host "USB_READY $target"
}
Write-Host "OFFLINE_ARCHIVE $archive"
Write-Host "SHA256 $archiveHash"
