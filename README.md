# ZenScript Skill MCP

Minecraft 1.12.2 的本地 MCP 模组。提供通用感知、操作、断言、可选脚本集成及本地实例运行器，核心不依赖 CraftTweaker。

```text
HTTP /mcp → 两版协议适配 → 工具目录 → 缓存 / 作业 → 对应游戏线程
```

共 31 个核心工具：状态与作业、目录与世界读取、玩家与 GUI 操作，以及通用断言。
`mc_inventory`、`mc_container`、`mc_chat` 支持读取与修改分支；`mc_wait` 支持 Tick 和受限只读条件。
P1、P2 开发客户端基础验收已通过；P2 已实测输入、交互、容器、GUI、合成、命令及存档进出。
第三方模组私有按键与真实容器拒绝回包仍待验收。
Cleanroom 正式 JAR 已通过移动、GUI、容器、跨模组标准能力和无 CT/MMCE 核心基础流程验证。
GUI 输入保留 Forge Pre 取消语义；容器槽位区分原版登记槽与客户端显示槽，使用唯一索引。
参数、示例和覆盖边界见 [P1 实现说明](docs/p1-development.md) 与 [P2 实现说明](docs/p2-development.md)。
可选扩展、`mc_assert` 和 PowerShell 顺序场景见 [P3 实现说明](docs/p3-development.md)。
CT 4.1.20.698 / ZenUtils 1.27.1 增加 3 个工具；诊断、重载限制和 GameStages 读取见
[P4 实现说明](docs/p4-development.md)。实例配置、批次和短距离导航/跟随见 [P5 使用说明](docs/p5-development.md)。

```powershell
# PowerShell 7.5+；先按 P5 文档准备本机实例配置和独立世界基线。
./tools/instance.ps1 start -Config ./instance.json -EnterWorld
./tools/instance.ps1 run -Config ./instance.json -Batch ./batch.json
./tools/instance.ps1 status -Config ./instance.json
```

## 构建与运行

保留 Gradle 9.7.0、RFG 2.0.3、stable_39 mappings；Java 25 运行 Gradle，Azul Java 8 编译及开发启动。

```powershell
# JAVA_HOME 指向 Java 25；wrapper 自动解析 Java 8 工具链。
# 先退出开发客户端，再构建，避免覆盖运行中加载的开发 JAR。
.\gradlew.bat assemble        # 编译并生成重混淆 JAR
.\gradlew.bat runClient
```

产物为 `build/libs/zenscriptmcp-1.0.0.jar`。`-dev.jar`、`-sources.jar` 用途不同。
目前支持物理客户端及单人内置服务端来源，不声明独立服务端部署兼容。完整跨模组验收进度见阶段文档。

## 本地连接

首次启动自动生成游戏目录下的 `config/zenscriptmcp.json` 和独立令牌文件 `config/zenscriptmcp.token`。开发启动的游戏目录是 `run/`。

```text
端点：http://127.0.0.1:8765/mcp
Authorization: Bearer <令牌文件内容>
Content-Type: application/json
Accept: application/json, text/event-stream
```

支持 `2025-11-25` 初始化握手和 `2026-07-28` 逐请求元数据；2026 版同时校验 `MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name`。不启用 SSE、协议会话、resources、sampling 或 MCP Tasks。

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"example-client","version":"1"}}}
```

初始化后发送 `notifications/initialized`，后续请求加 `MCP-Protocol-Version: 2025-11-25`：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mc_status","arguments":{}}}
```
