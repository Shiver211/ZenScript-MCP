# ZenScript Skill MCP

Minecraft 1.12.2 的本地 MCP 模组，让支持 MCP 的 Agent 查询游戏状态、执行玩家操作、检查 ZenScript，并通过实际游戏行为验证结果。
提供 31 个核心工具、可选 CraftTweaker / GameStages 集成，以及 Windows 本地实例与批次运行器。

```text
连接游戏 → 发现工具与内容 → 观察状态 → 执行操作 → 等待 → 断言结果
修改脚本 → CT 检查 → 重载或完整重启 → 游戏内操作 → 断言 → 修复并复测
```

- [安装与环境](#installation)
- [连接游戏](#connection)
- [工具与常用操作](#tools)
- [CraftTweaker 与 GameStages](#integrations)
- [编写和运行场景](#scenarios)
- [实例管理与批量回归](#runner)
- [配置与排错](#configuration)
- [从源码构建](#build)

<a id="installation"></a>
## 安装与环境

| 组件 | 要求或已验证版本 |
|---|---|
| 游戏 | Minecraft 1.12.2 客户端；世界操作主要在单人内置服务端验证 |
| 加载器 | Forge / Cleanroom 1.12.2 环境 |
| 游戏 Java | 沿用加载器要求；Cleanroom 实测 Java 25.0.1，Forge 客户端使用 Java 8 |
| PowerShell 运行器 | Windows、PowerShell 7.5+（`pwsh`）；直接使用 MCP 时无需 PowerShell |
| 可选 CT 集成 | CraftTweaker；推荐搭配 ZenUtils |
| 可选阶段读取 | GameStages |

1. 退出游戏，将 `zenscriptmcp-1.0.0.jar` 放入目标实例的 `mods/`。
2. 使用原有启动器或启动脚本启动该实例，保持加载器本身需要的依赖。
3. 首次启动会在**实际游戏目录**生成 `config/zenscriptmcp.json` 和 `config/zenscriptmcp.token`。
4. 要执行世界操作，需要开启离焦非暂停功能(按下 F3 + P)。脚本和批次验证建议使用专用测试存档；需要命令时启用作弊权限。

实际游戏目录是启动参数 `--gameDir` 指向的目录；启用版本隔离时，它可能是版本目录，而非上层 `.minecraft`。
主菜单可以查询实例状态和操作 GUI，但玩家、方块等查询需要已加载的世界。目前不声明独立服务端部署、完整远程服务器观测或全部模组私有机制兼容。

<a id="connection"></a>
## 连接游戏

### MCP 客户端 / Agent

在支持 HTTP MCP 的客户端中添加服务，填写以下参数。具体配置文件格式以客户端为准。

| 参数 | 默认值 |
|---|---|
| 服务地址 | `http://127.0.0.1:8765/mcp` |
| 传输 | HTTP / Streamable HTTP，工具响应使用 JSON |
| 请求头 | `Authorization: Bearer <令牌文件内容>` |
| 协议版本 | `2025-11-25` 或 `2026-07-28`；客户端需支持其中一种 |

令牌来自该实例的 `config/zenscriptmcp.token`，填写时去掉首尾空白。令牌赋予游戏操作权限，应保存在客户端本地配置中。
服务仅监听回环地址；端口可通过模组配置修改。

连接后先调用 `mc_status`，再通过 `tools/list` 获取实际可用工具与 `inputSchema`。可以让 Agent 执行：

```text
先查看 Minecraft 实例状态与可用工具；进入世界后读取玩家位置、背包和附近实体，报告各项数据来源。
```

### PowerShell 快速连接

以下命令在项目根目录的 PowerShell 7.5+ 中运行。请先手动启动游戏；此方式可连接已有实例。
PowerShell 功能需要本项目完整的 `tools/` 目录，单独的模组 JAR 不包含这些模块。

```powershell
Import-Module ./tools/McpClient.psm1

$gameDirectory = 'D:/Minecraft/instance' # 改成实际游戏目录
$endpoint = 'http://127.0.0.1:8765/mcp'
$tokenFile = Join-Path $gameDirectory 'config/zenscriptmcp.token'
$connection = New-McpConnection $endpoint $tokenFile

$status = Invoke-McpTool $connection mc_status @{}
$status.data | ConvertTo-Json -Depth 10

# 在游戏中进入存档、关闭暂停菜单后，再等待世界和内置服务端就绪。
$null = Wait-McpReady $connection -World -Server
$player = Invoke-McpTool $connection mc_player @{ source = 'server' }
$player.data | ConvertTo-Json -Depth 10

# 查看实际工具和参数。
$tools = (Invoke-McpRequest $connection 'tools/list' @{}).tools
$tools | Select-Object name, description
($tools | Where-Object name -eq 'mc_move').inputSchema | ConvertTo-Json -Depth 30
```

`New-McpConnection` 自动完成握手；`Invoke-McpTool` 自动轮询作业，并将最终结果展开到 `.data`、`.error`、`.job` 等字段。游戏重启后需重新创建连接。

### 自行实现 HTTP 调用

下面使用 `2025-11-25` 协议。向 `/mcp` 发送 POST，请求头为：

```http
Authorization: Bearer <令牌文件内容>
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-11-25
```

先发送初始化请求：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"my-client","version":"1.0"}}}
```

然后发送无 `id` 的初始化通知，再调用工具：

```json
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mc_status","arguments":{}}}
```

领域结果位于 JSON-RPC 响应的 `result.structuredContent`。返回 `status=accepted` 时，用其中的 `job.jobId` 轮询：

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"job_status","arguments":{"jobId":"返回的作业 ID"}}}
```

作业终态为 `succeeded`、`failed`、`cancelled` 或 `timed_out`。需要停止时调用 `job_cancel`，并等待 `cleanupComplete=true`。
`2026-07-28` 使用逐请求元数据，需同时匹配版本、方法、工具名请求头与 `params._meta`；具体字段见 [协议实现](src/main/java/com/shiver/zenscriptmcp/protocol/ProtocolAdapter.java)。本项目不提供 SSE 事件流、协议会话、resources、sampling 或 MCP Tasks。

<a id="tools"></a>
## 工具与常用操作

| 用途 | 核心工具 |
|---|---|
| 环境与发现 | `mc_status`、`mc_catalog`、`mc_commands` |
| 玩家与输入 | `mc_player`、`mc_look`、`mc_move`、`mc_input` |
| 世界与方块 | `mc_world_query`、`mc_block_inspect`、`mc_raycast`、`mc_block_interact` |
| 实体 | `mc_entities`、`mc_entity_inspect`、`mc_entity_interact` |
| 物品与容器 | `mc_inventory`、`mc_container`、`mc_click_slot`、`mc_item_use` |
| 界面与截图 | `mc_screen`、`mc_screenshot`、`mc_ui_action` |
| 命令与聊天 | `mc_command`、`mc_chat` |
| 模组数据 | `mc_capabilities`、`mc_nbt`、`mc_recipes` |
| 观察与作业 | `mc_events`、`mc_wait`、`mc_assert`、`job_status`、`job_cancel` |

沿用上文的 `$connection`：

```powershell
# 发现已加载模组、物品；大目录按返回的 cursor 分页。
Invoke-McpTool $connection mc_catalog @{ kind = 'mods'; limit = 100 }
Invoke-McpTool $connection mc_catalog @{ kind = 'items'; query = 'minecraft:stone' }

# 读取背包与附近实体。
Invoke-McpTool $connection mc_inventory @{ source = 'server' }
Invoke-McpTool $connection mc_entities @{ source = 'server'; radius = 8 }

# 在空旷位置运行：让玩家向前走 10 个客户端逻辑 Tick，再读取实际位置。
Invoke-McpTool $connection mc_move @{ forward = 1; durationTicks = 10 }
Invoke-McpTool $connection mc_player @{ source = 'server'; fields = @('position') }

# 按服务端实际逻辑 Tick 等待；暂停时 Tick 不会推进。
Invoke-McpTool $connection mc_wait @{ side = 'server'; ticks = 20 }
```

结果判读规则：

- `source=server` 请求内置服务端状态，不可用时会报错。`client` 是客户端观测，`auto` 按工具选择来源。
- `status=completed` 或 `job.state=succeeded` 表示调用已完成。原始 `job_status` 响应的断言结论在 `job.result.data.outcome`；PowerShell 将其展开为 `.data.outcome`。
- `observation.coverage`、`unavailable`、分页和截断字段说明数据是否完整；缺少数据不能推断为零或不存在。
- `mc_nbt` 的 typed 格式保留 NBT 类型，`long` 用十进制字符串表示，例如 `"9007199254740993"`。
- 实体、容器、界面引用应从当前查询取得。重启或切换世界后重新查询；槽位操作使用客户端的 `containerId/slotId`，GUI 操作使用最新 `screenId`。

例如，断言玩家在主世界：

```powershell
$condition = @{
    query = @{ tool = 'mc_player'; arguments = @{ source = 'server'; fields = @() } }
    path = '/data/entityRef/dimension'
    operator = 'eq'
    expected = 0
}
$assertion = Invoke-McpTool $connection mc_assert @{ conditions = @($condition) }
$assertion.data.outcome # passed / failed / inconclusive
```

条件路径使用 JSON Pointer，支持 `exists/eq/ne/gt/gte/lt/lte/contains`。`mc_assert` 采样一轮；要等待条件成立，可向 `mc_wait` 传入相同的 `conditions`。

<a id="integrations"></a>
## CraftTweaker 与 GameStages

### 检查与诊断

安装受支持版本后，先查看 `mc_status.data.integrations`。CT 工具需要单人世界、玩家和相应命令权限。
脚本仍保存在实例的 `scripts/`，由编辑器或具备文件访问能力的 Agent 修改。

```powershell
$status = Invoke-McpTool $connection mc_status @{}
$status.data.integrations | ConvertTo-Json -Depth 5

$check = Invoke-McpTool $connection ct_check @{}
if ($check.status -eq 'error') {
    throw $check.error.message
}
$check.data | ConvertTo-Json -Depth 10
Invoke-McpTool $connection ct_diagnostics @{ cursor = $check.data.diagnosticCursorStart; limit = 1000 }
```

`ct_check` 通过完整 `ct syntax` 命令检查脚本。语法错误、bracket 错误或已观察到的错误可使结果为 `failed`；无已观察错误时仍返回 `inconclusive`，因为日志过滤、启动缓冲和未触发的回调会留下观测缺口。
应结合诊断、实际触发操作和 `mc_assert` 确认行为。`ct_diagnostics` 返回原文、可确认的位置、异常与覆盖说明。

### 让脚本修改生效

| 修改类型 | 当前处理方式 |
|---|---|
| `crafttweaker` loader 中带 `#reloadable` 的事件脚本 | ZenUtils 1.27.1 下可请求 `ct_reload`，之后实际触发事件并验证 |
| 配方、ContentTweaker 注册、其他 loader 或未知修改 | 完整退出并重启游戏；`ct_reload` 返回 `restart_required` |

```powershell
Invoke-McpTool $connection ct_reload @{
    changes = @(@{ kind = 'events'; loader = 'crafttweaker' })
}
```

重载没有回滚保证；失败后应恢复适当基线并完整重启。当前检查的是整套脚本，不接收单文件路径。
CT 工具可用时总计 34 个 MCP 工具；未安装依赖或版本不支持时，相关工具不会注册。

GameStages 通过标准查询读取阶段：

```powershell
$capabilities = Invoke-McpTool $connection mc_capabilities @{
    source = 'server'
    target = @{ kind = 'player' }
}
$capabilities.data.extensions['gamestages:stages']
```

集成状态包括 `available`、`disabled`、`dependency_missing`、`unsupported_version`、`load_failed`。
标准库存、流体和 FE 能力可以跨模组读取；私有配方条件、进度等内容取决于适配器覆盖范围。

<a id="scenarios"></a>
## 编写和运行场景

场景是按顺序执行的 JSON 文件，可包含工具调用、只读断言和高层任务。每个场景至少有一次明确断言。
保存下面的内容为项目根目录下的 `scenario.json`：

```json
{
  "id": "player_dimension",
  "requires": ["client_player", "server_observation"],
  "parameters": {"dimension": 0},
  "steps": [
    {"id": "observe", "tool": "mc_player", "arguments": {"source": "server", "fields": []}}
  ],
  "assertions": [
    {
      "query": {"tool": "mc_player", "arguments": {"source": "server", "fields": []}},
      "path": "/data/entityRef/dimension",
      "operator": "eq",
      "expected": {"$ref": "parameters#/dimension"}
    }
  ]
}
```

在已启动并进入主世界的实例运行：

```powershell
Import-Module ./tools/ScenarioRunner.psm1
$scenario = Get-Content -Raw -LiteralPath ./scenario.json | ConvertFrom-Json -AsHashtable -DateKind String
$result = Invoke-McpScenario $connection $scenario './runs'
$result.outcome
$result.runDirectory
```

该调用会在当前世界执行场景并保存证据，结束后游戏保持运行。需要每轮重置世界时，使用下一节的批次运行器。

- 步骤 `id` 必须唯一，由字母开头，只含字母、数字、下划线或连字符；`parameters` 为保留名称。
- 每步使用 `tool`、`assert`、`task` 中的一种。中间断言格式为 `{"id":"verify","assert":[条件]}`。
- `$ref` 是完整对象引用，格式为 `步骤ID#JSON Pointer` 或 `parameters#JSON Pointer`，不执行表达式。
- 前置条件支持 `client_player`、`server_observation`、`integration:ct` 等已登记集成。
- 步骤未通过即停止。把 `ct_check` 放入步骤时，其 `inconclusive` 也会停止场景；通常先单独检查并处理诊断，再运行行为场景。

### 导航与跟随

在 `steps` 中加入 `task`，并保留场景的最终断言。坐标需按实际世界调整：

```json
{"id":"walk","task":{"kind":"navigate","position":{"x":10.5,"y":64,"z":10.5},"radius":0.6,"timeoutSeconds":20}}
```

跟随使用前一步查询到的实体引用，例如先调用 `mc_entities`，将步骤命名为 `target`，确认结果非空后使用：

```json
{"id":"follow","task":{"kind":"follow","target":{"$ref":"target#/data/items/0/entityRef"},"radius":2.5,"timeoutSeconds":10}}
```

这些任务用于可观察内置服务端的已加载平地：目标距离及起点各水平轴偏移不超过 16 格，半径为 0.4–4 格，时限为 1–60 秒。
导航进入半径后完成；跟随持续到时限，最后一次观测在半径内才通过。遇到障碍、无完整地面、高低差、液体或引用失效会停止并返回原因，不支持绕障或复杂开放世界寻路。

<a id="runner"></a>
## 实例管理与批量回归

### 准备实例与离线基线

运行器只管理由自己启动并登记的具体实例。已经手动启动的游戏，可以用上文的 MCP 连接方式操作；要使用运行器管理生命周期，应先正常退出游戏。

1. 准备能直接启动 Java 的现有 `.bat/.cmd`，其中 `--gameDir` 使用绝对路径。运行器不接管启动器界面，也不处理账号登录。
2. 创建专用测试世界，准备玩家位置和所需物品，正常保存退出游戏。
3. 将该世界的**整个存档文件夹**离线复制到独立基线目录；该目录直接包含 `level.dat`，不得与目标存档或运行输出目录重叠。
4. 保存以下配置为项目根目录下的 `instance.json`，按实际路径和存档列表调整。

```json
{
  "gameDirectory": "D:/Minecraft/instance",
  "launchScript": "D:/Minecraft/instance/start.bat",
  "workingDirectory": "D:/Minecraft/instance",
  "tokenFile": "D:/Minecraft/instance/config/zenscriptmcp.token",
  "endpoint": "http://127.0.0.1:8765/mcp",
  "outputDirectory": "./runs",
  "startupTimeoutSeconds": 180,
  "worldName": "MCP 专用世界",
  "baselineDirectory": "D:/Minecraft/baselines/mcp-world",
  "worldEntry": [
    {"screenClass":"net.minecraft.client.gui.GuiMainMenu","buttonId":1},
    {"screenClass":"net.minecraft.client.gui.GuiWorldSelection","x":170,"y":45},
    {"screenClass":"net.minecraft.client.gui.GuiWorldSelection","buttonId":1}
  ]
}
```

配置中的相对路径相对于 `instance.json`。`worldName` 必须是 `saves/` 下的文件夹名，可能与游戏显示名不同。
`worldEntry` 示例先打开单人游戏、点击列表条目、进入世界；坐标使用 `mc_screen` 的 GUI 缩放坐标，需要适配当前窗口、缩放和列表位置。
每步校验界面类型并重新获取引用；进入后核对真实存档名，选错世界会停止场景。

仅启动到主菜单时，可省略 `worldName`、`worldEntry`、`baselineDirectory`。自动进入世界需要前两项；离线还原和批次运行还需要基线。
每个实例使用独立的 `outputDirectory`，其中的 `instance-state.json` 用于校验进程身份。

### 生命周期命令

```powershell
./tools/instance.ps1 start   -Config ./instance.json -EnterWorld
./tools/instance.ps1 status  -Config ./instance.json
./tools/instance.ps1 stop    -Config ./instance.json
./tools/instance.ps1 restart -Config ./instance.json -EnterWorld

# restore 要求实例已退出；仅还原存档，不自动启动。
./tools/instance.ps1 restore -Config ./instance.json
```

启动会校验新 instanceId、PID、进程创建时间和游戏就绪状态。正常停止先取消修改并等待清理，再保存退出世界与 JVM。
`stop/restart -Force` 仅在正常停止失败时允许终止登记进程；不会批量结束其他 Java，也不会把强停记为干净退出。
状态可能为 `stopped`、`running`、`unresponsive` 或 `unmanaged`；后者表示同目录存在未登记游戏进程。

### 批次、续跑与取消

沿用上一节的 `scenario.json`，保存下面的内容为 `batch.json`。条目的 `file` 相对于批次文件，条目 `id` 必须唯一；`parameters` 可覆盖场景参数。

```json
{
  "stopOnFailure": true,
  "scenarios": [
    {"id":"dimension-check","file":"./scenario.json","parameters":{"dimension":0}}
  ]
}
```

```powershell
./tools/instance.ps1 run -Config ./instance.json -Batch ./batch.json -CancellationFile ./cancel.flag
```

**每个场景都会先退出实例、还原配置中的测试世界、完整重启并进入世界，再执行步骤。** 世界原有改动会移入输出目录的 `world-backups/`；脚本和模组仍使用当前实例文件，不随世界基线还原。
默认遇到非通过结果停止；`stopOnFailure=false` 允许普通失败后重置并执行下一场景，运行器或清理故障仍会停止。
批次结束正常退出游戏，保留最后一次场景的存档；需要回到基线时再执行 `restore`。

在另一个 PowerShell 窗口中创建取消文件，运行器会请求取消当前作业并等待清理：

```powershell
# 与运行命令使用同一个文件路径；相对路径按各自终端当前目录解析。
New-Item -ItemType File -Path ./cancel.flag -Force | Out-Null
```

再次运行前移除取消文件。批次运行中可用 `status` 旁观；其他生命周期命令受互斥锁保护。

```powershell
# 将目录替换为之前返回的 runDirectory。
./tools/instance.ps1 run -Config ./instance.json -Batch ./batch.json -ResumeDirectory ./runs/batch-实际编号
```

续跑跳过已通过条目，未完成或失败条目从基线重新开始。配置、批次、场景、脚本、模组或基线文件的 SHA-256 变化都会拒绝续跑；修改后应开始新批次。

### 查看结果

```text
runs/
  instance-state.json
  world-backups/<id>/             还原前的世界副本，不自动删除
  batch-<id>/
    manifest.json                输入摘要、参数与恢复策略
    result.json                  批次结论、每次尝试与退出状态
    process/attempt-<n>/         进程状态及游戏日志来源、长度和时间
    scenarios/<runId>/
      manifest.json              场景、环境与模组目录
      result.json                步骤和断言结果
      events.jsonl
      snapshots/
      screenshots/               失败时尝试保存
```

单独运行 `Invoke-McpScenario` 时，场景目录直接位于指定输出目录下。完整游戏日志保留在实例的 `logs/`；归档不复制账号启动参数或访问令牌。
世界副本会随批次累积，确认不再需要后由调用方清理。

| `outcome` | 含义 |
|---|---|
| `passed` | 所需断言通过，已检查的证据没有缺口 |
| `failed` | 断言不匹配或操作失败；结合步骤错误判断原因 |
| `blocked` | 缺少世界、来源、集成等前置条件，或当前操作受阻 |
| `inconclusive` | 证据不足、超时、取消或诊断不完整 |
| `runner_error` | 运行器、连接、配置或进程异常 |

`failureCategory` 进一步区分启动、脚本、行为、操作和进程退出等原因。CLI 正常完成退出码为 0，未通过批次或异常为 1；查询状态时仍需读取返回的 `state`。

<a id="configuration"></a>
## 配置与排错

### 模组配置

`config/zenscriptmcp.json` 属于游戏模组配置，与运行器的 `instance.json` 分开。以下是可用的最小配置，未列字段采用默认值；修改后重启游戏。

```json
{
  "enabled": true,
  "http": {
    "host": "127.0.0.1",
    "port": 8765,
    "tokenFile": "config/zenscriptmcp.token",
    "allowedOrigins": []
  },
  "integrations": {
    "crafttweaker": true,
    "gameStages": true
  }
}
```

模组的 `http.tokenFile` 相对于游戏目录。`allowedOrigins=[]` 拒绝带浏览器 Origin 的请求，不影响通常不带 Origin 的本地 MCP 客户端。
多实例同时运行时配置不同端口，并同步修改客户端地址和运行器配置。
查询数量、NBT 大小、事件容量和截图尺寸有默认预算，完整字段见 [McpConfig.java](src/main/java/com/shiver/zenscriptmcp/runtime/McpConfig.java)。

### 常见问题

| 现象 / 错误 | 检查方式 |
|---|---|
| 连接失败 / `mcp_transport_error` | 确认游戏已启动、模组已加载、端口与令牌文件属于同一实例；查看 `logs/latest.log` |
| HTTP 401 | 核对 `Authorization: Bearer ...` 和令牌内容，勿把文件路径当作令牌 |
| 协议版本或请求头不匹配 | 使用受支持协议；原始 HTTP 调用可先按上文的 2025 握手示例接入 |
| `world_unavailable` / `player_unavailable` / `source_unavailable` | 进入存档并等待就绪；显式 server 查询需要内置服务端来源 |
| `readiness_timeout` / 等待 Tick 超时 | 关闭暂停菜单，检查世界加载状态及是否因窗口失焦暂停 |
| `busy` / `cleanup_pending` | 查询当前作业，取消后等待清理，不并发提交下一条冲突修改 |
| `stale_reference` / `instance_changed` | 重新连接并查询当前实体、容器和界面引用 |
| CT 工具未出现 | 查看 `data.integrations.ct` 的开关、依赖及版本状态 |
| `restart_required` | 当前修改不在已验证重载范围，完整重启后重新查询和验证 |
| `unmanaged` / `unmanaged_instance` | 正常退出手动启动的游戏，再由运行器启动 |
| `screen_mismatch` / `world_mismatch` | 按 `mc_screen` 结果调整 `worldEntry`，核对存档文件夹名 |
| `world_in_use` | 等游戏退出后再还原，不能复制覆盖运行中的世界 |
| `resume_mismatch` | 输入已变化，使用新批次运行，保留旧结果供比较 |
| 导航返回 `path_blocked` 等原因 | 查看位置轨迹和地面条件，调整目标或使用基础控制工具 |

<a id="build"></a>
## 从源码构建

使用项目自带 Gradle wrapper；当前为 Gradle 9.7.0、RFG 2.0.3、stable_39 mappings。
Java 25 运行 Gradle，Azul Java 8 工具链负责目标代码编译及开发启动。

```powershell
# 在项目根目录执行；将 JAVA_HOME 改为本机 Java 25 路径。
$env:JAVA_HOME = 'D:/Java/jdk-25'
./gradlew.bat assemble

# 可选：启动开发客户端，其游戏目录为 run/。
./gradlew.bat runClient
```
