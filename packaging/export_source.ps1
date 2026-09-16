[CmdletBinding()]
param([string]$UsbRoot='')
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$version=(Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
if ($version -notmatch '^[0-9A-Za-z.-]+$') { throw 'Invalid version' }
$commit=(& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve source commit' }
$changes=@(& git -C $projectRoot status --porcelain --untracked-files=normal)
if ($LASTEXITCODE -ne 0 -or $changes.Count -ne 0) { throw 'Commit source changes before exporting' }
$tracked=@(& git -C $projectRoot -c core.quotepath=false ls-tree -r --name-only HEAD)
if ($LASTEXITCODE -ne 0 -or $tracked.Count -eq 0) { throw 'Cannot enumerate source files' }
$forbidden=@($tracked | Where-Object { $_ -match '(^|/)(data|dist|vendor|\.git)(/|$)|bootstrap\.local\.properties$|\.(et|xls|xlsx|mv\.db|log|tar\.gz)$' })
if ($forbidden.Count -gt 0) { throw 'Private or generated files are tracked; refuse source export' }
$modes=@(& git -C $projectRoot ls-tree -r HEAD)
if ($LASTEXITCODE -ne 0 -or @($modes | Where-Object { $_ -notmatch '^100(644|755) ' }).Count -gt 0) { throw 'Source archive must contain regular files only' }
$dist=Join-Path $projectRoot 'dist'
if ((Test-Path -LiteralPath $dist) -and ((Get-Item -LiteralPath $dist).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Release directory must not be a link' }
$name="zhihui-xinguan-source-$version"
$output=Join-Path $dist $name
$archive=Join-Path $dist "$name.tar"
if ((Test-Path -LiteralPath $output) -or (Test-Path -LiteralPath $archive)) { throw 'Source release already exists; never overwrite it' }
New-Item -ItemType Directory -Path $output | Out-Null
& git -C $projectRoot archive --format=tar "--output=$archive" HEAD
if ($LASTEXITCODE -ne 0) { throw 'Source archive failed' }
& tar.exe -xf $archive -C $output
if ($LASTEXITCODE -ne 0) { throw 'Source extraction failed' }
# Add only the locked JAR dependencies so source can be built offline with a local JDK and Node.
$dependencies=(Get-Content -LiteralPath (Join-Path $output 'dependencies.lock.json') -Raw | ConvertFrom-Json).dependencies
$vendor=Join-Path $output 'vendor\dependencies'
New-Item -ItemType Directory -Path $vendor -Force | Out-Null
foreach ($dependency in $dependencies) {
    $filename="$($dependency.artifact)-$($dependency.version).jar"
    if ($filename -notmatch '^[A-Za-z0-9._-]+\.jar$' -or $dependency.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid dependency lock' }
    $inputFile=Get-Item -LiteralPath (Join-Path $projectRoot "vendor\dependencies\$filename")
    if ($inputFile.PSIsContainer -or ($inputFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Dependency is not a regular file' }
    if ((Get-FileHash -LiteralPath $inputFile.FullName -Algorithm SHA256).Hash -ne $dependency.sha256) { throw "Dependency checksum mismatch: $filename" }
    Copy-Item -LiteralPath $inputFile.FullName -Destination $vendor
}
[IO.File]::WriteAllText((Join-Path $output 'SOURCE_COMMIT'),$commit+"`n",[Text.UTF8Encoding]::new($false))
$files=@(Get-ChildItem -LiteralPath $output -File -Recurse -Force | Sort-Object FullName)
$sums=@();foreach ($file in $files) {
    $relative=$file.FullName.Substring($output.Length+1).Replace('\','/')
    $sums+="{0}  {1}" -f (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(),$relative
}
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS'),($sums -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
if ($UsbRoot) {
    $usb=(Resolve-Path -LiteralPath $UsbRoot).Path
    if ((Get-Item -LiteralPath $usb).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'USB destination must not be a link' }
    $driveId=([IO.Path]::GetPathRoot($usb)).TrimEnd('\')
    $disk=Get-CimInstance Win32_LogicalDisk | Where-Object DeviceID -eq $driveId
    if ($null -eq $disk -or $disk.DriveType -ne 2) { throw 'Target is not a removable drive' }
    $target=Join-Path $usb $name
    if (Test-Path -LiteralPath $target) { throw 'USB source directory already exists' }
    $files=@(Get-ChildItem -LiteralPath $output -File -Recurse -Force)
    if ($disk.FreeSpace -lt ($files | Measure-Object Length -Sum).Sum+100MB) { throw 'Insufficient USB space' }
    Copy-Item -LiteralPath $output -Destination $target -Recurse -Force
    if (@(Get-ChildItem -LiteralPath $target -File -Recurse -Force).Count -ne $files.Count) { throw 'USB source file count mismatch' }
    foreach ($file in $files) {
        $copied=Join-Path $target $file.FullName.Substring($output.Length+1)
        if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash) { throw "USB source checksum mismatch: $copied" }
    }
    Write-Host "USB_SOURCE_READY $target"
}
Write-Host "SOURCE_READY $output"
