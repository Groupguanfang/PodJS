param([string]$DevEcoStudioHome = $env:DEVECO_STUDIO_HOME)
$ErrorActionPreference = 'Stop'
# Run only in an isolated build checkout: this temporarily changes dist assets
# and rebuilds the unsigned HAP. No application installation is performed.
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BundleDir = Join-Path $ProjectRoot 'dist/harmonyos-watch'
$RawfileDir = Join-Path $ProjectRoot 'platforms/harmony/entry/src/main/resources/rawfile'
$ManifestPath = Join-Path $BundleDir 'pod.manifest.json'
$RawManifestPath = Join-Path $RawfileDir 'pod.manifest.json'
$SavedManifest = [IO.File]::ReadAllBytes($ManifestPath)
$SavedRawManifest = [IO.File]::ReadAllBytes($RawManifestPath)
$Utf8 = New-Object Text.UTF8Encoding($false, $true)
$Manifest = $Utf8.GetString($SavedManifest) | ConvertFrom-Json
$ProbeName = 'podjs-packaging-probe'
$FileName = 'background/8e7bd512486a25b1e2d408c740d788563a283e3fa239a6040366c1210d906934.js'
$ProbePath = Join-Path $BundleDir $FileName
$RawProbePath = Join-Path $RawfileDir $FileName
if ((Test-Path -LiteralPath $ProbePath) -or (Test-Path -LiteralPath $RawProbePath) -or
    $null -ne $Manifest.background.$ProbeName) { throw 'Packaging probe already exists; refusing to overwrite' }
$Source = $Utf8.GetBytes("globalThis.backgroundHandler = function () { return 'success'; };`n")
$Hash = [Security.Cryptography.SHA256]::Create()
try { $Digest = ([BitConverter]::ToString($Hash.ComputeHash($Source))).Replace('-', '').ToLowerInvariant() }
finally { $Hash.Dispose() }
$Artifact = [PSCustomObject]@{ file = $FileName; bytes = $Source.Length; sha256 = $Digest }
if ($null -eq $Manifest.background) { $Manifest | Add-Member -NotePropertyName background -NotePropertyValue ([PSCustomObject]@{}) -Force }
$Manifest.background | Add-Member -NotePropertyName $ProbeName -NotePropertyValue $Artifact
function Save-ProbeManifest {
  [IO.File]::WriteAllText($ManifestPath, ($Manifest | ConvertTo-Json -Depth 30), $Utf8)
}
function Build-Probe {
  & (Join-Path $PSScriptRoot 'build-harmony-runtime.ps1') -DevEcoStudioHome $DevEcoStudioHome
}
function Expect-Rejected([string]$Expected) {
  Save-ProbeManifest
  $Rejected = $false
  try { Build-Probe } catch {
    if ($_.Exception.Message -notlike "*$Expected*") { throw }
    $Rejected = $true
  }
  if (-not $Rejected) { throw 'Invalid artifact was packaged' }
}
try {
  New-Item -ItemType Directory -Force (Split-Path -Parent $ProbePath) | Out-Null
  [IO.File]::WriteAllBytes($ProbePath, $Source)
  $Artifact.file = '../main.js'; Expect-Rejected 'Invalid background artifact'; $Artifact.file = $FileName
  $Artifact.sha256 = ('0' * 64); Expect-Rejected 'integrity mismatch'; $Artifact.sha256 = $Digest
  $Artifact.bytes = $Source.Length + 1; Expect-Rejected 'integrity mismatch'; $Artifact.bytes = $Source.Length
  Save-ProbeManifest
  Build-Probe
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $Hap = Join-Path $BundleDir 'podjs-harmony-watch-unsigned.hap'
  $Zip = [IO.Compression.ZipFile]::OpenRead($Hap)
  try {
    $ScriptEntries = @($Zip.Entries | Where-Object { $_.FullName.EndsWith('/rawfile/' + $FileName) })
    $ManifestEntries = @($Zip.Entries | Where-Object { $_.FullName.EndsWith('/rawfile/pod.manifest.json') })
    if ($ScriptEntries.Count -ne 1 -or $ManifestEntries.Count -ne 1) {
      throw ('HAP missing unique background assets; entries: ' + (($Zip.Entries | ForEach-Object { $_.FullName }) -join ', '))
    }
    $Stream = $ScriptEntries[0].Open(); $Memory = New-Object IO.MemoryStream
    try { $Stream.CopyTo($Memory); $PackedBytes = $Memory.ToArray() }
    finally { $Stream.Dispose(); $Memory.Dispose() }
    if ([Convert]::ToBase64String($PackedBytes) -cne [Convert]::ToBase64String($Source)) { throw 'HAP source differs from manifest-approved bytes' }
    $Reader = New-Object IO.StreamReader($ManifestEntries[0].Open(), $Utf8)
    try { $PackedManifest = $Reader.ReadToEnd() | ConvertFrom-Json } finally { $Reader.Dispose() }
    $PackedArtifact = $PackedManifest.background.$ProbeName
    if ($PackedArtifact.file -cne $FileName -or $PackedArtifact.sha256 -cne $Digest -or $PackedArtifact.bytes -ne $Source.Length) {
      throw 'HAP manifest does not match packaged handler'
    }
  } finally { $Zip.Dispose() }
  Write-Host 'Harmony HAP background packaging: path/hash/size rejection and actual archived source/manifest passed'
} finally {
  [IO.File]::WriteAllBytes($ManifestPath, $SavedManifest)
  [IO.File]::WriteAllBytes($RawManifestPath, $SavedRawManifest)
  # These exact files were created by this probe after checking nonexistence.
  if (Test-Path -LiteralPath $ProbePath) { [IO.File]::Delete($ProbePath) }
  if (Test-Path -LiteralPath $RawProbePath) { [IO.File]::Delete($RawProbePath) }
}
# Restore the output artifact too; the probe HAP is never the final build.
Build-Probe
