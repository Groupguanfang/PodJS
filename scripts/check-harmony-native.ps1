param(
  [Parameter(Mandatory=$true)][string]$ProjectRoot,
  [string]$NativeSdk = 'C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/native'
)
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $NativeSdk 'llvm/bin/clang++.exe'
$sysroot = Join-Path $NativeSdk 'sysroot'
if (!(Test-Path $compiler) -or !(Test-Path $sysroot)) { throw 'Harmony native compiler/sysroot missing' }
$sourceDir = Join-Path $ProjectRoot 'platforms/harmony/entry/src/main/cpp'
$headerDir = Join-Path $ProjectRoot 'crates/podjs-runtime/include'
$outputDir = Join-Path $ProjectRoot '.native-a11y-check'
New-Item -ItemType Directory -Force $outputDir | Out-Null
foreach ($name in @('accessibility_provider', 'napi_init', 'gles_renderer')) {
  & $compiler '--target=aarch64-linux-ohos' "--sysroot=$sysroot" '-std=c++17' '-fPIC' '-O2' '-Wall' '-Wextra' `
    "-I$headerDir" '-c' (Join-Path $sourceDir "$name.cpp") '-o' (Join-Path $outputDir "$name.o")
  if ($LASTEXITCODE -ne 0) { throw "Harmony compile failed: $name" }
}
Write-Output 'Harmony ARM64 native translation units compiled; runtime archive link and ArkTS packaging are separate gates.'
