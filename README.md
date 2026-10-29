# ZenScript MCP

Minecraft 1.12.2 的本地 MCP 模组。当前已实现 P0 协议基线和 P1 通用感知与发现，核心不依赖 CraftTweaker。

```text
HTTP /mcp → 两版协议适配 → 工具目录 → 缓存 / 作业 → 对应游戏线程
```

共 21 个工具：P0 状态/作业工具，以及 P1 目录、玩家、世界、实体、容器、GUI、截图、标准能力、NBT、配方、命令发现和事件读取。
P1 的 `mc_inventory`、`mc_container`、`mc_chat` 仅开放读取分支。
开发客户端基础验收已通过；跨模组能力与性能基准仍待验收，范围见 [P1 实现说明](docs/p1-development.md)。

## 构建与运行

保留 Gradle 9.7.0、RFG 2.0.3、stable_39 mappings；Java 25 运行 Gradle，Azul Java 8 编译及开发启动。

```powershell
# JAVA_HOME 指向 Java 25；wrapper 自动解析 Java 8 工具链。
.\gradlew.bat assemble        # 编译并生成重混淆 JAR
.\gradlew.bat runClient
```

产物为 `build/libs/zenscriptmcp-1.0.0.jar`。`-dev.jar`、`-sources.jar` 用途不同。
目前支持物理客户端及单人内置服务端来源，不声明独立服务端部署兼容。正式 JAR 的实机加载检查本轮按用户要求取消。

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
