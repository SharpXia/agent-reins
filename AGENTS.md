# AgentReins 客户端 · 工作规约

- **定位代码先读 [../AgentReinCoordinate/docs/CODE-INDEX.md](../AgentReinCoordinate/docs/CODE-INDEX.md)**（功能→代码入口索引，含 iOS/桌面/共享层触点速查），不要扫目录树。
- 活跃开发线 = worktree `../AgentReins-hub`（分支 `feature/hub-client`）；本 worktree 在 `main`，跨线改动先确认在哪个 worktree。
- 投影库升版只改 `HubDriver.kt` 的 `HUB_DB_VERSION`（PRAGMA user_version 恒等 Schema.version，自有记账走 hubSyncState.db_version——弄反=死库）。
- 契约唯一事实 `../AgentReinService/api/hub-api-v1.yaml`；服务端改动节奏见协调仓 AGENTS.md。
- 测试会话用隔离探针（label `*-probe-<date>`），勿动真实会话。
- 本机模拟器构建须 `ARCHS=arm64`（generic destination 触发 Compose 资源任务报 x86_64）。
