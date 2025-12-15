param(
  [string]$KeystorePath = "$(Join-Path $PSScriptRoot '..\\release.jks')",
  [string]$Alias = "release"
)

$ErrorActionPreference = 'Stop'

$KeystorePath = (Resolve-Path $KeystorePath).Path

if (!(Test-Path $KeystorePath)) {
  throw "Keystore not found: $KeystorePath"
}

Write-Host "Keystore: $KeystorePath"
Write-Host "Alias: $Alias"
Write-Host ""

$bytes = [System.IO.File]::ReadAllBytes($KeystorePath)
$b64 = [Convert]::ToBase64String($bytes)

Write-Host "Copy into GitHub Secrets:"
Write-Host "ANDROID_KEYSTORE_BASE64 = (base64 of release.jks)"
Write-Host "ANDROID_KEYSTORE_PASSWORD = <your store password>"
Write-Host "ANDROID_KEY_ALIAS = $Alias"
Write-Host "ANDROID_KEY_PASSWORD = <your key password>"
Write-Host ""

Write-Host "ANDROID_KEYSTORE_BASE64 (first 120 chars):"
Write-Host ($b64.Substring(0, [Math]::Min(120, $b64.Length)) + "...")
Write-Host ""

Write-Host "Full base64 written to: $KeystorePath.base64.txt"
[System.IO.File]::WriteAllText("$KeystorePath.base64.txt", $b64, [System.Text.Encoding]::UTF8)
