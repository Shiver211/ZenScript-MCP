#requires -Version 7.5
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'InstanceRuntime.psm1')
Import-Module (Join-Path $PSScriptRoot 'ScenarioRunner.psm1')
Import-Module (Join-Path $PSScriptRoot 'McpClient.psm1')

function Get-RunFiles {
    param([string]$Root, [string]$Filter = '*')
    if (-not (Test-Path -LiteralPath $Root)) { return @() }
    return @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Filter | Sort-Object FullName | ForEach-Object {
        @{ path = [IO.Path]::GetRelativePath($Root, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    })
}

function Get-BatchInput {
    param([hashtable]$Config, [string]$BatchFile)
    $path = (Resolve-Path -LiteralPath $BatchFile).Path
    $batch = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json -AsHashtable -DateKind String
    if (-not $batch['scenarios'] -or $batch.scenarios.Count -gt 1000) { throw '批次需要 1–1000 个 scenarios。' }
    if (-not $Config.ContainsKey('baselineDirectory') -or -not $Config.ContainsKey('worldName')) {
        throw '批次需要独立 baselineDirectory 和 worldName；每个场景均从离线基线开始。'
    }
    $scenarios = @()
    $ids = @{}
    foreach ($entry in $batch.scenarios) {
        $file = [IO.Path]::GetFullPath($entry.file, (Split-Path $path))
        $scenario = Get-Content -Raw -LiteralPath $file | ConvertFrom-Json -AsHashtable -DateKind String
        if (-not $entry['id'] -or $ids.ContainsKey($entry.id)) { throw '批次条目必须有唯一 id。' }
        $ids[$entry.id] = $true
        if ($entry.ContainsKey('parameters')) {
            if (-not $scenario.ContainsKey('parameters')) { $scenario.parameters = @{} }
            foreach ($key in $entry.parameters.Keys) { $scenario.parameters[$key] = $entry.parameters[$key] }
        }
        $scenarios += @{ id = $entry.id; path = $file; sha256 = (Get-FileHash -LiteralPath $file).Hash; value = $scenario }
    }
    $inputs = [ordered]@{
        configHash = (Get-FileHash -LiteralPath $Config.configPath).Hash
        batchHash = (Get-FileHash -LiteralPath $path).Hash
        scenarios = @($scenarios | ForEach-Object { [ordered]@{ id = $_.id; sha256 = $_.sha256 } })
        scripts = Get-RunFiles (Join-Path $Config.gameDirectory 'scripts') '*.zs'
        mods = Get-RunFiles (Join-Path $Config.gameDirectory 'mods') '*.jar'
        baseline = Get-RunFiles $Config.baselineDirectory
    }
    # 使用固定字段顺序与排序后的路径，不对 Hashtable 的枚举顺序计算身份。
    $lines = @($inputs.configHash, $inputs.batchHash)
    foreach ($group in @('scenarios', 'scripts', 'mods', 'baseline')) {
        foreach ($entry in $inputs[$group]) {
            $name = if ($group -eq 'scenarios') { $entry.id } else { $entry.path }
            $lines += $group + ':' + $name + ':' + $entry.sha256
        }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    return @{ definition = $batch; scenarios = $scenarios; inputs = $inputs; fingerprint = $fingerprint }
}

function Save-ProcessEvidence {
    param([hashtable]$Config, [string]$Directory, [string]$Phase)
    $null = New-Item -ItemType Directory -Path $Directory -Force
    Write-RunJson (Join-Path $Directory ($Phase + '.json')) (Get-InstanceStatus $Config)
    $log = Join-Path $Config.gameDirectory 'logs/latest.log'
    if (Test-Path -LiteralPath $log) {
        $file = Get-Item -LiteralPath $log
        Write-RunJson (Join-Path $Directory ($Phase + '-log.json')) @{
            source = $file.FullName; length = $file.Length; modifiedAt = $file.LastWriteTimeUtc.ToString('o')
            coverage = 'metadata_only'; reason = '完整游戏日志保留在实例中，归档不复制启动凭据或账号参数。'
        }
    }
}

function Invoke-McpBatch {
    param([hashtable]$Config, [string]$BatchFile, [string]$ResumeDirectory, [string]$CancellationFile)
    $inputSet = Get-BatchInput $Config $BatchFile
    if ($ResumeDirectory) {
        $directory = (Resolve-Path -LiteralPath $ResumeDirectory).Path
        $manifest = Get-Content -Raw -LiteralPath (Join-Path $directory 'manifest.json') `
            | ConvertFrom-Json -AsHashtable -DateKind String
        if ($manifest.fingerprint -ne $inputSet.fingerprint) {
            throw 'resume_mismatch：配置、批次、场景、脚本、模组或世界基线已经变化，请开始新批次。'
        }
        $result = Get-Content -Raw -LiteralPath (Join-Path $directory 'result.json') `
            | ConvertFrom-Json -AsHashtable -DateKind String
    } else {
        $id = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        $directory = Join-Path $Config.outputDirectory ('batch-' + $id)
        $null = New-Item -ItemType Directory -Path $directory
        Write-RunJson (Join-Path $directory 'manifest.json') @{
            schemaVersion = 1; fingerprint = $inputSet.fingerprint; inputs = $inputSet.inputs
            batch = $inputSet.definition; worldName = $Config.worldName; resetPolicy = 'offline_baseline_before_each'
        }
        $result = @{ outcome = 'running'; attempts = @(); runDirectory = $directory; startedAt = [DateTime]::UtcNow.ToString('o') }
    }
    $resultPath = Join-Path $directory 'result.json'
    $result.outcome = 'running'
    Write-RunJson $resultPath $result
    $stopOnFailure = $inputSet.definition['stopOnFailure'] -ne $false
    try {
        foreach ($entry in $inputSet.scenarios) {
            if (@($result.attempts | Where-Object { $_.id -eq $entry.id -and $_.outcome -eq 'passed' }).Count) { continue }
            if ($CancellationFile -and (Test-Path -LiteralPath $CancellationFile)) {
                $result.outcome = 'inconclusive'; $result.reason = 'cancelled'; break
            }
            $attempt = @{ id = $entry.id; outcome = 'running'; phase = 'reset'; startedAt = [DateTime]::UtcNow.ToString('o') }
            $result.attempts += $attempt
            Write-RunJson $resultPath $result
            $processDirectory = Join-Path $directory ('process/attempt-' + $result.attempts.Count)
            try {
                $null = Stop-ManagedInstance $Config
                $null = Restore-InstanceWorld $Config
                $attempt.phase = 'startup'
                $null = Start-ManagedInstance $Config
                $connection = Connect-ManagedInstance $Config
                Enter-InstanceWorld $Config $connection
                Save-ProcessEvidence $Config $processDirectory 'started'
                $attempt.phase = 'scenario'
                $scenarioResult = Invoke-McpScenario $connection $entry.value (Join-Path $directory 'scenarios') `
                    -CancellationFile $CancellationFile
                $attempt.outcome = $scenarioResult.outcome
                $attempt.scenarioDirectory = $scenarioResult.runDirectory
                if ($scenarioResult.ContainsKey('failureCategory')) { $attempt.failureCategory = $scenarioResult.failureCategory }
                if ($scenarioResult.ContainsKey('error')) { $attempt.error = $scenarioResult.error }
                if ($scenarioResult.ContainsKey('reason')) { $attempt.reason = $scenarioResult.reason }
                if ((Get-InstanceStatus $Config).state -eq 'stopped') {
                    $attempt.outcome = 'runner_error'
                    $attempt.failureCategory = 'process_exit'
                }
            } catch {
                $attempt.outcome = 'runner_error'
                $attempt.reason = $_.Exception.Message
                $attempt.failureCategory = if ($attempt.phase -eq 'startup') { 'startup_failure' } `
                    elseif ($attempt.phase -eq 'reset') { 'reset_failure' } else { 'runner_failure' }
            }
            $attempt.endedAt = [DateTime]::UtcNow.ToString('o')
            Save-ProcessEvidence $Config $processDirectory 'finished'
            Write-RunJson $resultPath $result
            # 清理未完成、进程或传输故障不继续；普通失败仅在显式配置下恢复基线后执行下一场景。
            if ($attempt.outcome -eq 'runner_error' -or ($attempt['error'] -and $attempt.error.code -eq 'cleanup_pending') `
                    -or ($stopOnFailure -and $attempt.outcome -ne 'passed')) { break }
        }
    } finally {
        try {
            $result.shutdown = Stop-ManagedInstance $Config
        } catch {
            $result.shutdown = @{ state = 'failed'; reason = $_.Exception.Message }
        }
        $allPassed = @($inputSet.scenarios | Where-Object {
            $id = $_.id
            -not @($result.attempts | Where-Object { $_.id -eq $id -and $_.outcome -eq 'passed' }).Count
        }).Count -eq 0
        if ($result.shutdown.state -ne 'stopped') { $result.outcome = 'runner_error' } `
        elseif ($allPassed) { $result.outcome = 'passed' } `
        elseif ($result.outcome -eq 'running') {
            $result.outcome = 'failed'
            foreach ($outcome in @('inconclusive', 'blocked', 'runner_error')) {
                if (@($result.attempts | Where-Object outcome -eq $outcome).Count) { $result.outcome = $outcome }
            }
        }
        $result.endedAt = [DateTime]::UtcNow.ToString('o')
        Write-RunJson $resultPath $result
    }
    return $result
}

Export-ModuleMember -Function Invoke-McpBatch
