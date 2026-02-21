param(
  [int]$IntervalSeconds = 60,
  [int]$MaxCycles = 0
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkspaceRoot = Resolve-Path (Join-Path $ScriptDir "..")
Set-Location $WorkspaceRoot

python -m credential_defense.cli watchdog-daemon --interval $IntervalSeconds --max-cycles $MaxCycles

