<#
.SYNOPSIS
    Batch-generates full-day demand CSVs for all study dates in cluster/dates.txt.

.DESCRIPTION
    Executes generate_demand_csvs.py using the local Python venv to produce
    demand_{date}.csv files in cluster/demand/.
    Idempotent: skips existing valid CSVs unless -Force is specified.

.PARAMETER DatesFile
    Path to dates.txt (default: $PSScriptRoot\dates.txt)

.PARAMETER OutputDir
    Output directory (default: $PSScriptRoot\demand)

.PARAMETER PythonExe
    Path to Python executable (default: D:\Mitarbeitende\gw2128\repositories\mirova\venv\Scripts\python.exe)

.PARAMETER Force
    Regenerate all CSVs even if valid files exist.

.EXAMPLE
    .\cluster\generate_demand_csvs.ps1
    .\cluster\generate_demand_csvs.ps1 -Force
#>

param(
    [string]$DatesFile = "$PSScriptRoot\dates.txt",
    [string]$OutputDir = "$PSScriptRoot\demand",
    [string]$PythonExe = "D:\Mitarbeitende\gw2128\repositories\mirova\venv\Scripts\python.exe",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $PythonExe)) {
    Write-Error "Python executable not found at: $PythonExe"
    exit 1
}

$ScriptPath = Join-Path $PSScriptRoot "generate_demand_csvs.py"
$ArgsList = @(
    $ScriptPath,
    "--dates", $DatesFile,
    "--output-dir", $OutputDir
)

if ($Force) {
    $ArgsList += "--force"
}

Write-Host "Running: $PythonExe $($ArgsList -join ' ')" -ForegroundColor Cyan
& $PythonExe @ArgsList
if ($LASTEXITCODE -ne 0) {
    Write-Error "Batch demand generation failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
