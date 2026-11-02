# ZenScript Skill MCP

Minecraft 1.12.2 的本地 MCP 模组，让支持 MCP 的 Agent 查询游戏状态、执行玩家操作、检查 ZenScript，并通过实际游戏行为验证结果。
提供游戏查询与操作工具、可选 CraftTweaker / GameStages 集成，以及 Windows 本地实例与批次运行器。

```text
连接游戏 → 发现工具与内容 → 观察状态 → 执行操作 → 等待 → 断言结果
修改脚本 → CT 检查 → 重载或完整重启 → 游戏内操作 → 断言 → 修复并复测
```

- [安装与环境](#installation)
- [连接游戏](#connection)
- [工具与常用操作](#tools)
- [自动启动实例](#runner)
- [模组配置](#configuration)

<a id="installation"></a>
## 安装与环境

| 组件 | 要求或已验证版本 |
|---|---|
| 游戏 | Minecraft 1.12.2 客户端 |
| 加载器 | Forge / Cleanroom 1.12.2 环境 |
| PowerShell (可选) | Windows、PowerShell 7.5+（`pwsh`）；直接使用 MCP 时无需 PowerShell |
| CRT 集成 (可选) | CraftTweaker；推荐搭配 ZenUtils |

1. 将 `zenscriptmcp-x.x.x.jar` 放入目标实例 `mods/` 中。
2. 使用原有启动器或启动脚本启动该实例，保持加载器本身需要的依赖。
3. 首次启动会在`config/`生成 `zenscriptmcp.json` 和 `zenscriptmcp.token`。
4. 开启离焦非暂停功能 (按下 F3 + P)。需要命令时启用作弊权限。

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

令牌来自该实例的 `config/zenscriptmcp.token`。

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

`New-McpConnection` 自动完成握手；`Invoke-McpTool` 自动轮询作业。

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

<a id="tools"></a>
## 工具与常用操作

| 用途 | 核心工具 |
|---|---|
| 环境与发现 | `mc_status`、`mc_catalog`、`mc_commands` |
| 玩家与输入 | `mc_player`、`mc_look`、`mc_move`、`mc_input` |
| 世界与方块 | `mc_world_query`、`mc_block_inspect`、`mc_raycast`、`mc_block_interact` |
| 实体 | `mc_entities`、`mc_entity_inspect`、`mc_entity_interact` |
| 物品与容器 | `mc_inventory`、`mc_container`、`mc_item_inspect`、`mc_click_slot`、`mc_swap_slots`、`mc_item_use` |
| 界面与截图 | `mc_screen`、`mc_screenshot`、`mc_image`、`mc_ui_action` |
| 命令与聊天 | `mc_command`、`mc_chat` |
| 模组数据 | `mc_capabilities`、`mc_nbt`、`mc_recipes` |
| 观察与作业 | `mc_events`、`mc_wait`、`mc_wait_for_world`、`mc_assert`、`job_status`、`job_cancel` |

<a id="runner"></a>
## 自动启动实例

### 准备实例

1. 准备能直接启动的现有 `.bat/.cmd`，其中 `--gameDir` 使用绝对路径。运行器不接管启动器界面，也不处理账号登录。
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

<a id="configuration"></a>
## 模组配置

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
