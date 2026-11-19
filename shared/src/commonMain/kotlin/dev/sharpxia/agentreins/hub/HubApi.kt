package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationOk
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.DevicesPage
import dev.sharpxia.agentreins.hub.auth.LoginOk
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileRequest
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CommandsList
import dev.sharpxia.agentreins.hub.projection.ModelsList
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse
import dev.sharpxia.agentreins.hub.projection.MediaUpload
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SessionsResponse
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest

/**
 * Hub REST 客户端（契约 v1.5.0：31 端点 + WSS，冻结 tag hub-api-v1.5.0；v1.4.0 29 端点 +
 * 多主会话 #28/#29；v1.3.0 27 端点 + 网关供给面 #25–#27；v1.2.0 24 端点 + 多模态新增
 * #23/#24；v1.1 20 端点 + #16–#21）。
 *
 * 契约钉版（P0 验收②，docs/plans/hub-migration-plan.md §6-P0；v1.1 迁移见 §8 变更清单）：
 * - REST 契约：`api/hub-api-v1.yaml` — OpenAPI 3.0.3，info.version = **v1.5.0**，`x-hub-api-version: 1`，
 *   冻结 git tag `hub-api-v1.5.0`（AgentReinService 仓；tag 名 ≠ yaml version，AGENTS.md 纪律）
 * - WSS 帧契约：`api/hub-wss-frames-v1.schema.json` — JSON Schema 2020-12，protocolVersion 支持集 = {1}
 * - 版本策略（08 §6.1）：/v1 内仅 additive（新增端点、可选请求字段、响应字段、错误码）；
 *   破坏性变更升 /v2 并行 ≥90 天（v1.1 为前端未开工期的破坏性修订特例，不经 /v2）。客户端义务
 *   = 忽略未知字段、宽松解析、不因新增字段失败
 * - 通用（08 §1.1）：每响应带 `x-hub-api-version: 1` 与 `x-request-id`（排障必须引用）；
 *   错误形状见 [HubErrorPayload]；命令类端点共用 202 + CommandReceipt（ADR-07：进度只经事件流）
 * - 端点 #21（v1.1 additive，2026-11-07）：GET /v1/conversations/{conversationId}/commands
 *   （动态斜杠命令目录）——additive 新增端点，不升版、不重钉 tag；调用方按 08 §1.1 忽略未知字段
 * - 端点 #25/#26/#27（v1.3.0 additive，多网关轮 2026-11-11，设计稿
 *   docs/multi-gateway-design-20261110.md §2.1）：网关供给面 onboarding——POST /v1/gateways
 *   （注册，一次性明文 bridgeToken）/ POST /v1/gateways/{gatewayId}/bridge-token（轮换，24h 宽限
 *   新旧双活）/ DELETE /v1/gateways/{gatewayId}（撤销终态，204 幂等）。全部 bearerDevice（与
 *   GET /v1/gateways 同链）；客户端义务：bridgeToken 不持久化（仅会话内存，复制给设备 env
 *   用完即弃）；错误码零新增（复用 HUB-BIZ-001/002 与既有认证族）
 * - v1.4.0 additive（多主会话 + 会话删除 + 管理/供给读面，2026-11-16）：#16 请求体增可选
 *   newMainSession（true = 在该 agent 名下新开主线，Hub 以请求幂等键 UUID 确定性派生
 *   sessionKey=agent:<agentId>:main-<idempotencyKey>，重试同键=同键形=同会话；与 mainSession
 *   互斥，同真 400 HUB-BIZ-004）；新端点 #28 GET /v1/gateways/{gatewayId}/agents/{agentId}/sessions
 *   （按 agent 列会话，管理面清单 SessionsResponse，不收敛不筛族）与 #29 DELETE
 *   /v1/conversations/{conversationId}（Hub 主权 tombstone，204 幂等终态，历史只读保留）；
 *   GatewaySummary 增可选 provider（桥自报供给方标识，未知省略）。客户端义务同族：忽略未知
 *   字段、provider 不做枚举假设
 * - v1.5.0 additive（个性化形象轮，2026-11-20）：新端点 #30 GET /v1/me/avatar-profile
 *   （纯投影读无副作用，资源恒存在——未设置过返回默认档 + epoch 哨兵，无 404 语义；s.bearer
 *   非 bearerDevice——形象是用户级资源非设备绑定）与 #31 PUT /v1/me/avatar-profile（幂等
 *   覆盖写：形状校验越界 400 HUB-BIZ-004 不落库；**200 回服务端权威档**——updatedAt 直接
 *   回填，客户端无二程 GET；同值重 PUT 返回同 body 不更新 updatedAt 不重复广播）；WSS 控制帧
 *   avatar.updated（非事件域：不进事件类型闭集、不入 eventlog、不占 seq、无投影、零推送触发）。
 *   形象档形状见 [AvatarProfile]；客户端义务：忽略未知字段、未知枚举/越界宽松回落（显示层）。
 *   本接口 getAvatarProfile/putAvatarProfile 给默认实现抛 UnsupportedOperationException
 *   （desktopTest 存量 fake 不在本批改动清单，生产实现 [DefaultHubApi] 必须覆盖——#28/#29 同款纪律）
 */
