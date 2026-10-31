#requires -Version 7.5
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('start', 'status', 'stop', 'restart', 'run', 'restore')][string]$Command,
    [Parameter(Mandatory)][string]$Config,
    [string]$Batch,
    [string]$ResumeDirectory,
    [string]$CancellationFile,
    [switch]$EnterWorld,
    [switch]$Force
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'InstanceRuntime.psm1')
Import-Module (Join-Path $PSScriptRoot 'BatchRunner.psm1')
$mutex = $null
$acquired = $false
try {
    $context = Get-InstanceContext $Config
    $keyBytes = [Text.Encoding]::UTF8.GetBytes($context.gameDirectory.ToLowerInvariant())
    $key = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($keyBytes))
    $mutex = [Threading.Mutex]::new($false, ('Local\ZenScriptMcp-' + $key))
    # status 可以旁观长批次；其余命令在同一实例上互斥。
    if ($Command -ne 'status') {
        try { $acquired = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
        if (-not $acquired) { throw 'runner_busy：该实例已有运行器操作；取消批次请创建 CancellationFile。' }
    }
    $result = switch ($Command) {
        'status' { Get-InstanceStatus $context }
        'stop' { Stop-ManagedInstance $context -Force:$Force }
        'restore' { Restore-InstanceWorld $context }
        { $_ -in @('start', 'restart') } {
            if ($Command -eq 'restart') { $null = Stop-ManagedInstance $context -Force:$Force }
            $started = Start-ManagedInstance $context
            if ($EnterWorld) { Enter-InstanceWorld $context (Connect-ManagedInstance $context) }
            $started
        }
        'run' {
            if (-not $Batch) { throw 'run 需要 -Batch。' }
            Invoke-McpBatch $context $Batch -ResumeDirectory $ResumeDirectory -CancellationFile $CancellationFile
        }
    }
    $result | ConvertTo-Json -Depth 100
    if ($result['outcome'] -and $result.outcome -ne 'passed') { exit 1 }
} catch {
    @{ outcome = 'runner_error'; reason = $_.Exception.Message } | ConvertTo-Json -Depth 5
    exit 1
} finally {
    if ($acquired) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
