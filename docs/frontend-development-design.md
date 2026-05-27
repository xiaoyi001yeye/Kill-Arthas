# Fordring 前端开发设计文档

## 1. 设计目标

本文档描述 Fordring MVP 前端实现方案，基于当前原型图与产品/开发设计文档整理，目标是指导第一版 Web 管理台开发。

前端 MVP 覆盖页面：

- 接入管理
- 控制台
- 命令历史
- 接入 Arthas 弹窗
- 新增接入弹窗
- 命令详情面板
- 再次执行命令弹窗
- 保存命令弹窗

前端设计原则：

- 以管理台效率为先，保持表格、表单、弹窗和终端区域清晰克制。
- 严格贴近原型的信息密度、布局节奏和交互路径。
- 页面组件化，业务状态集中在 hooks 和 API 层，展示组件尽量无副作用。
- MVP 单用户模式，不做登录页；默认展示操作者 `admin` 或环境变量注入的操作者名称。
- 所有真实诊断动作必须有状态反馈、错误提示和审计相关字段传递。

## 2. 技术栈

| 类别 | 选型 | 说明 |
| --- | --- | --- |
| 构建工具 | Vite | 快速启动和构建，适合 MVP |
| 框架 | React 18 + TypeScript | 组件化与类型约束 |
| 路由 | React Router | 管理页面路由 |
| 服务端状态 | TanStack Query | 接口请求、缓存、刷新、分页列表 |
| 本地 UI 状态 | Zustand | 当前目标、控制台会话、弹窗状态等轻量状态 |
| HTTP 客户端 | Axios | 统一 baseURL、错误拦截、请求 ID |
| WebSocket | 原生 WebSocket 封装 | 控制台命令流、停止命令、重连预留 |
| UI 组件库 | Ant Design | 与原型风格接近，表格/表单/弹窗成熟 |
| 图标 | lucide-react + Ant Design Icons | lucide 用于侧边栏和工具按钮，Ant 图标用于组件库语义图标 |
| 终端展示 | xterm.js | 控制台输出、复制、滚动、等宽字体 |
| 时间处理 | dayjs | 日期格式化、筛选区日期范围 |
| 工具函数 | clsx | 条件 className |
| 测试 | Vitest + React Testing Library | hooks、组件和工具函数测试 |
| E2E | Playwright | 关键流程浏览器测试 |
| 代码规范 | ESLint + Prettier | 基础质量与格式统一 |

推荐包：

```json
{
  "dependencies": {
    "@ant-design/icons": "^5",
    "@tanstack/react-query": "^5",
    "@xterm/xterm": "^5",
    "antd": "^5",
    "axios": "^1",
    "clsx": "^2",
    "dayjs": "^1",
    "lucide-react": "^0",
    "react": "^18",
    "react-dom": "^18",
    "react-router-dom": "^6",
    "zustand": "^5"
  },
  "devDependencies": {
    "@playwright/test": "^1",
    "@testing-library/jest-dom": "^6",
    "@testing-library/react": "^16",
    "@testing-library/user-event": "^14",
    "@vitejs/plugin-react": "^4",
    "eslint": "^9",
    "prettier": "^3",
    "typescript": "^5",
    "vite": "^6",
    "vitest": "^2"
  }
}
```

具体版本以项目初始化时最新兼容版本为准；主版本升级需单独验证。

## 3. 信息架构与路由

| 路由 | 页面 | 原型图 |
| --- | --- | --- |
| `/access` | 接入管理 | `arthas1.png`、`arthas2.png`、`arthas3.png` |
| `/console/:targetId` | 控制台 | `arthas4.png` |
| `/commands` | 命令历史 | `arthas5.png`、`arthas6.png`、`arthas7.png`、`arthas8.png` |
| `/commands/:executionId` | 命令历史 + 详情态 | `arthas6.png` |

默认路由重定向到 `/access`。

页面布局：

