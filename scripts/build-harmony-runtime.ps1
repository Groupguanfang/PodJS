param(
  [string]$DevEcoStudioHome = $env:DEVECO_STUDIO_HOME,
  [string]$Configuration = "release"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$HarmonyRoot = Join-Path $ProjectRoot "platforms/harmony"
$TargetTriple = "aarch64-unknown-linux-ohos"
$OhosArch = "arm64-v8a"

if (-not $DevEcoStudioHome) {
  $DevEcoStudioHome = Join-Path $env:ProgramFiles "Huawei/DevEco Studio"
}
if (-not (Test-Path $DevEcoStudioHome)) {
  throw "DevEco Studio was not found. Set DEVECO_STUDIO_HOME."
}
$DevEcoStudioHome = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($DevEcoStudioHome).ShortPath

$SdkRoot = Join-Path $DevEcoStudioHome "sdk"
$NativeRoot = Join-Path $SdkRoot "default/openharmony/native"
$LlvmBin = Join-Path $NativeRoot "llvm/bin"
$Sysroot = Join-Path $NativeRoot "sysroot"
$Clang = Join-Path $LlvmBin "clang.exe"
$Ar = Join-Path $LlvmBin "llvm-ar.exe"
$JavaHome = Join-Path $DevEcoStudioHome "jbr"
$Node = Join-Path $DevEcoStudioHome "tools/node/node.exe"
$Hvigor = Join-Path $DevEcoStudioHome "tools/hvigor/bin/hvigorw.js"
$Ohpm = Join-Path $DevEcoStudioHome "tools/ohpm/bin/ohpm.bat"
$ClangResourceRoot = Join-Path $NativeRoot "llvm/lib/clang"
$ClangResourceDir = Get-ChildItem -Directory $ClangResourceRoot |
  Sort-Object { [version]$_.Name } -Descending |
  Select-Object -First 1

foreach ($Required in @($Clang, $Ar, $Node, $Hvigor, $Ohpm, (Join-Path $JavaHome "bin/java.exe"))) {
  if (-not (Test-Path $Required)) { throw "Required DevEco tool is missing: $Required" }
}
if (-not $ClangResourceDir) { throw "Clang resource headers are missing below $ClangResourceRoot" }

$env:DEVECO_SDK_HOME = $SdkRoot
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
$env:LIBCLANG_PATH = $LlvmBin
$env:CC_aarch64_unknown_linux_ohos = $Clang
$env:AR_aarch64_unknown_linux_ohos = $Ar
$env:CFLAGS_aarch64_unknown_linux_ohos = "--target=aarch64-linux-ohos --sysroot=$Sysroot -D__MUSL__"
$env:C_INCLUDE_PATH = @(
  (Join-Path $ClangResourceDir.FullName "include"),
  (Join-Path $Sysroot "usr/include"),
  (Join-Path $Sysroot "usr/include/aarch64-linux-ohos")
) -join ";"
$env:CARGO_TARGET_AARCH64_UNKNOWN_LINUX_OHOS_LINKER = $Clang
$env:RUSTFLAGS = "-C link-arg=--target=aarch64-linux-ohos -C link-arg=--sysroot=$Sysroot"

& rustup target add $TargetTriple
if ($LASTEXITCODE -ne 0) { throw "rustup target add failed" }

$CargoArgs = @("build", "-p", "podjs-runtime", "--target", $TargetTriple)
if ($Configuration -eq "release") { $CargoArgs += "--release" }
& cargo @CargoArgs --manifest-path (Join-Path $ProjectRoot "Cargo.toml")
if ($LASTEXITCODE -ne 0) { throw "HarmonyOS Rust build failed" }

$Profile = if ($Configuration -eq "release") { "release" } else { "debug" }
$RuntimeLib = Join-Path $ProjectRoot "target/$TargetTriple/$Profile/libpodjs_runtime.a"
$PrebuiltDir = Join-Path $HarmonyRoot "entry/src/main/cpp/prebuilt/$OhosArch"
New-Item -ItemType Directory -Force $PrebuiltDir | Out-Null
Copy-Item -Force $RuntimeLib (Join-Path $PrebuiltDir "libpodjs_runtime.a")

$BundleDir = Join-Path $ProjectRoot "dist/harmonyos-watch"
$RawfileDir = Join-Path $HarmonyRoot "entry/src/main/resources/rawfile"
$BackgroundManifest = Get-Content -Raw -LiteralPath (Join-Path $BundleDir "pod.manifest.json") | ConvertFrom-Json
if ($BackgroundManifest.schema -ne 1 -or $BackgroundManifest.target -ne "harmonyos-watch") {
  throw "Invalid Harmony background manifest target or schema"
}
$BackgroundArtifacts = @()
if ($null -ne $BackgroundManifest.background) {
  if ($BackgroundManifest.background -isnot [PSCustomObject]) { throw "Invalid background manifest" }
  $BackgroundArtifacts = @($BackgroundManifest.background.PSObject.Properties)
}
if ($BackgroundArtifacts.Count -gt 32) { throw "Too many background handlers" }
foreach ($Property in $BackgroundArtifacts) {
  $Artifact = $Property.Value
  if ($Property.Name -cnotmatch '^[A-Za-z0-9_.:-]{1,128}$' -or
      $Artifact.file -isnot [string] -or $Artifact.file -cnotmatch '^background/[0-9a-f]{64}\.js$' -or
      $Artifact.sha256 -isnot [string] -or $Artifact.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
      ($Artifact.bytes -isnot [long] -and $Artifact.bytes -isnot [int]) -or
      $Artifact.bytes -lt 1 -or $Artifact.bytes -gt 1048576) { throw "Invalid background artifact" }
  $BackgroundSource = Join-Path $BundleDir $Artifact.file
  if ((Get-Item -LiteralPath $BackgroundSource).Length -ne $Artifact.bytes -or
      (Get-FileHash -Algorithm SHA256 -LiteralPath $BackgroundSource).Hash.ToLowerInvariant() -cne $Artifact.sha256) {
    throw "Background artifact integrity mismatch"
  }
}
foreach ($Asset in @("main.js", "main.pak", "pod.manifest.json")) {
  $Source = Join-Path $BundleDir $Asset
  if (-not (Test-Path $Source)) { throw "Missing app asset: $Source. Run pod build first." }
  Copy-Item -Force $Source (Join-Path $RawfileDir $Asset)
}
if ($BackgroundArtifacts.Count -gt 0) {
  New-Item -ItemType Directory -Force (Join-Path $RawfileDir "background") | Out-Null
  foreach ($Property in $BackgroundArtifacts) {
    Copy-Item -LiteralPath (Join-Path $BundleDir $Property.Value.file) -Destination (Join-Path $RawfileDir $Property.Value.file) -Force
  }
}

Push-Location $HarmonyRoot
try {
  & $Ohpm install
  if ($LASTEXITCODE -ne 0) { throw "ohpm install failed" }
  & $Node $Hvigor --mode module -p product=default assembleHap
  if ($LASTEXITCODE -ne 0) { throw "HarmonyOS HAP packaging failed" }
} finally {
  Pop-Location
}

$Hap = Join-Path $HarmonyRoot "entry/build/default/outputs/default/entry-default-unsigned.hap"
if (-not (Test-Path $Hap)) { throw "Expected HAP was not produced: $Hap" }
New-Item -ItemType Directory -Force $BundleDir | Out-Null
Copy-Item -Force $Hap (Join-Path $BundleDir "podjs-harmony-watch-unsigned.hap")
Write-Host "PodJS HarmonyOS package: $(Join-Path $BundleDir 'podjs-harmony-watch-unsigned.hap')"
