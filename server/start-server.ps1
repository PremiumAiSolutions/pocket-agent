# Starts the OpenCode agent server on this PC so the Pocket Agent app can reach it.
#
# Usage (PowerShell):
#   .\start-server.ps1 -Project C:\repos\my-app
#
# The password and Venice key are read from environment variables so they
# never get written into this file.

param(
    [string]$Project = (Get-Location).Path,
    [int]$Port = 4096
)

# Finds the opencode command, including when npm's global folder is not on PATH.
function Find-OpenCode {
    $command = Get-Command opencode -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidate = Join-Path (npm prefix -g) "opencode.cmd"
    if (Test-Path $candidate) { return $candidate }
    return $null
}

$opencode = Find-OpenCode
if (-not $opencode) {
    Write-Host "Installing OpenCode..."
    npm i -g "@opencode/cli@latest"
    $opencode = Find-OpenCode
}
if (-not $opencode) {
    Write-Host "OpenCode installed but its command could not be found. Run 'npm prefix -g' and check that folder for opencode.cmd." -ForegroundColor Red
    exit 1
}

if (-not $env:OPENCODE_PASSWORD) {
    $secure = Read-Host "Choose a server password (you will type it into the phone app)" -AsSecureString
    $env:OPENCODE_PASSWORD = [System.Net.NetworkCredential]::new("", $secure).Password
}

if (-not $env:VENICE_API_KEY) {
    Write-Host "VENICE_API_KEY is not set. Set it to use Venice models:" -ForegroundColor Yellow
    Write-Host '  $env:VENICE_API_KEY = "your-key"' -ForegroundColor Yellow
}

$addresses = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
    Select-Object -ExpandProperty IPAddress

Write-Host ""
Write-Host "In the phone app, use one of these as the server address:"
foreach ($address in $addresses) { Write-Host "  ${address}:$Port" }
Write-Host ""

Set-Location $Project
& $opencode serve --hostname 0.0.0.0 --port $Port