- 左侧固定侧边栏：产品名、主导航、当前用户。
- 右侧主内容区：页面标题、说明、数据卡片、表格、终端或详情面板。
- 弹窗统一由页面级容器管理，避免跨页面隐式打开。

## 4. 目录结构

```text
frontend/
  src/
    app/
      App.tsx
      router.tsx
      providers.tsx
    assets/
    components/
      common/
      layout/
      status/
      terminal/
    features/
      access/
        api.ts
        types.ts
        hooks.ts
        pages/
        components/
      console/
        api.ts
        types.ts
        hooks.ts
        pages/
        components/
      commands/
        api.ts
        types.ts
        hooks.ts
        pages/
        components/
      saved-commands/
        api.ts
        types.ts
        hooks.ts
    lib/
      api-client.ts
      query-client.ts
      websocket.ts
      date.ts
      errors.ts
    stores/
      use-ui-store.ts
      use-console-store.ts
    styles/
      tokens.css
      global.css
    test/
      setup.ts
    main.tsx
```

模块规则：

- `features/*/api.ts` 只负责调用后端接口。
- `features/*/hooks.ts` 封装 TanStack Query 和业务动作。
- `features/*/types.ts` 放 DTO 与前端视图类型。
- `components/common` 放跨业务复用组件。
- 页面文件只组合组件和管理页面级弹窗状态。

## 5. 设计系统

### 5.1 视觉基调

原型整体是白底、浅灰边框、蓝色主操作、绿色成功、橙色待处理/风险、红色失败的企业管理台风格。正式 Fordring 前端应保留这种轻量、清晰、适合排障场景的风格。

基础设计 token：

```css
:root {
  --color-bg: #ffffff;
  --color-bg-subtle: #f7f9fc;
  --color-surface: #ffffff;
  --color-border: #e5eaf2;
  --color-text: #101828;
  --color-text-secondary: #667085;
  --color-primary: #155eef;
  --color-primary-hover: #004eeb;
  --color-success: #16a34a;
  --color-warning: #f97316;
  --color-danger: #ef4444;
  --radius-card: 8px;
  --radius-control: 6px;
  --sidebar-width: 232px;
  --content-max-width: 1180px;
}
```

### 5.2 字体与密度

- 字体：系统字体栈，优先 `Inter`、`-apple-system`、`BlinkMacSystemFont`、`Segoe UI`。
- 页面标题：24px / 32px，600。
- 分区标题：16px / 24px，600。
- 表格正文：14px / 22px。
- 表单标签：14px / 22px。
- 终端字体：`JetBrains Mono`、`SFMono-Regular`、`Consolas`、monospace。

### 5.3 组件形态

- 卡片圆角不超过 8px。
- 表格行高约 64px，命令历史可适当压缩到 56px。
- 主按钮使用蓝色实心。
- 链接操作使用蓝色文字按钮。
- 危险操作使用红色文字按钮或二次确认。
- 状态使用 Tag，不用纯文本裸露状态。

## 6. 核心组件拆分

### 6.1 全局布局

| 组件 | 职责 |
| --- | --- |
| `AppLayout` | 侧边栏 + 内容区整体布局 |
| `SidebarNav` | 产品名、导航项、激活状态 |
| `UserFooter` | 底部用户信息，MVP 固定 `admin` |
| `PageHeader` | 页面标题与描述 |
| `PageShell` | 页面内边距、最大宽度和响应式约束 |

导航项：

- 接入管理：`/access`
- 控制台：只有选中目标后进入 `/console/:targetId`
- 命令历史：`/commands`

### 6.2 通用组件

| 组件 | 职责 |
| --- | --- |
| `StatusTag` | 渲染目标状态、命令状态 |
| `MetricCard` | 接入统计卡片 |
| `SearchInput` | 统一搜索输入 |
| `SegmentedFilter` | 类型分段筛选 |
| `ConfirmActionModal` | 高风险命令、断开等确认 |
| `ErrorReasonModal` | 查看检查失败或接入失败原因 |
| `ActionLinkGroup` | 表格行内操作 |
| `EmptyState` | 空列表、无目标、无历史 |
| `CopyButton` | 复制命令或输出 |

