#requires -Version 7.5
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'McpClient.psm1')

function Test-TaskCorridor {
    param([hashtable]$Connection, [hashtable]$Player, [double]$DeltaX, [double]$DeltaZ)
    $position = $Player.position
    $length = [Math]::Sqrt($DeltaX * $DeltaX + $DeltaZ * $DeltaZ)
    $aheadX = $position.x + $DeltaX / $length * [Math]::Min(1.2, $length)
    $aheadZ = $position.z + $DeltaZ / $length * [Math]::Min(1.2, $length)
    $feetY = [int][Math]::Floor($position.y)
    $minimum = @{
        x = [int][Math]::Floor([Math]::Min($position.x, $aheadX) - 0.3)
        y = $feetY - 1
        z = [int][Math]::Floor([Math]::Min($position.z, $aheadZ) - 0.3)
    }
    $maximum = @{
        x = [int][Math]::Floor([Math]::Max($position.x, $aheadX) + 0.3)
        y = $feetY + 1
        z = [int][Math]::Floor([Math]::Max($position.z, $aheadZ) + 0.3)
    }
    $read = Invoke-McpTool $Connection 'mc_world_query' @{
        source = 'server'; kind = 'blocks'; dimension = $Player.entityRef.dimension
        min = $minimum; max = $maximum; includeAir = $true; fields = @('registryName', 'collision'); limit = 100
    }
    if ($read.status -eq 'error') { return 'terrain_unavailable' }
    if ($read.observation.coverage -ne 'complete' -or $read.data.truncated) { return 'terrain_unavailable' }
    foreach ($block in $read.data.items) {
        if ($block.target.position.y -eq $feetY - 1) {
            if (-not $block['collision'] -or [Math]::Abs($block.collision.max.y - $position.y) -gt 0.05) {
                return 'unsupported_ground'
            }
        } elseif ($block.registryName -ne 'minecraft:air') {
            return 'path_blocked'
        }
    }
    return $null
}

function Invoke-McpPlayerTask {
    param([hashtable]$Connection, [hashtable]$Task, [string]$CancellationFile)
    if ($Task['kind'] -notin @('navigate', 'follow')) { throw '高层任务 kind 只支持 navigate 或 follow。' }
    $following = $Task.kind -eq 'follow'
    if ($following -and -not $Task.ContainsKey('target')) { throw 'follow 需要本次实例的实体引用 target。' }
    if (-not $following -and -not $Task.ContainsKey('position')) { throw 'navigate 需要 position。' }
    $radius = if ($Task.ContainsKey('radius')) { [double]$Task.radius } elseif ($following) { 2.5 } else { 0.6 }
    $seconds = if ($Task.ContainsKey('timeoutSeconds')) { [double]$Task.timeoutSeconds } else { 20 }
    if ($radius -lt 0.4 -or $radius -gt 4 -or $seconds -lt 1 -or $seconds -gt 60) {
        throw 'radius 必须为 0.4–4，timeoutSeconds 必须为 1–60。'
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $trace = [Collections.Generic.List[object]]::new()
    $start = $null
    $epoch = $null
    $previous = $null
    $stuck = 0
    $inRange = $false
    $reason = 'timeout'
    while ($timer.Elapsed.TotalSeconds -lt $seconds) {
        if ($CancellationFile -and (Test-Path -LiteralPath $CancellationFile)) { $reason = 'cancelled'; break }
        $read = Invoke-McpTool $Connection 'mc_player' @{
            source = 'server'; fields = @('position', 'state', 'mode')
        }
        if ($read.status -eq 'error') { return $read }
        $player = $read.data
        if ($null -eq $start) {
            $start = $player.position
            $epoch = $read.context.worldEpoch
        } elseif ($read.context.worldEpoch -ne $epoch) {
            $reason = 'world_changed'; break
        }
        $target = $Task['position']
        if ($following) {
            $entity = Invoke-McpTool $Connection 'mc_entity_inspect' @{
                source = 'server'; target = $Task.target; fields = @('position', 'state')
            }
            if ($entity.status -eq 'error') { return $entity }
            if ($entity.data.entityRef.dimension -ne $player.entityRef.dimension -or -not $entity.data.state.alive) {
                $reason = 'target_unavailable'; break
            }
            $target = $entity.data.position
        }
        $dx = $target.x - $player.position.x
        $dz = $target.z - $player.position.z
        $distance = [Math]::Sqrt($dx * $dx + $dz * $dz)
        $trace.Add(@{ position = $player.position; target = $target; distance = $distance })
        if ($trace.Count -gt 100) { $trace.RemoveAt(0) }
        if ([Math]::Abs($target.y - $player.position.y) -gt 0.5) { $reason = 'unsupported_height'; break }
        if ($distance -gt 16 -or [Math]::Abs($player.position.x - $start.x) -gt 16 `
                -or [Math]::Abs($player.position.z - $start.z) -gt 16) { $reason = 'range_exceeded'; break }
        if (-not $player.state.onGround -or $player.state.inWater -or $player.mode.flying) {
            $reason = 'unsupported_movement'; break
        }
        $inRange = $distance -le $radius
        if ($inRange) {
            if (-not $following) { $reason = 'arrived'; break }
            $wait = Invoke-McpTool $Connection 'mc_wait' @{ side = 'server'; ticks = 5 } `
                -CancellationFile $CancellationFile
            if ($wait.status -eq 'error') { return $wait }
            $previous = $null
            continue
        }
        if ($previous) {
            $moved = [Math]::Abs($player.position.x - $previous.x) + [Math]::Abs($player.position.z - $previous.z)
            if ($moved -lt 0.05) { $stuck++ } else { $stuck = 0 }
            if ($stuck -ge 3) { $reason = 'stuck'; break }
        }
        $obstacle = Test-TaskCorridor $Connection $player $dx $dz
        if ($obstacle) { $reason = $obstacle; break }
        $previous = $player.position
        $yaw = -[Math]::Atan2($dx, $dz) * 180 / [Math]::PI
        # 短脉冲每次均等待输入释放；受阻后返回观察，不自动破坏、跳跃或传送。
        $move = Invoke-McpTool $Connection 'mc_move' @{
            forward = 1; durationTicks = 3; look = @{ yaw = $yaw; pitch = 0 }
        } -CancellationFile $CancellationFile
        if ($move.status -eq 'error') { return $move }
    }
    $passed = $reason -eq 'arrived' -or ($following -and $reason -eq 'timeout' -and $inRange)
    if ($passed -and $following) { $reason = 'followed' }
    $outcome = if ($passed) { 'passed' } elseif ($reason -eq 'cancelled') { 'inconclusive' } else { 'failed' }
    return @{
        status = 'completed'; context = @{ instanceId = $Connection.InstanceId; worldEpoch = $epoch }
        data = @{ outcome = $outcome; reason = $reason; source = 'server'; trace = @($trace.ToArray()) }
    }
}

Export-ModuleMember -Function Invoke-McpPlayerTask
