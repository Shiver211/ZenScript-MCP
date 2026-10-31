#requires -Version 7.5
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'McpClient.psm1')
Import-Module (Join-Path $PSScriptRoot 'PlayerTasks.psm1')

function Write-RunJson {
    param([string]$Path, $Value)
    $destination = [IO.Path]::GetFullPath($Path)
    $temporary = $destination + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    [IO.File]::WriteAllText($temporary, (ConvertTo-Json -Depth 100 -InputObject $Value), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $destination, $true)
}

function Resolve-ScenarioValue {
    param($Value, [hashtable]$Values)
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Count -eq 1 -and $Value.Contains('$ref')) {
            $reference = [string]$Value['$ref']
            $parts = $reference.Split('#', 2)
            if ($parts.Count -ne 2 -or -not $Values.ContainsKey($parts[0])) {
                throw "无效场景引用：$reference"
            }
            $current = $Values[$parts[0]]
            if ($parts[1]) {
                if (-not $parts[1].StartsWith('/')) {
                    throw "引用路径必须是 JSON Pointer：$reference"
                }
                foreach ($encoded in $parts[1].Substring(1).Split('/')) {
                    if ($encoded -match '~([^01]|$)') {
                        throw "无效引用转义：$reference"
                    }
                    $key = $encoded.Replace('~1', '/').Replace('~0', '~')
                    if ($current -is [System.Collections.IDictionary] -and $current.Contains($key)) {
                        $current = $current[$key]
                    } elseif ($current -is [array] -and $key -match '^(0|[1-9][0-9]*)$' `
                            -and [long]$key -lt $current.Count) {
                        $current = $current[[int]$key]
                    } else {
                        throw "引用字段不存在：$reference"
                    }
                }
            }
            return ,$current
        }
        $result = @{}
        foreach ($key in $Value.Keys) {
            $result[$key] = Resolve-ScenarioValue $Value[$key] $Values
        }
        return $result
    }
    if ($Value -is [array]) {
        $result = @($Value | ForEach-Object { Resolve-ScenarioValue $_ $Values })
        return ,$result
    }
    return ,$Value
}

function Get-ScenarioOutcome {
    param([hashtable]$Result)
    if ($Result.status -eq 'error') {
        $code = $Result.error.code
        if ($code -in @('world_unavailable', 'player_unavailable', 'source_unavailable', 'unsupported_capability',
                'permission_denied', 'busy', 'restart_required', 'stale_reference', 'target_unloaded')) {
            return 'blocked'
        }
        if ($code -in @('confirmation_timeout', 'deadline_exceeded', 'cancelled', 'cleanup_pending')) {
            return 'inconclusive'
        }
        return 'failed'
    }
    if ($Result.ContainsKey('data') -and $Result.data.ContainsKey('outcome')) {
        return $Result.data.outcome
    }
    return 'passed'
}

function Get-ScenarioRequirements {
    param([hashtable]$Connection, [array]$Requires)
    $status = Invoke-McpTool $Connection 'mc_status' @{}
    $missing = @()
    foreach ($requirement in $Requires) {
        $available = switch -Regex ($requirement) {
            '^client_player$' { $status.data.client.playerAvailable; break }
            '^server_observation$' { $status.data.server.available; break }
            '^integration:(.+)$' {
                $id = $Matches[1]
                $status.data.integrations.ContainsKey($id) -and $status.data.integrations[$id].state -eq 'available'
                break
            }
            default { throw "未知场景前置条件：$requirement" }
        }
        if (-not $available) {
            $missing += $requirement
        }
    }
    return ,$missing
}

function Save-ScenarioEvents {
    param([hashtable]$Connection, [string]$Cursor, [string]$RunDirectory)
    $request = @{ cursor = $Cursor; limit = 1000 }
    $gap = $false
    $pages = 0
    $lines = [Collections.Generic.List[string]]::new()
    do {
        $events = Invoke-McpTool $Connection 'mc_events' $request
        if ($events.status -eq 'error') {
            throw '事件证据读取失败。'
        }
        $gap = $gap -or $events.data.cursorGap
        foreach ($event in $events.data.items) {
            $lines.Add((ConvertTo-Json -Depth 100 -Compress -InputObject $event))
        }
        $request.cursor = $events.data.nextCursor
        $pages++
    } while ($events.data.truncated -and $pages -lt 32)
    [IO.File]::WriteAllLines((Join-Path $RunDirectory 'events.jsonl'), $lines, [Text.UTF8Encoding]::new($false))
    return @{ cursorGap = $gap; truncated = $events.data.truncated; nextCursor = $events.data.nextCursor }
}

function Invoke-McpScenario {
    param(
        [Parameter(Mandatory)][hashtable]$Connection,
        [Parameter(Mandatory)][hashtable]$Scenario,
        [Parameter(Mandatory)][string]$OutputDirectory,
        [string]$CancellationFile
    )
    $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $directory = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $runId
    $null = New-Item -ItemType Directory -Path (Join-Path $directory 'snapshots') -Force
    $startedAt = [DateTime]::UtcNow.ToString('o')
    $result = @{
        runId = $runId; outcome = 'runner_error'; startedAt = $startedAt
        steps = @(); assertions = @(); evidence = @{}; runDirectory = $directory
    }
    $values = @{ parameters = @{} }
    $primaryFinished = $false
    $eventCursor = $null
    try {
        if (-not $Scenario.ContainsKey('id') -or -not $Scenario.ContainsKey('steps')) {
            throw '场景必须包含 id 和 steps。'
        }
        # 运行前检查所有步骤，避免配置错误到中途才被发现。
        $ids = @{}
        $hasAssertions = $Scenario.ContainsKey('assertions') -and $Scenario.assertions.Count -gt 0
        foreach ($step in $Scenario.steps) {
            if (-not $step.ContainsKey('id') -or $step.id -notmatch '^[a-zA-Z][a-zA-Z0-9_-]*$' `
                    -or $step.id -eq 'parameters' -or $ids.ContainsKey($step.id) `
                    -or @(@('tool', 'assert', 'task') | Where-Object { $step.ContainsKey($_) }).Count -ne 1) {
                throw '每个步骤需要唯一 id，以及 tool、assert 或 task 其中一种。'
            }
            $hasAssertions = $hasAssertions -or $step.ContainsKey('assert') -or $step['tool'] -eq 'mc_assert'
            $ids[$step.id] = $true
        }
        if (-not $hasAssertions) {
            throw '场景至少需要一次断言，动作完成不能替代测试通过。'
        }
        if ($Scenario.ContainsKey('parameters')) {
            $values.parameters = $Scenario.parameters
        }
        $status = Invoke-McpTool $Connection 'mc_status' @{}
        $cursorResult = Invoke-McpTool $Connection 'mc_events' @{ type = '__runner_cursor__' }
        $eventCursor = $cursorResult.data.nextCursor
        $catalog = Invoke-McpTool $Connection 'mc_catalog' @{ kind = 'mods'; limit = 1000 }
        Write-RunJson (Join-Path $directory 'manifest.json') @{
            schemaVersion = 1; runId = $runId; context = $status.context; environment = $status.data
            scenario = $Scenario; mods = $catalog; startedAt = $startedAt
        }
        $requires = @()
        if ($Scenario.ContainsKey('requires')) {
            $requires = @($Scenario.requires)
        }
        $missing = Get-ScenarioRequirements $Connection $requires
        if ($missing.Count -gt 0) {
            $result.outcome = 'blocked'
            $result.missing = $missing
            $result.failureCategory = 'prerequisite_unavailable'
        } else {
            $result.outcome = 'passed'
            foreach ($step in $Scenario.steps) {
                if ($CancellationFile -and (Test-Path -LiteralPath $CancellationFile)) {
                    $result.outcome = 'inconclusive'
                    $result.reason = 'cancelled'
                    break
                }
                $arguments = @{}
                if ($step.ContainsKey('assert')) {
                    $name = 'mc_assert'
                    $arguments.conditions = Resolve-ScenarioValue $step.assert $values
                } elseif ($step.ContainsKey('task')) {
                    $name = 'task:' + $step.task.kind
                } else {
                    $name = $step.tool
                    if ($name -in @('job_cancel', 'job_status')) {
                        throw '场景由运行器管理作业，不接受显式作业控制步骤。'
                    }
                    if ($step.ContainsKey('arguments')) {
                        $arguments = Resolve-ScenarioValue $step.arguments $values
                    }
                }
                if ($step.ContainsKey('task')) {
                    $task = Resolve-ScenarioValue $step.task $values
                    $observation = Invoke-McpPlayerTask $Connection $task -CancellationFile $CancellationFile
                } else {
                    $observation = Invoke-McpTool $Connection $name $arguments -CancellationFile $CancellationFile
                }
                $values[$step.id] = $observation
                Write-RunJson (Join-Path $directory ('snapshots/' + $step.id + '.json')) $observation
                $outcome = Get-ScenarioOutcome $observation
                $result.steps += @{ id = $step.id; tool = $name; outcome = $outcome }
                if ($outcome -ne 'passed') {
                    $result.outcome = $outcome
                    $result.failureCategory = if ($name -like 'ct_*' -and $outcome -eq 'inconclusive') {
                        'diagnostic_incomplete'
                    } elseif ($name -like 'ct_*') { 'script_failure' } `
                        elseif ($name -eq 'mc_assert' -or $name -like 'task:*') { 'behavior_failure' } `
                        else { 'action_failure' }
                    if ($observation.ContainsKey('error')) { $result.error = $observation.error }
                    break
                }
            }
            if ($result.outcome -eq 'passed' -and $Scenario.ContainsKey('assertions')) {
                $assertions = Resolve-ScenarioValue $Scenario.assertions $values
                $observation = Invoke-McpTool $Connection 'mc_assert' @{ conditions = $assertions } `
                    -CancellationFile $CancellationFile
                Write-RunJson (Join-Path $directory 'snapshots/assertions.json') $observation
                $result.assertions = $observation
                $result.outcome = Get-ScenarioOutcome $observation
                if ($result.outcome -ne 'passed') { $result.failureCategory = 'behavior_failure' }
            }
        }
        $primaryFinished = $true
    } catch {
        $result.outcome = 'runner_error'
        $result.reason = $_.Exception.Message
        $result.failureCategory = 'runner_failure'
    }
    try {
        if ($eventCursor) {
            $result.evidence.events = Save-ScenarioEvents $Connection $eventCursor $directory
            if ($result.outcome -eq 'passed' -and ($result.evidence.events.cursorGap `
                    -or $result.evidence.events.truncated)) {
                $result.outcome = 'inconclusive'
            }
        }
        if ($result.outcome -ne 'passed') {
            $screenshot = Invoke-McpTool $Connection 'mc_screenshot' @{ keepFile = $false; timeoutMs = 5000 }
            if ($screenshot.status -eq 'completed') {
                $index = 0
                foreach ($content in $screenshot.content) {
                    if ($content.type -eq 'image') {
                        $null = New-Item -ItemType Directory -Path (Join-Path $directory 'screenshots') -Force
                        [IO.File]::WriteAllBytes((Join-Path $directory "screenshots/failure-$index.png"),
                            [Convert]::FromBase64String($content.data))
                        $index++
                    }
                }
            } else {
                $result.evidence.screenshotError = $screenshot.error.code
            }
        }
    } catch {
        $result.evidence.error = $_.Exception.Message
        if ($primaryFinished -and $result.outcome -eq 'passed') {
            $result.outcome = 'inconclusive'
        }
    }
    $result.endedAt = [DateTime]::UtcNow.ToString('o')
    Write-RunJson (Join-Path $directory 'result.json') $result
    return $result
}

Export-ModuleMember -Function Invoke-McpScenario, Write-RunJson