### 6.3 接入管理组件

| 组件 | 职责 |
| --- | --- |
| `AccessPage` | 接入管理页面容器 |
| `AccessStats` | 总接入数、已接入、待处理统计 |
| `AccessToolbar` | 搜索、目标类型筛选、新增接入 |
| `AccessTargetTable` | 接入目标列表 |
| `AddAccessModal` | 新增接入表单 |
| `AttachArthasModal` | 接入 Arthas 确认与端口配置 |
| `DiscoveryContainerSelect` | Docker 容器实时发现下拉 |
| `DiscoveryProcessSelect` | Java 进程实时发现下拉 |
| `ArthasInstallActions` | 行内检查 Arthas 安装与安装确认操作 |

新增接入表单字段：

- 接入名称
- 环境标签
- 主机地址
- SSH 端口，默认 22
- 认证方式
- 用户名
- 凭据输入
- 目标类型
- 容器名称
- Java 进程 PID
- Telnet 端口
- HTTP 端口

交互规则：

- 选择 Docker 容器目标时展示容器发现控件。
- 选择物理机 Java 时直接展示 Java 进程发现控件。
- 发现失败时允许用户手动填写容器名称和 Java 进程 PID。
- 「获取 PID」按钮调用 Java 进程发现接口，携带 SSH 端口和凭据，成功后把第一个候选进程的 `processId` 自动填入 PID 输入框。
- 保存前必须校验必填字段。

### 6.4 控制台组件

| 组件 | 职责 |
| --- | --- |
| `ConsolePage` | 控制台页面容器 |
| `TargetSummaryBar` | 展示接入目标摘要 |
| `QuickCommandBar` | 快捷命令按钮 |
| `TerminalPanel` | 终端输出展示 |
| `CommandInputBar` | 命令输入、执行、停止 |
| `CommandRiskConfirmModal` | 高风险命令二次确认 |

控制台状态：

- 当前目标
- WebSocket 连接状态
- 当前执行 ID
- 当前命令状态
- 是否存在运行中命令
- 输出是否已截断
- 最后错误信息

终端能力：

- 追加输出分片
- 自动滚动到底部
- 清空当前终端显示
- 复制全部已显示输出
- 命令运行时展示停止按钮
- 输出截断时展示提示

### 6.5 命令历史组件

| 组件 | 职责 |
| --- | --- |
| `CommandHistoryPage` | 命令历史页面容器 |
| `CommandHistoryFilters` | 搜索、目标、状态、日期筛选 |
| `CommandHistoryTable` | 历史列表 |
| `CommandDetailDrawer` | 命令详情与输出分片 |
| `RerunCommandModal` | 再次执行命令 |
| `SaveCommandModal` | 保存命令 |
| `CommandOutputViewer` | 分片输出查看 |

命令详情展示：

- 命令
- 目标快照
- 执行时间
- 状态
- 耗时
- 来源
- 执行人
- 风险等级
- 是否二次确认
- 输出内容

## 7. 状态管理

### 7.1 TanStack Query

用于服务端状态：

| Query Key | 用途 |
| --- | --- |
| `['access-target-stats']` | 接入统计 |
| `['access-targets', filters]` | 接入目标列表 |
| `['access-target', id]` | 接入目标详情 |
| `['command-executions', filters]` | 命令历史 |
| `['command-execution', id]` | 命令详情 |
| `['command-output', executionId, fromSequence]` | 命令输出分片 |
| `['saved-commands', filters]` | 保存命令列表 |

Mutation：

- 新增/更新/删除接入目标
- 发现容器
- 发现 Java 进程
- 接入前检查
- 接入 Arthas
- 断开 Arthas
- 风险检查
- 再次执行
- 保存命令

