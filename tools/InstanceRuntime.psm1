#requires -Version 7.5
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'McpClient.psm1')
Import-Module (Join-Path $PSScriptRoot 'ScenarioRunner.psm1')

function Get-InstanceContext {
    param([Parameter(Mandatory)][string]$ConfigFile)
    $configPath = (Resolve-Path -LiteralPath $ConfigFile).Path
    $config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json -AsHashtable -DateKind String
    $base = Split-Path $configPath
    foreach ($key in @('gameDirectory', 'launchScript', 'outputDirectory', 'tokenFile')) {
        if (-not $config.ContainsKey($key)) { throw "实例配置缺少 $key。" }
        $config[$key] = [IO.Path]::GetFullPath($config[$key], $base)
    }
    if (-not $config.ContainsKey('endpoint')) { throw '实例配置缺少 endpoint。' }
    if ([IO.Path]::GetExtension($config.launchScript) -notin @('.bat', '.cmd')) {
        throw 'launchScript 必须是已有 Windows 批处理启动入口。'
    }
    if (-not (Test-Path -LiteralPath $config.launchScript -PathType Leaf)) { throw '启动脚本不存在。' }
    if ($config.ContainsKey('worldName')) {
        if (-not $config.worldName -or $config.worldName -in @('.', '..') `
                -or $config.worldName.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) {
            throw 'worldName 必须是 saves 下的单个存档文件夹名称。'
        }
    }
    if ($config.ContainsKey('baselineDirectory')) {
        $config.baselineDirectory = [IO.Path]::GetFullPath($config.baselineDirectory, $base)
    }
    if (-not $config.ContainsKey('startupTimeoutSeconds')) { $config.startupTimeoutSeconds = 180 }
    if ($config.ContainsKey('workingDirectory')) {
        $config.workingDirectory = [IO.Path]::GetFullPath($config.workingDirectory, $base)
    } else {
        $config.workingDirectory = $config.gameDirectory
    }
    if ($config.startupTimeoutSeconds -lt 1) { throw 'startupTimeoutSeconds 必须为正数。' }
    $null = New-Item -ItemType Directory -Path $config.outputDirectory -Force
    $config.configPath = $configPath
    $config.statePath = Join-Path $config.outputDirectory 'instance-state.json'
    return $config
}

function Read-InstanceState {
    param([hashtable]$Config)
    if (Test-Path -LiteralPath $Config.statePath) {
        return Get-Content -Raw -LiteralPath $Config.statePath | ConvertFrom-Json -AsHashtable -DateKind String
    }
    return @{}
}

function Get-ManagedProcess {
    param([hashtable]$Config, [hashtable]$State)
    if ($State.Count -gt 0 -and $State['gameDirectory'] -ne $Config.gameDirectory) {
        throw '状态文件属于其他实例目录；请为每个实例使用独立 outputDirectory。'
    }
    if (-not $State.ContainsKey('processId')) { return $null }
    $process = Get-Process -Id $State.processId -ErrorAction SilentlyContinue
    if (-not $process -or $process.StartTime.ToUniversalTime().Ticks `
            -ne ([DateTime]$State.startedAt).ToUniversalTime().Ticks) {
        return $null
    }
    return $process
}

function Get-GameProcesses {
    param([hashtable]$Config)
    $directory = [regex]::Escape($Config.gameDirectory)
    # 只识别明确的游戏目录，返回进程身份，不输出含登录参数的命令行。
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match "--gameDir\s+`"?$directory(?:`"|\s|$)"
    })
}

function Connect-ManagedInstance {
    param([hashtable]$Config)
    $state = Read-InstanceState $Config
    $process = Get-ManagedProcess $Config $state
    if (-not $process) { throw 'process_exited：登记的实例进程未运行。' }
    $connection = New-McpConnection $Config.endpoint $Config.tokenFile
    $status = Invoke-McpTool $connection 'mc_status' @{}
    if ($status.data['processId'] -ne [string]$process.Id `
            -or ($state['instanceId'] -and $connection.InstanceId -ne $state.instanceId)) {
        throw 'instance_mismatch：MCP 端点与登记进程不一致。'
    }
    return $connection
}

function Get-InstanceStatus {
    param([hashtable]$Config)
    $state = Read-InstanceState $Config
    $process = Get-ManagedProcess $Config $state
    if (-not $process) {
        $others = @(Get-GameProcesses $Config)
        if ($others.Count -gt 0) {
            return @{ state = 'unmanaged'; processIds = @($others | ForEach-Object ProcessId) }
        }
        return @{ state = 'stopped'; process = $state }
    }
    try {
        $connection = Connect-ManagedInstance $Config
        $status = Invoke-McpTool $connection 'mc_status' @{}
        return @{ state = 'running'; process = $state; mcp = $status }
    } catch {
        return @{ state = 'unresponsive'; process = $state; reason = $_.Exception.Message }
    }
}

function Start-ManagedInstance {
    param([hashtable]$Config)
    $oldState = Read-InstanceState $Config
    if (Get-ManagedProcess $Config $oldState) { throw '实例已启动；请先查询状态或停止。' }
    if (@(Get-GameProcesses $Config).Count -gt 0) { throw '目录已有未登记的游戏进程，拒绝重复启动。' }
    $quotedScript = '""' + $Config.launchScript + '""'
    $launcher = Start-Process -FilePath $env:ComSpec -ArgumentList '/d', '/s', '/c', $quotedScript `
        -WorkingDirectory $Config.workingDirectory -WindowStyle Hidden -PassThru
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $state = @{ gameDirectory = $Config.gameDirectory; launcherId = $launcher.Id; cleanShutdown = $false }
    Write-RunJson $Config.statePath $state
    do {
        if (-not $state.ContainsKey('processId')) {
            # 启动脚本直接启动 Java；不接管别的启动器或同名 Java。
            $candidates = @(Get-GameProcesses $Config | Where-Object ParentProcessId -eq $launcher.Id)
            if ($candidates.Count -eq 1) {
                $process = Get-Process -Id $candidates[0].ProcessId
                $state.processId = $process.Id
                $state.startedAt = $process.StartTime.ToUniversalTime().ToString('o')
                Write-RunJson $Config.statePath $state
            }
        } else {
            if (-not (Get-ManagedProcess $Config $state)) {
                throw 'startup_failed：游戏进程在 MCP 就绪前退出。'
            }
            try {
                $connection = New-McpConnection $Config.endpoint $Config.tokenFile
                $status = Invoke-McpTool $connection 'mc_status' @{}
                if ($status.data['processId'] -eq [string]$state.processId `
                        -and $status.data.loadingState -eq 'ready' `
                        -and $status.data.client['available'] `
                        -and $connection.InstanceId -ne $oldState['instanceId']) {
                    $state.instanceId = $connection.InstanceId
                    Write-RunJson $Config.statePath $state
                    return @{ state = 'ready'; process = $state }
                }
            } catch {
                # 端点尚未启动时继续轮询；达到总期限后单独报告启动失败。
            }
        }
        if ($launcher.HasExited -and -not $state.ContainsKey('processId')) {
            throw 'startup_failed：启动脚本已退出且没有启动游戏进程。'
        }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt $Config.startupTimeoutSeconds)
    throw 'startup_timeout：进程仍保留在状态文件中，可查询、停止后重试。'
}

