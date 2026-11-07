# AgentReins 客户端 · 工作规约

- **定位代码先读 [../AgentReinCoordinate/docs/CODE-INDEX.md](../AgentReinCoordinate/docs/CODE-INDEX.md)**（功能→代码入口索引，含 iOS/桌面/共享层触点速查），不要扫目录树。
- 本仓唯一主线 = `main`（本目录即开发与发包工作区）；feature/hub-client 已于 2026-11-07 并入 main（merge `250cfab`，树与 b8v2 基线 c062b43 全等）且 worktree 已移除，勿再检出旧分支。
- 投影库升版只改 `HubDriver.kt` 的 `HUB_DB_VERSION`（PRAGMA user_version 恒等 Schema.version，自有记账走 hubSyncState.db_version——弄反=死库）。
- 契约唯一事实 `../AgentReinService/api/hub-api-v1.yaml`；服务端改动节奏见协调仓 AGENTS.md。
- 测试会话用隔离探针（label `*-probe-<date>`），勿动真实会话。
- 本机模拟器构建须 `ARCHS=arm64`（generic destination 触发 Compose 资源任务报 x86_64）。
