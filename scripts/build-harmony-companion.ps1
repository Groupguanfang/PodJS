param([string]$DevEcoStudioHome = $env:DEVECO_STUDIO_HOME, [switch]$Example)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$DevEcoStudioHome) { $DevEcoStudioHome = Join-Path $env:ProgramFiles 'Huawei/DevEco Studio' }
$node = Join-Path $DevEcoStudioHome 'tools/node/node.exe'
$hvigor = Join-Path $DevEcoStudioHome 'tools/hvigor/bin/hvigorw.js'
$ohpm = Join-Path $DevEcoStudioHome 'tools/ohpm/bin/ohpm.bat'
$env:JAVA_HOME = Join-Path $DevEcoStudioHome 'jbr'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$env:DEVECO_SDK_HOME = Join-Path $DevEcoStudioHome 'sdk'
foreach ($tool in @($node, $hvigor, $ohpm, (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
  if (!(Test-Path $tool)) { throw "Missing DevEco tool: $tool" }
}
Push-Location (Join-Path $project 'platforms/harmony')
try {
  & $ohpm install
  if ($LASTEXITCODE -ne 0) { throw 'ohpm install failed' }
  if ($Example) {
    & $node $hvigor --mode module -p module=companion_example@default -p product=default assembleHap
  } else {
    & $node $hvigor --mode module -p module=companion@default -p product=default assembleHar
  }
  if ($LASTEXITCODE -ne 0) { throw 'Companion build failed' }
} finally { Pop-Location }
if ($Example) {
  $haps = @(Get-ChildItem -LiteralPath (Join-Path $project 'platforms/harmony/companion_example/build/default/outputs/default') -Filter '*-unsigned.hap')
  if ($haps.Count -ne 1) { throw 'Expected exactly one unsigned companion example HAP' }
  $output = Join-Path $project 'dist/harmonyos-companion-example'
  New-Item -ItemType Directory -Force $output | Out-Null
  Copy-Item -LiteralPath $haps[0].FullName -Destination (Join-Path $output 'podjs-companion-example-unsigned.hap') -Force
  Write-Output "Companion example HAP: $output/podjs-companion-example-unsigned.hap"
  return
}
$har = Join-Path $project 'platforms/harmony/companion/build/default/outputs/default/companion.har'
if (!(Test-Path $har)) { throw 'Expected companion HAR is missing' }
$output = Join-Path $project 'dist/harmonyos-companion'
New-Item -ItemType Directory -Force $output | Out-Null
Copy-Item -LiteralPath $har -Destination (Join-Path $output 'podjs-companion.har') -Force
Write-Output "Companion state SDK HAR: $output/podjs-companion.har"