### 7.2 Zustand

用于短生命周期 UI 状态：

```ts
type ConsoleState = {
  targetId?: number;
  executionId?: number;
  connectionStatus: 'idle' | 'connecting' | 'connected' | 'disconnected';
  commandStatus: 'idle' | 'running' | 'stopping' | 'finished' | 'failed';
  outputTruncated: boolean;
};
```

页面弹窗状态建议优先放在页面组件内部，只有跨组件共享时再放入 store。

## 8. API 层设计

### 8.1 HTTP 客户端

`lib/api-client.ts` 负责：

- 注入 `baseURL`
- 注入 `X-Fordring-Operator`
- 统一处理 `success: false`
- 统一转化错误码与错误消息
- 暴露 typed request 方法

```ts
export type ApiResponse<T> =
  | { success: true; data: T; requestId: string }
  | { success: false; error: ApiError; requestId: string };
```

### 8.2 WebSocket 客户端

`lib/websocket.ts` 负责封装控制台连接：

- 建立 `/ws/console?targetId={targetId}`
- 发送 `EXECUTE_COMMAND`
- 发送 `STOP_COMMAND`
- 接收 `COMMAND_STARTED`
- 接收 `COMMAND_CONFIRM_REQUIRED`
- 接收 `COMMAND_OUTPUT`
- 接收 `COMMAND_STOPPING`
- 接收 `COMMAND_STOPPED`
- 接收 `COMMAND_FINISHED`
- 接收 `COMMAND_FAILED`

MVP 重连策略：

- 控制台页面打开时连接。
- 页面离开时关闭。
- 连接断开后展示断开状态，不自动重放命令。
- 用户可刷新页面或重新进入控制台。

## 9. 交互流程

### 9.1 新增接入

1. 用户点击「新增接入」。
2. 打开 `AddAccessModal`。
3. 用户填写主机、认证方式、用户名和凭据。
4. 用户选择目标类型。
5. 前端调用发现接口加载容器或 Java 进程。
6. 用户选择进程或手动填写。
7. 用户点击「保存并检测」。
8. 前端创建接入目标，再触发检查接口。
9. 成功后刷新接入列表和统计卡片。

### 9.2 接入 Arthas

1. 用户点击「接入 Arthas」。
2. 打开 `AttachArthasModal`。
3. 展示目标摘要与接入前检查结果。
4. 用户确认端口。
5. 调用接入接口。
6. 成功后刷新目标状态。
7. 失败时展示失败原因。

### 9.3 执行命令

1. 用户进入控制台。
2. 前端建立 WebSocket。
3. 用户点击快捷命令或输入命令。
4. 前端调用风险检查。
5. 如果需要确认，展示二次确认弹窗。
6. 发送 `EXECUTE_COMMAND`。
7. 终端展示流式输出。
8. 运行中可点击停止，发送 `STOP_COMMAND`。
9. 完成后刷新命令历史缓存。

### 9.4 查看历史与再次执行

1. 用户进入命令历史。
2. 前端加载历史列表。
3. 用户点击详情，打开详情面板并加载输出分片。
4. 用户点击再次执行。
5. 打开 `RerunCommandModal`。
6. 用户确认目标、命令和超时时间。
7. 执行成功后跳转或提示进入控制台查看输出。

## 10. 表单与校验

推荐使用 Ant Design Form。

基础校验：

- 接入名称：必填，最长 128。
- 主机地址：必填，支持 IP 或 hostname。
- SSH 端口：必填，默认 22，范围 1-65535。
- 认证方式：必填。
- 用户名：必填。
- 凭据：新增时必填，编辑时可不回显。
- 目标类型：必填。
- Docker 容器目标：容器名称必填。
- Java 进程：PID 必填，进程名称不作为表单字段。
- Telnet/HTTP 端口：1-65535，且不可相同。
- 命令内容：必填，最长按后端配置。
- 保存命令名称：必填，最长 128。

