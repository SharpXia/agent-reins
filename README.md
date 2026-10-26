# AgentReins

**Hold the reins of your agents —— 看得见，拉得住。**

自建 AI Agent 的原生客户端（iOS + Desktop，一套 Kotlin 代码）：取代用 Telegram/飞书当 Agent 入口的方式，把 channel 通信升级为控制台。

[English summary] <!-- TODO: W4 补 3 行英文摘要 -->

![overview](docs/images/overview.gif) <!-- TODO: M3 录制：连接网关 → 对话 → 工具调用卡片 → 审批卡片 -->

## 为什么需要它

用 IM 当自家 Agent 的入口，够用，但只有文字：

- Agent 调了哪个工具、传了什么参数、耗时多少——**看不见**
- 敏感操作要批准——只能回一条消息碰运气
- 多会话、历史、网关状态——IM 里没法管理

AgentReins 直连 Agent 网关，把**每次工具调用变成可展开的卡片，每次审批变成原生确认框**。理念和我的另一个项目 [git-lens-web](https://github.com/SharpXia/git-lens-web) 一脉相承：先展示事实，再执行操作。

## 功能（v1.0 规划）

- **对话**：会话列表 + 流式回复
- **工具调用卡片**：名称 / 参数 / 结果 / 耗时，JSON 可展开
- **审批卡片**：敏感操作原生弹卡，批准 / 拒绝
- **状态面板**：网关 status / channels / models
- **双端**：iPhone 与 Desktop 出自同一套 Kotlin 代码（KMP + Compose Multiplatform）；Android 保持同源构建，v1.1 出包

## 多 Agent 接入

OpenClaw 是第一个也是最深的一个接入（网关 WebSocket 协议：sessions / approvals / status / channels）；架构上预留 `AgentClient` 适配层，v1.1 计划接入 OpenAI 兼容端点与自建任务编排引擎。

## 技术栈

Kotlin Multiplatform · Compose Multiplatform · Ktor（WebSocket / HTTP）· kotlinx.serialization · Material 3

## 快速上手

<!-- TODO: W4 release 时补 -->
1. 下载 [最新 release](../../releases)（Android APK / Desktop 包）
2. 填入你的 Agent 网关地址与 API Key
3. 开始对话，点击任意工具调用卡片查看详情

## 路线图

- [ ] v1.0 —— 对话 / 工具调用卡片 / 审批卡片 / 状态面板 / 双端（iPhone + Desktop）
- [ ] v1.1 —— Android APK 发布、自建编排引擎工作流视图、OpenAI 兼容直连
- [ ] v1.x —— 会话检索与归档、多后端配置

## 许可

Apache-2.0