function Invoke-InstanceButton {
    param([hashtable]$Connection, [hashtable]$Screen, [int]$ButtonId)
    $button = @($Screen.data.buttons | Where-Object { $_.id -eq $ButtonId -and $_.enabled -and $_.visible })
    if ($button.Count -ne 1) { throw 'screen_mismatch：目标按钮不可用。' }
    return Invoke-McpTool $Connection 'mc_ui_action' @{
        screenId = $Screen.data.screenId; action = 'click'; button = 0
        x = [int]($button[0].x + $button[0].width / 2); y = [int]($button[0].y + $button[0].height / 2)
    }
}

function Wait-InstanceScreen {
    param([hashtable]$Connection, [string]$ScreenClass, [int]$TimeoutSeconds = 30)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $screen = Invoke-McpTool $Connection 'mc_screen' @{ fields = @('buttons') }
        if ($screen.status -eq 'completed' -and (-not $ScreenClass -or $screen.data.class -eq $ScreenClass)) {
            return $screen
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    $actual = if ($screen.status -eq 'error') { $screen.error.code } else { $screen.data.class }
    throw "screen_mismatch：等待界面 $ScreenClass 超时，当前为 $actual。"
}

function Enter-InstanceWorld {
    param([hashtable]$Config, [hashtable]$Connection)
    if (-not $Config.ContainsKey('worldName')) { return }
    $status = Invoke-McpTool $Connection 'mc_status' @{}
    if (-not $status.data.client['worldAvailable']) {
        if (-not $Config.ContainsKey('worldEntry')) { throw '配置缺少 worldEntry，无法自动进入测试世界。' }
        foreach ($step in $Config.worldEntry) {
            $screen = Wait-InstanceScreen $Connection $step.screenClass
            if ($step.ContainsKey('buttonId')) {
                $result = Invoke-InstanceButton $Connection $screen $step.buttonId
            } else {
                $result = Invoke-McpTool $Connection 'mc_ui_action' @{
                    screenId = $screen.data.screenId; action = 'click'; x = $step.x; y = $step.y; button = 0
                }
            }
            if ($result.status -eq 'error') { throw ('world_entry_failed：' + $result.error.code) }
        }
    }
    $status = Wait-McpReady $Connection -World -Server -TimeoutSeconds $Config.startupTimeoutSeconds
    if ($status.data.server['worldName'] -ne $Config.worldName) {
        throw 'world_mismatch：进入的存档不是配置中的测试世界，停止场景。'
    }
}

function Stop-ManagedInstance {
    param([hashtable]$Config, [switch]$Force)
    $state = Read-InstanceState $Config
    $process = Get-ManagedProcess $Config $state
    if (-not $process) {
        if (@(Get-GameProcesses $Config).Count -gt 0) { throw 'unmanaged_instance：该目录有未登记的游戏进程。' }
        return @{ state = 'stopped'; cleanShutdown = $state['cleanShutdown'] }
    }
    $quitRequested = $false
    try {
        $connection = Connect-ManagedInstance $Config
        $status = Invoke-McpTool $connection 'mc_status' @{}
        if ($status.data.jobs['mutation']) {
            $jobId = $status.data.jobs.mutation.jobId
            $null = Invoke-McpTool $connection 'job_cancel' @{ jobId = $jobId }
            $timer = [Diagnostics.Stopwatch]::StartNew()
            do {
                $job = Invoke-McpTool $connection 'job_status' @{ jobId = $jobId }
                if ($job.job.cleanupComplete) { break }
                Start-Sleep -Milliseconds 100
            } while ($timer.Elapsed.TotalSeconds -lt 30)
            if (-not $job.job.cleanupComplete) { throw 'cleanup_pending：修改作业尚未清理。' }
        }
        for ($index = 0; $index -lt 8; $index++) {
            $screen = Wait-InstanceScreen $connection
            switch ($screen.data.class) {
                'hud' {
                    $null = Invoke-McpTool $connection 'mc_ui_action' @{
                        screenId = $screen.data.screenId; action = 'key'; keyCode = 1
                    }
                }
                'net.minecraft.client.gui.GuiIngameMenu' {
                    $null = Invoke-InstanceButton $connection $screen 1
                }
                'net.minecraft.client.gui.GuiMainMenu' {
                    $quitRequested = $true
                    $null = Invoke-InstanceButton $connection $screen 4
                }
                default {
                    $null = Invoke-McpTool $connection 'mc_ui_action' @{
                        screenId = $screen.data.screenId; action = 'close'
                    }
                }
            }
            if ($quitRequested) { break }
        }
    } catch {
        if (-not $quitRequested -and -not $Force) { throw }
    }
    if (-not $process.WaitForExit(30000)) {
        if (-not $Force) { throw 'stop_timeout：未强制终止；请检查实例状态。' }
        Stop-Process -Id $process.Id
        $process.WaitForExit()
        $quitRequested = $false
    }
    $state.cleanShutdown = $quitRequested
    $state.stoppedAt = [DateTime]::UtcNow.ToString('o')
    Write-RunJson $Config.statePath $state
    return @{ state = 'stopped'; cleanShutdown = $quitRequested }
}

function Restore-InstanceWorld {
    param([hashtable]$Config)
    if (@(Get-GameProcesses $Config).Count -gt 0) { throw 'world_in_use：运行中的实例不能还原存档。' }
    if (-not $Config.ContainsKey('baselineDirectory') -or -not $Config.ContainsKey('worldName')) {
        throw '还原需要 baselineDirectory 和 worldName。'
    }
    $saves = [IO.Path]::GetFullPath((Join-Path $Config.gameDirectory 'saves'))
    $target = [IO.Path]::GetFullPath((Join-Path $saves $Config.worldName))
    $backupRoot = [IO.Path]::GetFullPath((Join-Path $Config.outputDirectory 'world-backups'))
    $backup = Join-Path $backupRoot ([guid]::NewGuid().ToString('N'))
    if (-not $target.StartsWith($saves + [IO.Path]::DirectorySeparatorChar) `
            -or -not $backup.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar) `
            -or $backupRoot.StartsWith($saves, [StringComparison]::OrdinalIgnoreCase) `
            -or $Config.baselineDirectory.StartsWith($target, [StringComparison]::OrdinalIgnoreCase) `
            -or $target.StartsWith($Config.baselineDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'baseline_conflict：基线与目标必须是独立目录，且目标位于该实例 saves 内。'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Config.baselineDirectory 'level.dat'))) {
        throw 'baseline_missing：基线缺少 level.dat。'
    }
    if ($backupRoot.StartsWith($Config.baselineDirectory, [StringComparison]::OrdinalIgnoreCase) `
            -or $Config.baselineDirectory.StartsWith($backupRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'baseline_conflict：运行输出与基线必须独立。'
    }
    $null = New-Item -ItemType Directory -Path $backupRoot -Force
    $hadWorld = Test-Path -LiteralPath $target
    if ($hadWorld) { Move-Item -LiteralPath $target -Destination $backup }
    try {
        Copy-Item -LiteralPath $Config.baselineDirectory -Destination $target -Recurse
    } catch {
        if (Test-Path -LiteralPath $target) { Move-Item -LiteralPath $target -Destination ($backup + '-incomplete') }
        if ($hadWorld) { Move-Item -LiteralPath $backup -Destination $target }
        throw
    }
    return @{ state = 'restored'; worldName = $Config.worldName; previousWorldDirectory = $backup }
}

Export-ModuleMember -Function Get-InstanceContext, Get-InstanceStatus, Start-ManagedInstance,
    Stop-ManagedInstance, Restore-InstanceWorld, Connect-ManagedInstance, Enter-InstanceWorld