interface HubApi {
    // ── 认证（#1 / #2，公开端点） ─────────────────────────────────────────
    /** 登录两步流第①步：创建会话级 accessToken（2h，未绑定设备、无 refreshToken）。 */
    suspend fun login(req: LoginRequest): LoginOk

    /** Token 刷新：refreshToken 一次一用轮换，旧值立即失效；轮换后 60s 内重放幂等。 */
    suspend fun refresh(req: RefreshRequest): RefreshOk

    // ── 设备（#3 / #4 / #5，Bearer） ─────────────────────────────────────
    /** 两步流第②步：注册设备并签发该设备 refreshToken（30 天滑动）；会话 Token 随即升级为绑定 Token（ADR-15）。 */
    suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk

    /** 设备清单；current 标记发起请求的设备。 */
    suspend fun listDevices(): DevicesPage

    /** 撤销设备（可撤任意自有设备含当前）：该设备全部 Token 即时失效，其 WSS 被服务端关闭（204 无体）。 */
    suspend fun deleteDevice(deviceId: String)

    /**
     * 设备推送 token 上报（#20，v1.1 additive wp-17；204 无响应体，与 deleteDevice 同款 no-body）：
     * 幂等覆盖写，token 空串 = 清除/退订。仅绑定设备可调用（撤销设备 403 HUB-AUTH-004）。
     */
    suspend fun registerApnsToken(req: ApnsTokenRequest)

    // ── 网关（#6，08 §4.1【MVP 必需】） ──────────────────────────────────
    /** 网关与 Agent 列表；无分页。冷启动优先用快照自带 gateways 数组，此端点用于校准/刷新。 */
    suspend fun gateways(): GatewaysPage

    // ── 网关供给面（#25 / #26 / #27，v1.3.0 additive 多网关轮；全部 bearerDevice 与 #6 同链） ──

    /**
     * 注册网关（#25，v1.3.0 additive）：为当前用户注册一台新 Gateway，201 返回一次性明文
     * bridgeToken（仅本响应下发一次，服务端只存 SHA-256 摘要、无法再次查看）。客户端义务：
     * **不持久化 bridgeToken**——管理面展示后仅存会话内存，复制给 Bridge 设备 env 用完即弃
     * （契约 #25 description；08 §2.4 设备 refreshToken 注册同款纪律）。
     * 名称不判重、无幂等键：重复提交产生多个网关各持独立 Token；重试安全由调用方按钮防重
     * （提交中禁用）保证，服务端不提供服务端幂等。400 = name 空/超长（HUB-BIZ-001）。
     */
    suspend fun createGateway(req: CreateGatewayRequest): GatewayCreatedResponse

    /**
     * 轮换网关 bridgeToken（#26，v1.3.0 additive）：为指定 Gateway 重新签发，200 返回新明文
     * （一次性，持久化纪律同 #25）；旧 Token 进 24h 宽限期（宽限期内新旧双活）。并发轮换
     * last-write-wins（服务端直 UPDATE 无版本锁，以最后一次签发为准）；目标网关已撤销
     * （status='revoked'）时 401 HUB-AUTH-005 语义拒绝；「未知/非属主」404 HUB-BIZ-002 防枚举。
     */
    suspend fun rotateBridgeToken(gatewayId: String): BridgeTokenIssuedResponse

    /**
     * 撤销网关（#27，v1.3.0 additive）：置为撤销终态——gateways.status='revoked'、Hub 主动
     * 收口该网关全部活跃 Bridge 连接、目录与 AgentStatus 即时消失（读面按 status='active'
     * 过滤）、历史 sessions/messages/agents 不级联删除保留只读。204 无响应体（与 deleteDevice
     * 同款 no-body）。撤销是终态且对属主幂等（对已撤销网关再次 DELETE 直接 204）；
     * 「非属主/不存在」404 HUB-BIZ-002 防枚举——幂等语义只对属主生效。
     */
    suspend fun deleteGateway(gatewayId: String)

    // ── 会话与消息（#7 / #8 / #9 / #10，Bearer） ─────────────────────────
    /** 会话列表：不透明 keyset 分页（最近活跃降序），limit 默认 50 / 上限 200；cursor 原样回传、禁止自构。 */
    suspend fun conversations(limit: Int? = null, cursor: String? = null): ConversationsPage

