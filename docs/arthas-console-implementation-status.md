# Arthas 控制台实现完成度清单

本文档基于当前代码实现，对照 `docs/arthas-console-terminal-design.md` 梳理前端到 Arthas 终端交互的完成度。

## 总体结论

真实 Arthas 命令执行链路已经完成：前端通过 WebSocket 提交命令，后端创建执行记录，调用 Arthas HTTP API 执行命令，流式返回输出，并保存命令历史和输出分片。Docker 容器目标下，后端通过 SSH + `docker exec` 在容器内访问 `127.0.0.1:{httpPort}/api`，不再要求 Fordring 后端直接连目标宿主机的 8563 端口。

尚未完成的是生产级可靠性和完整交互治理：当前前端已经使用 xterm.js 展示输出，但后端还缺单目标互斥、WebSocket 重连恢复、REST 执行链路真实执行、目标健康自动修正和更完整的风险确认。

## 已完成

| 能力 | 状态 | 代码位置 | 说明 |
| --- | --- | --- | --- |
| 控制台入口不再默认进入某个目标 | 已完成 | `frontend/src/main.tsx` | 已注册 `/console` 和 `/console/:targetId`。 |
| 左侧控制台页签先进目标选择页 | 已完成 | `frontend/src/ui/AppLayout.tsx` | 控制台导航指向 `/console`。 |
| 进入控制台时能确认目标环境 | 已完成 | `frontend/src/pages/ConsolePage.tsx` | 页面标题展示环境/名称，摘要展示主机、目标类型、容器/进程、PID、状态。 |
| 接入管理里能直接进入对应控制台 | 已完成 | `frontend/src/pages/AccessPage.tsx` | 已接入目标显示“进入 {环境或名称} 控制台”。 |
| 控制台断开按钮 | 已完成 | `frontend/src/pages/ConsolePage.tsx` | 前端调用 `/api/access-targets/{id}/detach`，成功后关闭 WebSocket 并回到 `/console`。 |
| WebSocket 命令协议 | 已完成 | `backend/src/main/java/com/fordring/websocket/ConsoleWebSocketHandler.java` | 支持 `EXECUTE_COMMAND` 和 `STOP_COMMAND`。 |
| 真实 Arthas HTTP 执行 | 已完成 | `backend/src/main/java/com/fordring/arthas/ArthasHttpCommandClient.java` | 封装 `init_session`、`async_exec`、`pull_results`、`interrupt_job`、`close_session`。Docker 目标通过 SSH + `docker exec` 调用容器内本地 API。 |
| 目标环境 Shell 执行器 | 已完成 | `backend/src/main/java/com/fordring/arthas/TargetShellExecutor.java` | 统一 SSH 登录、凭据认证、Docker `exec` 包装，供安装/接入/断开/命令执行复用。 |
| Arthas HTTP Basic Auth | 已完成 | `backend/src/main/java/com/fordring/arthas/ArthasHttpCommandClient.java` | 通过配置生成 `Authorization: Basic ...`。 |
| attach 真实执行 | 已完成 | `backend/src/main/java/com/fordring/arthas/ArthasInstallationService.java` | 通过 SSH 登录目标主机，Docker 目标进入容器后启动 `arthas-boot`。 |
| detach 真实执行 | 已完成 | `backend/src/main/java/com/fordring/arthas/ArthasInstallationService.java` | 通过 SSH 进入目标环境调用 Arthas `stop`。 |
| 目标状态维护 | 已完成 | `backend/src/main/java/com/fordring/target/AccessTargetService.java` | attach 成功后置为 `ATTACHED`，失败置为 `ATTACH_FAILED`，detach 成功置为 `DISCONNECTED`。 |
| 命令历史和输出分片 | 已完成 | `backend/src/main/java/com/fordring/command/CommandService.java` | 创建 execution、追加 output chunk、finish 更新状态。 |
| 快捷 dashboard 避免持续阻塞 | 已完成 | `frontend/src/pages/ConsolePage.tsx` | 快捷按钮发送 `dashboard -n 1`。 |
| 基础命令风险拦截 | 已完成 | `backend/src/main/java/com/fordring/command/RiskService.java` | 后端已区分 `DENY`、`CONFIRM`、`ALLOW`。 |
| 输出大小截断落库 | 已完成 | `backend/src/main/java/com/fordring/command/CommandService.java` | 超过 `fordring.command.output-max-bytes` 后标记 `outputTruncated`。 |
| xterm.js 终端组件 | 已完成 | `frontend/src/components/terminal/TerminalView.tsx` | 已用 `@xterm/xterm` 替换控制台原有 `div.terminal` 输出。 |
| 控制台会话 Hook | 已完成 | `frontend/src/components/terminal/useTerminalSession.ts` | 已将 WebSocket 连接、执行、停止和状态维护从页面中抽出。 |
| 前端大输出基础性能保护 | 已完成 | `frontend/src/components/terminal/TerminalView.tsx` | 输出直接写入 xterm 实例，复制缓冲最多保留最近 1 MB，避免完整输出驱动 React 重渲染。 |
| 设计文档 | 已完成 | `docs/arthas-console-terminal-design.md` | 已记录前端控制台、WebSocket、Arthas HTTP、attach/detach、xterm.js 演进设计。 |