## 11. 状态与错误展示

目标状态：

| 状态 | 展示 |
| --- | --- |
| `NOT_ATTACHED` | 灰色 Tag：未接入 |
| `ATTACHED` | 绿色 Tag：已接入 |
| `CHECK_FAILED` | 橙色 Tag：检查失败 |
| `ATTACH_FAILED` | 红色 Tag：接入失败 |
| `DISCONNECTED` | 灰色 Tag：已断开 |

命令状态：

| 状态 | 展示 |
| --- | --- |
| `PENDING` | 灰色 Tag：等待中 |
| `RUNNING` | 蓝色 Tag：运行中 |
| `SUCCESS` | 绿色 Tag：成功 |
| `FAILED` | 红色 Tag：失败 |
| `TIMEOUT` | 橙色 Tag：超时 |
| `STOPPING` | 橙色 Tag：停止中 |
| `STOPPED` | 灰色 Tag：已停止 |
| `CANCELLED` | 灰色 Tag：已取消 |

错误处理：

- 表单错误展示在字段下方。
- 接口错误使用 message 或 notification。
- 接入失败和检查失败支持「查看原因」。
- WebSocket 断开在控制台顶部展示连接状态。
- 命令输出截断时在终端顶部展示提示。

## 12. 响应式设计

MVP 优先支持桌面端，目标分辨率：

- 1440px 宽桌面：主设计尺寸。
- 1280px 宽笔记本：必须可用。
- 1024px 宽平板横屏：表格可横向滚动。

移动端策略：

- MVP 不做完整移动端体验。
- 小屏下侧边栏可折叠。
- 表格允许横向滚动。
- 弹窗最大宽度使用 `calc(100vw - 32px)`。

## 13. 可访问性与可用性

- 所有按钮有明确文本或 tooltip。
- 危险操作必须有确认。
- 表单控件必须有 label。
- 终端区域支持键盘复制和文本选择。
- loading、empty、error 状态不能空白。
- 颜色不作为唯一状态表达，状态 Tag 必须有文字。

## 14. 测试策略

单元测试：

- API client 错误处理。
- 状态 Tag 映射。
- WebSocket 消息 reducer。
- 命令风险确认交互。

组件测试：

- 新增接入弹窗字段联动。
- 接入目标表格操作入口。
- 控制台命令输入与停止按钮。
- 命令历史筛选和详情面板。

E2E 测试：

- 新增接入目标。
- 接入 Arthas。
- 进入控制台执行命令。
- 停止持续输出命令。
- 查看命令历史。
- 再次执行命令。
- 保存命令并在控制台快捷显示。

## 15. 开发里程碑

### 15.1 第一阶段：静态可交互原型

- 初始化 Vite + React + TypeScript。
- 搭建布局、路由、设计 token。
- 使用 mock 数据完成接入管理、控制台、命令历史页面。
- 完成所有弹窗和详情态。

### 15.2 第二阶段：接入 REST API

- 接入目标 CRUD。
- 容器和 Java 进程实时发现。
- 接入前检查、接入、断开。
- 命令历史和保存命令。

### 15.3 第三阶段：接入 WebSocket

- 控制台 WebSocket 连接。
- 流式输出。
- 停止命令。
- 风险确认。
- 输出截断提示。

### 15.4 第四阶段：质量收口

- 补齐测试。
- 桌面端和窄屏检查。
- 错误态、空态、加载态检查。
- 与原型图逐页对照，修正布局和视觉细节。

## 16. 待确认事项

- UI 组件库最终是否确定为 Ant Design。
- 命令详情使用右侧抽屉、分栏详情，还是独立详情页。
- 控制台终端使用 xterm.js 还是自定义只读终端。
- 是否需要将保存命令单独做成独立管理页面。
- MVP 是否需要深色终端主题之外的全局暗色模式。