    /** 历史消息：无 before 返回尾页（默认 50 / 上限 200），before=seq 向上翻页；翻页只 upsert、不推进 cursor。 */
    suspend fun messages(conversationId: String, before: Long? = null, limit: Int? = null): MessagesPage

    /** 发送消息命令 message.send（202 CommandReceipt）；同键同内容重放返回原 commandId，同键不同内容 409 HUB-BIZ-001。 */
    suspend fun sendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt

    /**
     * #23（v1.2.0 additive，多模态 v1）：媒体上传——multipart/form-data（file 必填 +
     * fileName/width/height 可选表单字段），201 MediaUploadResponse。硬约束：附件不经 WS
     * 帧传输与存储（internal/artifacts/api.go 硬约束段）——图片字节只走本 REST 端点，命令
     * 载荷只带 mediaId+元数据。服务端以魔数字节判定类型（白名单 jpeg/png/webp/gif，不信任
     * 声明 Content-Type）、单文件 ≤20MB（流式读超限即断）；返回 mimeType 为服务端嗅探值
     * （非请求声明值）。限流：上传类每设备 20 次/分钟（429 HUB-BIZ-003）。
     * conversationId 仅作存在性归属校验，不产生绑定；404 = 会话不存在/无权（HUB-BIZ-002
     * 防枚举），400 = 形状/类型/大小（HUB-BIZ-004）。
     */
    suspend fun uploadMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): MediaUpload

    /**
     * #24（v1.2.0 additive，多模态 v1）：媒体内容拉取——客户端渲染面走 **Bearer** 分支
     * （设备绑定 Token；设计稿 §2.4：签名 URL 分支供 Bridge 机对面，客户端面不用——签名
     * URL 进历史行会结构性过期）。200 = 原始字节（Content-Type 为服务端嗅探 mime）；
     * 401 = Bearer 失效（TokenManager.withBearer 负责刷新重试）、404 = mediaId 非 ULID/
     * 未知/非 available（HUB-BIZ-002 防枚举）。[mediaId] 须为端点 #23 返回的 Hub ULID。
     */
    suspend fun fetchMediaContent(mediaId: String): ByteArray

    /** 已读上报命令 read.mark；readUpTo 指向当前化身的既有精确 message seq（05 §3.10）。 */
    suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt

    /**
     * 会话删除（#29，v1.4.0 additive 多主会话轮，204 无响应体与 deleteDevice 同款 no-body）：
     * Hub 主权 tombstone（用户发起的 App 面删除）——Hub append session.removed 事件驱动服务端
     * 投影 conversation_state='removed'，句柄与只读历史保留、不级联 messages/events；#7/快照
     * 与 #28 管理清单自此不再产出该行。**原生删除非承诺**：Gateway 侧物理删除不在本端点语义内。
     * 204 幂等终态：对已 removed 会话再次 DELETE 亦 204；「不存在与无权」统一 404 HUB-BIZ-002
     * 防枚举。默认实现抛 UnsupportedOperationException（存量测试 fake 不必逐一补 stub，
     * 见接口头注；生产实现必须覆盖）。
     */
    suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException("deleteConversation 为 v1.4.0 新端点，实现方必须覆盖")
    }

    // ── v1.1 新增（#16–#19；命令类 202 + CommandReceipt，幂等键由 Hub 生成，网关离线进 WAITING_BRIDGE 排队） ──
    /** 新开会话命令 conversation.create（#16，openclaw 原生指令端点）；新会话经 roster 同步自动出现在列表。404 HUB-BIZ-002。 */
    suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest): CommandReceipt

    /** 压缩上下文命令 conversation.compact（#17）；化身更替后 conversationId 不变。404 HUB-BIZ-002。 */
    suspend fun compactConversation(conversationId: String): CommandReceipt

    /** Agent 状态查询（#18，纯投影派生无副作用；限流走列表类）。 */
    suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse

    /**
     * 按 Agent 列会话（#28，v1.4.0 additive 多主会话轮，纯投影查询；限流走列表类）：
     * 管理面清单视图——与端点 #7 聚合口径不同，不收敛、不筛族（dashboard/主线族含
     * main-<idempotencyKey> 多主线/task/channel/subagent/cron 全键族可见），按 lastActivityAt
     * 降序、无分页；排除 removed 行（#29 tombstone 终态行不出清单）。
     * SessionSummary.isMainSession = 主线族谓词（消费面 rename 门据此判定）。
     * 「未知 Gateway/Agent/非属主」404 HUB-BIZ-002 防枚举。默认实现抛
     * UnsupportedOperationException（存量测试 fake 不必逐一补 stub，见接口头注）。
     */
    suspend fun listAgentSessions(gatewayId: String, agentId: String): SessionsResponse {
        throw UnsupportedOperationException("listAgentSessions 为 v1.4.0 新端点，实现方必须覆盖")
    }

    /**
     * 端点 #21（v1.1 additive，2026-11-07）：动态斜杠命令目录——Gateway commands.list 有界透传，
     * scope=text/includeArgs=false 服务端钉死（契约形状 HubModels.CommandListItem）；服务端解析
     * conversationId，无查询参数、无分页。纯读无副作用，限流走列表类（08 §9.1 每设备 60 次/分钟）；
     * 404 = 会话不存在/无权（HUB-BIZ-002 防枚举既有语义），503 = 桥离线/查询超时/上游不可用。
     * truncated=true 表示列表被截断（Gateway 500 上限或桥帧预算），客户端按已有列表渲染即可。
     */
    suspend fun commands(conversationId: String): CommandsList

    /**
     * #22（v1.1 additive，2026-11-08）：会话模型目录——Gateway models.list 的服务端有界透传。
     * conversationId 由服务端解析（无查询参数、无分页）。纯读无副作用，限流走列表类
     * （08 §9.1 每设备 60 次/分钟）；404 = 会话不存在/无权（HUB-BIZ-002 防枚举既有语义），
     * 503 = 桥离线/查询超时/上游不可用。truncated=true 表示列表被截断（条目上限 100 或
     * 桥帧预算），客户端按已有列表渲染即可。消费面 = 斜杠交互卡（/model 命令回复落定后
     * 渲染模型卡，点按钮发 `/model <id>`——发送走既有 message.send 通道）。
     */
    suspend fun models(conversationId: String): ModelsList

    /** 会话重命名命令 conversation.rename（#19）；结果经 session.updated 事件与列表投影更新。404 HUB-BIZ-002。 */
    suspend fun renameConversation(conversationId: String, req: RenameConversationRequest): CommandReceipt

    // ── 命令（#11 / #12，Bearer） ────────────────────────────────────────
    /** 取消 Run 命令 run.cancel；runId 只能从事件流获得；已结束后取消为幂等成功。 */
    suspend fun cancelRun(runId: String, req: CancelRunRequest): CommandReceipt

    /** 审批决定命令 approval.decide；decision ∈ approve/reject；approvalId 从 approval.requested 载荷获得。B-4 假设未验证，不做主路径。 */
    suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt

    // ── 同步（#13 / #14，Bearer） ────────────────────────────────────────
    /** 全量快照：无状态；首次初始化 / 游标过期（410）/ 缓存怀疑失真时直接调用。不含 approvals 与 run 明细。 */
    suspend fun snapshot(): SnapshotResponse

    /** 事件增量（同端点两用途）：返回 (cursor, …] 按 id 升序；串行分页至 hasMore=false 且 nextCursor==currentWatermark。 */
    suspend fun events(cursor: String, limit: Int? = null): EventsPage

    // ── 用户形象档（#30 / #31，v1.5.0 additive；s.bearer 非 bearerDevice——用户级资源非设备绑定） ──

    /**
     * 形象档读取（#30，v1.5.0 additive，纯投影读无副作用）：资源恒存在——未设置过返回
     * 默认档（faceFactors 12×1.00、GRAY、standard、updatedAt=epoch 哨兵），无 404 语义；
     * 无会话级 Token 403 HUB-AUTH-006 分支（撤销设备 Token 失效仍走 403 HUB-AUTH-004）。
     * 默认实现抛 UnsupportedOperationException（存量测试 fake 不必逐一补 stub，见接口头注；
     * 生产实现必须覆盖——#28/#29 同款纪律）。
     */
    suspend fun getAvatarProfile(): AvatarProfile {
        throw UnsupportedOperationException("getAvatarProfile 为 v1.5.0 新端点，实现方必须覆盖")
    }

    /**
     * 形象档保存（#31，v1.5.0 additive，幂等覆盖写）：**返回 200 + AvatarProfile**（服务端
     * 权威 updatedAt 直接回填，客户端无二程 GET）；同值重 PUT 返回同 body、不更新 updatedAt、
     * 不重复广播（同值无副作用）。逐字段校验在服务端（越界/长度≠12/未知枚举 → 400
     * HUB-BIZ-004 不落库）；updatedAt 不入请求体（恒由服务端权威设定，见 [AvatarProfileRequest]）。
     * 保存成功后服务端 best-effort 广播 WSS 控制帧 avatar.updated 至该用户全部在线连接（含
     * 发起设备自身——自收帧因 updatedAt 与本响应相等而幂等跳过）。默认实现抛
     * UnsupportedOperationException（同 [getAvatarProfile] 纪律）。
     */
    suspend fun putAvatarProfile(req: AvatarProfileRequest): AvatarProfile {
        throw UnsupportedOperationException("putAvatarProfile 为 v1.5.0 新端点，实现方必须覆盖")
    }
}
