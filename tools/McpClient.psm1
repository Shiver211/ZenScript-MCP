#requires -Version 7.5
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-McpConnection {
    param([Parameter(Mandatory)][string]$Endpoint, [Parameter(Mandatory)][string]$TokenFile)
    $uri = [uri]$Endpoint
    if ($uri.Scheme -ne 'http' -or $uri.Host -notin @('127.0.0.1', 'localhost', '[::1]', '::1')) {
        throw '仅支持本机 HTTP MCP 端点。'
    }
    $connection = @{
        Endpoint = $Endpoint
        Token = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TokenFile)).Trim()
        RequestId = 0
        InstanceId = $null
    }
    $null = Invoke-McpRequest $connection 'initialize' @{
        protocolVersion = '2025-11-25'
        capabilities = @{}
        clientInfo = @{ name = 'zenscriptmcp-runner'; version = '1.0' }
    }
    $null = Invoke-McpRequest $connection 'notifications/initialized' @{} -Notification
    $status = Invoke-McpTool $connection 'mc_status' @{}
    if ($status.status -ne 'completed') {
        throw '无法取得 MCP 实例状态。'
    }
    $connection.InstanceId = $status.context.instanceId
    return $connection
}

function Invoke-McpRequest {
    param([hashtable]$Connection, [string]$Method, [hashtable]$Parameters, [switch]$Notification)
    $headers = @{
        Authorization = 'Bearer ' + $Connection.Token
        Accept = 'application/json, text/event-stream'
        'MCP-Protocol-Version' = '2025-11-25'
    }
    $body = @{ jsonrpc = '2.0'; method = $Method; params = $Parameters }
    if (-not $Notification) {
        $Connection.RequestId++
        $body.id = $Connection.RequestId
    }
    try {
        $response = Invoke-WebRequest -Uri $Connection.Endpoint -Method Post -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body (ConvertTo-Json $body -Depth 100 -Compress) `
            -TimeoutSec 15 -MaximumRedirection 0
    } catch {
        # 不把请求头、启动命令或令牌带入异常与归档。
        throw 'mcp_transport_error：请求失败；请检查进程、端点和令牌文件。'
    }
    if ($Notification) {
        return
    }
    $reply = ConvertFrom-Json -AsHashtable -DateKind String $response.Content
    if ($reply.ContainsKey('error')) {
        throw ('mcp_protocol_error：{0} {1}' -f $reply.error.code, $reply.error.message)
    }
    return $reply.result
}

function Invoke-McpTool {
    param(
        [Parameter(Mandatory)][hashtable]$Connection,
        [Parameter(Mandatory)][string]$Name,
        [hashtable]$Arguments = @{},
        [int]$TimeoutSeconds = 330,
        [string]$CancellationFile
    )
    if ($CancellationFile -and (Test-Path -LiteralPath $CancellationFile)) {
        return @{
            status = 'error'; error = @{ code = 'cancelled'; message = '场景已取消，没有提交新操作。' }
        }
    }
    $reply = Invoke-McpRequest $Connection 'tools/call' @{ name = $Name; arguments = $Arguments }
    $domain = $reply.structuredContent
    Assert-McpInstance $Connection $domain
    if ($domain.status -ne 'accepted') {
        $domain.content = @($reply.content | Where-Object type -ne 'text')
        return $domain
    }
    $jobId = $domain.job.jobId
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $stopRequested = $false
    $stopTimer = $null
    while ($true) {
        $cancelled = $CancellationFile -and (Test-Path -LiteralPath $CancellationFile)
        if (-not $stopRequested -and ($cancelled -or $timer.Elapsed.TotalSeconds -ge $TimeoutSeconds)) {
            $null = Invoke-McpRequest $Connection 'tools/call' @{
                name = 'job_cancel'; arguments = @{ jobId = $jobId }
            }
            $stopRequested = $true
            $stopTimer = [Diagnostics.Stopwatch]::StartNew()
        }
        $reply = Invoke-McpRequest $Connection 'tools/call' @{
            name = 'job_status'; arguments = @{ jobId = $jobId }
        }
        $domain = $reply.structuredContent
        Assert-McpInstance $Connection $domain
        if ($domain.status -eq 'error') {
            return $domain
        }
        $job = $domain.job
        if ($job.state -in @('succeeded', 'failed', 'cancelled', 'timed_out')) {
            $result = @{
                status = 'completed'; context = $domain.context; job = $job
                content = @($reply.content | Where-Object type -ne 'text')
            }
            if ($job['result']) {
                foreach ($key in $job.result.Keys) {
                    $result[$key] = $job.result[$key]
                }
            }
            if ($job.state -ne 'succeeded') {
                $result.status = 'error'
                $result.error = $job.error
            }
            return $result
        }
        if ($stopRequested -and $stopTimer.Elapsed.TotalSeconds -ge 30) {
            return @{
                status = 'error'; context = $domain.context; job = $job
                error = @{ code = 'cleanup_pending'; message = '取消后尚未完成清理，禁止继续修改或还原世界。' }
            }
        }
        Start-Sleep -Milliseconds 100
    }
}

function Assert-McpInstance {
    param([hashtable]$Connection, [hashtable]$Domain)
    if ($Connection.InstanceId -and $Domain.context.instanceId -ne $Connection.InstanceId) {
        throw 'instance_changed：游戏已重启；旧场景与引用不能继续使用。'
    }
}

function Wait-McpReady {
    param([hashtable]$Connection, [switch]$World, [switch]$Server, [int]$TimeoutSeconds = 120)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $status = Invoke-McpTool $Connection 'mc_status' @{}
        $ready = $status.data.loadingState -eq 'ready' -and $status.data.client['available']
        if ($World) {
            $ready = $ready -and $status.data.client['worldAvailable'] -and $status.data.client['playerAvailable'] `
                -and -not $status.data.client['paused']
        }
        if ($Server) {
            $ready = $ready -and $status.data.server['available']
        }
        if ($ready) {
            return $status
        }
        Start-Sleep -Milliseconds 100
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'readiness_timeout：实例未在期限内达到所需加载、世界或玩家状态。'
}

Export-ModuleMember -Function New-McpConnection, Invoke-McpRequest, Invoke-McpTool, Wait-McpReady
