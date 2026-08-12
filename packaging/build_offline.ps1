[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$rootDir = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -LiteralPath (Join-Path $rootDir 'VERSION') -Raw -Encoding UTF8).Trim()
$bundleName = "zhihui-xinguan-offline-$version-kylin"
$distDir = Join-Path $rootDir 'dist'
$bundleDir = Join-Path $distDir $bundleName
$payloadDir = Join-Path $bundleDir 'payload'
$smartExcelRoot = 'D:\Codex_project\smart_excel'
$coreDir = Join-Path $smartExcelRoot 'runtime\hop\lib\core'
$vendorDir = Join-Path $smartExcelRoot 'vendor'
$buildDir = Join-Path $rootDir "build\package-$version-release2"
$classDir = Join-Path $buildDir 'classes'
$libDir = Join-Path $buildDir 'app\lib'

$javac = (Get-Command javac -ErrorAction Stop).Source
$javaVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($javaVersion -notmatch '^javac\s+(\d+)' -or [int]$Matches[1] -lt 17) { throw 'JDK 17 or newer is required for the build' }

$jarNames = @(
    'poi-5.5.1.jar','poi-ooxml-5.5.1.jar','poi-ooxml-lite-5.5.1.jar','xmlbeans-5.3.0.jar',
    'commons-compress-1.28.0.jar','commons-io-2.21.0.jar','commons-collections4-4.5.0.jar',
    'commons-codec-1.21.0.jar','SparseBitSet-1.3.jar','curvesapi-1.08.jar','commons-math3-3.6.1.jar',
    'log4j-api-2.25.4.jar','log4j-core-2.25.4.jar'
)

if (Test-Path -LiteralPath $bundleDir) { throw "Output already exists; inspect or move it first: $bundleDir" }
if (Test-Path -LiteralPath $buildDir) { throw "Build workspace already exists; inspect or move it first: $buildDir" }
New-Item -ItemType Directory -Path $classDir,$libDir,$payloadDir -Force | Out-Null

$classpathFiles = @()
foreach ($name in $jarNames) {
    $source = Join-Path $coreDir $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing required library: $source" }
    Copy-Item -LiteralPath $source -Destination $libDir
    $classpathFiles += $source
}
$classpath = $classpathFiles -join ';'
$sources = Get-ChildItem -LiteralPath (Join-Path $rootDir 'src') -File -Filter '*.java' | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 17 -cp $classpath -d $classDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }

$jarExe = Join-Path (Split-Path -Parent $javac) 'jar.exe'
$jarFile = Join-Path $buildDir 'app\zhihui-xinguan.jar'
& $jarExe --create --file $jarFile -C $classDir .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }

$appRoot = Join-Path $buildDir 'bundle-app'
New-Item -ItemType Directory -Path (Join-Path $appRoot 'app'),(Join-Path $appRoot 'web\assets'),(Join-Path $appRoot 'seed') -Force | Out-Null
Copy-Item -LiteralPath $jarFile -Destination (Join-Path $appRoot 'app')
Copy-Item -LiteralPath $libDir -Destination (Join-Path $appRoot 'app') -Recurse
Copy-Item -LiteralPath (Join-Path $rootDir 'web\assets\style.css') -Destination (Join-Path $appRoot 'web\assets')
Copy-Item -LiteralPath (Join-Path $rootDir 'web\assets\html5shiv.js') -Destination (Join-Path $appRoot 'web\assets')
Copy-Item -LiteralPath (Join-Path $rootDir 'start.sh'),(Join-Path $rootDir 'VERSION'),(Join-Path $rootDir 'README_zh.md'),(Join-Path $rootDir 'THIRD_PARTY_NOTICES.md') -Destination $appRoot
$sheetDir = Get-ChildItem -LiteralPath $rootDir -Directory | Where-Object {
    (Get-ChildItem -LiteralPath $_.FullName -File -Filter '*.et' -ErrorAction SilentlyContinue).Count -ge 2
} | Select-Object -First 1
if ($null -eq $sheetDir) { throw 'Could not find the source spreadsheet directory' }
$seedFiles = @(Get-ChildItem -LiteralPath $sheetDir.FullName -File -Filter '*.et')
if ($seedFiles.Count -ne 2) { throw "Expected exactly two .et seed files in $($sheetDir.FullName)" }
for ($i = 0; $i -lt $seedFiles.Count; $i++) {
    Copy-Item -LiteralPath $seedFiles[$i].FullName -Destination (Join-Path $appRoot ("seed\template-{0}-202607.et" -f ($i + 1)))
}

Push-Location $appRoot
try { & tar.exe -czf (Join-Path $payloadDir 'app.tar.gz') *; if ($LASTEXITCODE -ne 0) { throw 'Failed to create app payload' } }
finally { Pop-Location }

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.sh') -Destination $bundleDir
Copy-Item -LiteralPath (Join-Path $rootDir 'README_zh.md') -Destination $bundleDir
Copy-Item -LiteralPath (Join-Path $rootDir 'THIRD_PARTY_NOTICES.md') -Destination $bundleDir
foreach ($name in @('microsoft-jdk-21.0.12-linux-aarch64.tar.gz','microsoft-jdk-21.0.12-linux-x64.tar.gz')) {
    $source = Join-Path $vendorDir $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing offline runtime: $source" }
    Copy-Item -LiteralPath $source -Destination $payloadDir
}

$lines = @()
Get-ChildItem -LiteralPath $payloadDir -File | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $lines += "$hash  payload/$($_.Name)"
}
[System.IO.File]::WriteAllText((Join-Path $bundleDir 'SHA256SUMS'),($lines -join "`n")+"`n",[System.Text.UTF8Encoding]::new($false))

$archive = Join-Path $distDir "$bundleName.tar.gz"
Push-Location $distDir
try { & tar.exe -czf $archive $bundleName; if ($LASTEXITCODE -ne 0) { throw 'Failed to create offline archive' } }
finally { Pop-Location }

Write-Host "Offline directory: $bundleDir"
Write-Host "Complete archive: $archive"
Write-Host "Payload checksums: $($lines.Count) files"