## 未完成

| 能力 | 状态 | 当前表现 | 影响 |
| --- | --- | --- | --- |
| 真正终端级交互 | 未完成 | 当前是命令级交互：输入框发送完整 Arthas 命令，后端按 job 拉结果。 | 不是 PTY/字节流终端；不支持原生光标编辑、补全、逐字符输入。 |
| 单目标命令互斥 | 未完成 | 后端 `runningTasks` 以 `executionId` 为 key，不限制同一个 `targetId` 并发执行。 | 同一目标可同时跑多个持续命令，容易造成资源竞争和状态混乱。 |
| WebSocket 重连/恢复订阅 | 未完成 | 断开后只保留历史，不支持按 `executionId` 重新订阅实时输出。 | 页面刷新或网络抖动后无法继续看实时输出。 |
| WebSocket 连接建立时校验目标 | 未完成 | URL 上有 `targetId`，但后端主要使用消息体 `targetId`。 | 连接语义较弱，后续权限和目标绑定不好做。 |
| 目标健康自动修正 | 未完成 | `ATTACHED` 只看数据库状态，执行失败时才暴露端口不可达。 | 控制台列表可能显示过期状态。 |
| REST 执行接口真实执行 | 未完成 | `CommandService.executeSync` 和 `rerun` 只创建 RUNNING 记录，不调用 Arthas。 | 命令历史页面的再次执行或 REST 使用方可能产生不会结束的记录。 |
| 前端风险二次确认 | 未完成 | 控制台执行时固定传 `riskConfirmed: true`。 | 后端 `CONFIRM` 规则会被前端绕过，风险确认体验不完整。 |
| 操作者上下文 | 未完成 | WebSocket 执行命令时写死 `admin`。 | 多操作者或审计扩展时不准确。 |
| 物理机目标生产化安全通道 | 未完成 | Docker 目标已走 SSH + `docker exec`，物理机 Java 目标仍保留直接 HTTP 访问。 | 生产环境更适合统一迁移到 SSH 本机调用、SSH tunnel、Agent 反连或严格防火墙。 |
| detach 失败文案 | 未完成 | 文案仍写“无法调用 Arthas shutdown”，实际命令已改为 `stop`。 | 小问题，但会误导排障。 |
| 自动化测试覆盖 | 未完成 | 当前缺少针对控制台真实链路的单元测试和 E2E 测试。 | 后续改动容易回归。 |

## 建议优先级

1. 后端增加单目标命令互斥，避免同一目标并发执行多个持续命令。
2. 修复 REST `executeSync` 和 `rerun`，要么接入真实 Arthas 执行，要么调整接口语义，避免创建永远 `RUNNING` 的记录。
3. 前端补风险确认弹窗，不再固定传 `riskConfirmed: true`。
4. 增加目标健康检查，把数据库中陈旧的 `ATTACHED` 自动修正为不可达或失败状态。
5. 增加 WebSocket 重连和按 `executionId` 恢复订阅。
6. 将物理机目标的 Arthas HTTP 访问从直接端口改为 SSH 本机调用、SSH tunnel 或 Agent 通道。
7. 增加自动化测试：选择目标、执行 `dashboard -n 1`、停止持续 `dashboard`、断开控制台。

## 当前代码映射

| 模块 | 文件 |
| --- | --- |
| 控制台页面 | `frontend/src/pages/ConsolePage.tsx` |
| xterm 终端组件 | `frontend/src/components/terminal/TerminalView.tsx` |
| 控制台会话 Hook | `frontend/src/components/terminal/useTerminalSession.ts` |
| 控制台路由 | `frontend/src/main.tsx` |
| 左侧导航 | `frontend/src/ui/AppLayout.tsx` |
| 接入管理操作 | `frontend/src/pages/AccessPage.tsx` |
| WebSocket 命令编排 | `backend/src/main/java/com/fordring/websocket/ConsoleWebSocketHandler.java` |
| Arthas HTTP API 客户端 | `backend/src/main/java/com/fordring/arthas/ArthasHttpCommandClient.java` |
| 目标环境 Shell 执行器 | `backend/src/main/java/com/fordring/arthas/TargetShellExecutor.java` |
| Arthas 安装/接入/断开 | `backend/src/main/java/com/fordring/arthas/ArthasInstallationService.java` |
| 目标状态维护 | `backend/src/main/java/com/fordring/target/AccessTargetService.java` |
| 命令记录与输出保存 | `backend/src/main/java/com/fordring/command/CommandService.java` |
| 命令风险判断 | `backend/src/main/java/com/fordring/command/RiskService.java` |
| Arthas 配置 | `backend/src/main/resources/application.yml`、`docker-compose.yml` |
