# AgentReins

**Hold the reins of your agents —— 看得见，拉得住。**

自建 AI Agent 的原生移动端控制台：取代用 Telegram/飞书当 Agent 入口的方式，把 channel 通信升级为控制台。
客户端只连自建的 **AgentReins Hub**（服务端项目 AgentReinService：REST `/v1` + WSS `/v1/ws`，契约 v1.0.0），
由 Hub 统一对接各家 Agent 网关——客户端不再直连任何网关协议。

[English summary] <!-- TODO: 发布时补 3 行英文摘要 -->

## 为什么需要它

用 IM 当自家 Agent 的入口，够用，但只有文字：

- Agent 调了哪个工具、传了什么参数、耗时多少——**看不见**
- 敏感操作要批准——只能回一条消息碰运气
- 多会话、历史、网关状态——IM 里没法管理

AgentReins 把**每次工具调用变成可展开的卡片，每次审批变成原生确认框**。理念和我的另一个项目 [git-lens-web](https://github.com/SharpXia/git-lens-web) 一脉相承：先展示事实，再执行操作。

## 架构

- `shared/`（Kotlin Multiplatform）：Hub 客户端数据层——账号与设备绑定（refreshToken 落平台安全存储）、
  快照 + 事件流同步引擎（SQLDelight 投影库、游标水位裁决、410 重建）、幂等命令队列（乐观 UI、四级回执纪律）。
- `iosApp/`（SwiftUI，iOS 18+，零第三方依赖）：当前主端。经 `composeApp` 导出的 `AgentBridge` 消费 shared，
  自研轻量 Markdown 渲染器。
- `composeApp/` / `androidApp/` / `desktopApp/`：Android 与 Desktop 为占位壳（Hub 界面待基于 shared 另起实现）；
  Desktop 另带三条 Hub 链路验收探针（`hub-login` / `hub-sync` / `hub-wsraw`，凭据走环境变量）。

## 功能（当前）

- **登录与设备绑定**：用户名口令两步流，设备注册、静默恢复、设备管理（撤销 / 登出即撤销本机并清全部本地数据）
- **会话**：会话列表（未读 / 预览 / 对账中）+ 会话页（流式行、乐观发送态、自动已读）
- 规划中：工具调用卡片、审批卡片、agent 头像与定制（渲染资产已就位，待 Hub 事件面补齐）

## 技术栈

Kotlin Multiplatform · SwiftUI · Ktor（HTTP / WebSocket）· SQLDelight · kotlinx.serialization

## 快速上手

1. 部署 AgentReins Hub（服务端项目 AgentReinService 的 `deploy/`：Docker Compose + Caddy 443 单入口）
2. iOS：TestFlight 内测包；登录页填入 Hub 地址与账号
3. 开发期联调约定见 `docs/plans/hub-migration-plan.md`（仅本机）

## 许可

Apache-2.0
