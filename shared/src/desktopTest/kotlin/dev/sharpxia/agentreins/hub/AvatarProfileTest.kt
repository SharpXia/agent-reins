package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.avatar.AgentAvatarStatus
import dev.sharpxia.agentreins.hub.avatar.AgentExpression
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileJson
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileRequest
import dev.sharpxia.agentreins.hub.avatar.DEFAULT_AVATAR_PROFILE
import dev.sharpxia.agentreins.hub.avatar.DEFAULT_FACE_FACTORS
import dev.sharpxia.agentreins.hub.avatar.UPDATED_AT_EPOCH
import dev.sharpxia.agentreins.hub.avatar.avatarUpdatedAtNewer
import dev.sharpxia.agentreins.hub.avatar.convergeAvatarProfile
import dev.sharpxia.agentreins.hub.avatar.encodeFaceFactors
import dev.sharpxia.agentreins.hub.avatar.facePoint
import dev.sharpxia.agentreins.hub.avatar.kotlinHashCode
import dev.sharpxia.agentreins.hub.avatar.parseAvatarProfileJson
import dev.sharpxia.agentreins.hub.avatar.parseFaceFactors
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hub.sync.DefaultSyncScheduler
import dev.sharpxia.agentreins.hub.sync.SyncPhase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.5.0 个性化形象轮 shared 测试（设计稿 clientPlan desktopTest 锚点）：
 * ① AvatarProfile 宽松解析（未知枚举回落 / 长度≠12 或越界回退正圆 / 未知字段忽略）；
 * ② ClayFace 公式镜像（parse/encode 往返 + 8 款预设 encode 往返 + 相位锚几何锚点）；
 * ③ 收敛规则（updatedAt 大者胜 / 同值幂等跳过 / epoch 哨兵最小）；
 * ④ WSS avatar.updated 帧解码（载荷抽取 / 形状漂移静默 / 未知帧忽略）；
 * ⑤ DefaultSyncScheduler 双钩子（sync.ready 回拉钩子 / avatar.updated 推送钩子）；
 * ⑥ DefaultHubApi #30/#31 路径断言（GET/PUT /v1/me/avatar-profile，MockEngine 实管线）+
 *    AvatarProfileRequest 线格式（三字段恒在、updatedAt 不入请求）。
 */
class AvatarProfileTest {

    // ── ① 宽松解析（收侧，设计稿 §dataModel 规则 4） ──

    @Test
    fun unknownMotionFallsBackToStandard() {
        val p = parseAvatarProfileJson(
            """{"faceFactors":[1,1,1,1,1,1,1,1,1,1,1,1],"paletteId":"GRAY","motionLevel":"lively","updatedAt":"2026-11-20T08:00:00.000Z"}""",
        )
        assertEquals(AvatarMotion.STANDARD, p?.motionLevel) // lively 为 additive 保留位：未知回落 standard
    }

    @Test
    fun unknownPaletteFallsBackToGray() {
        val p = parseAvatarProfileJson(
            """{"faceFactors":[1,1,1,1,1,1,1,1,1,1,1,1],"paletteId":"CHARTREUSE","motionLevel":"off","updatedAt":"2026-11-20T08:00:00.000Z"}""",
        )
        assertEquals(AvatarPalette.GRAY, p?.paletteId)
        assertEquals(AvatarMotion.OFF, p?.motionLevel) // 已知 motion 正常收下
    }

    @Test
    fun wrongLengthFactorsFallBackToCircle() {
        val p = parseAvatarProfileJson(
            """{"faceFactors":[1,1,1,1,1,1],"paletteId":"BLUE","motionLevel":"off","updatedAt":"2026-11-20T08:00:00.000Z"}""",
        )
        assertEquals(DEFAULT_FACE_FACTORS, p?.faceFactors) // 长度≠12 → 回退正圆
        // 其余字段照常收下（宽松不是整体丢弃）
        assertEquals(AvatarPalette.BLUE, p?.paletteId)
    }

    @Test
    fun outOfRangeFactorsFallBackToCircle() {
        val p = parseAvatarProfileJson(
            """{"faceFactors":[1.2,1,1,1,1,1,1,1,1,1,1,1],"paletteId":"GRAY","motionLevel":"off","updatedAt":"2026-11-20T08:00:00.000Z"}""",
        )
        assertEquals(DEFAULT_FACE_FACTORS, p?.faceFactors) // 越界 → 回退正圆（设计稿规则 4）
    }

    @Test
    fun unknownFieldsIgnoredOnDecode() {
        val p = parseAvatarProfileJson(
            """{"faceFactors":[1,1,1,1,1,1,1,1,1,1,1,1],"paletteId":"GRAY","motionLevel":"off","updatedAt":"2026-11-20T08:00:00.000Z","originDeviceId":"dev-9","futureField":42}""",
        )
        assertEquals(AvatarMotion.OFF, p?.motionLevel) // 未知字段忽略（additive 前向兼容义务）
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(parseAvatarProfileJson("not-json"))
        assertNull(parseAvatarProfileJson(null))
    }

    // ── ② ClayFace 公式镜像（iOS RobotAvatar.swift:41-91 逐字口径） ──

    @Test
    fun encodeRoundTripIsStable() {
        val factors = listOf(1.03, 1.02, 1.01, 1.04, 1.0, 0.92, 0.94, 1.07, 1.10, 0.99, 0.91, 0.97)
        val encoded = encodeFaceFactors(factors)
        assertEquals(59, encoded.length) // 12×4−1（规范化串口径）
        assertEquals("1.03,1.02,1.01,1.04,1.00,0.92,0.94,1.07,1.10,0.99,0.91,0.97", encoded)
        // parse(encode(x)) == encode(x) 往返稳定
        assertEquals(encoded, encodeFaceFactors(parseFaceFactors(encoded)!!))
        // parse 逐值 clamp：12 段串内 1.50 越界 clamp 至 1.10
        val clamped = parseFaceFactors("1.50," + "1.00,".repeat(10) + "1.00")!!
        assertEquals(1.10, clamped.first())
    }

    @Test
    fun parseRejectsBadSegments() {
        assertNull(parseFaceFactors("1.0,1.0,1.0")) // 段数≠12
        assertNull(parseFaceFactors("a,b,c,d,e,f,g,h,i,j,k,l")) // 非数
        assertNull(parseFaceFactors(""))
    }

    @Test
    fun presetsEncodeRoundTrip() {
        assertEquals(8, AVATAR_PRESETS.size) // 目录 8 款（设计稿唯一事实，排序 = P1..P8）
        AVATAR_PRESETS.forEach { preset ->
            assertEquals(12, preset.faceFactors.size, "${preset.id} 恒 12 值")
            val encoded = encodeFaceFactors(preset.faceFactors)
            assertEquals(59, encoded.length, "${preset.id} 规范串 59 字符")
            assertEquals(encoded, encodeFaceFactors(parseFaceFactors(encoded)!!), "${preset.id} encode 往返稳定")
            assertEquals(preset.faceKey, encoded, "${preset.id} faceKey 即规范串")
            // 预设值全部落在契约域（服务端 400 域内）
            preset.faceFactors.forEach { f -> assertTrue(f in 0.70..1.10, "${preset.id} 值 $f 越界") }
        }
        assertEquals("圆满", AVATAR_PRESETS[0].zhName)
        assertEquals(AvatarPalette.GRAY, AVATAR_PRESETS[0].recommendedPalette)
        assertEquals("南瓜", AVATAR_PRESETS[7].zhName)
        assertEquals(AvatarPalette.ORANGE, AVATAR_PRESETS[7].recommendedPalette)
    }

    @Test
    fun facePointAnchorsAtTwelveOclockClockwise() {
        // 0 号 = 12 点方向：θ=0 → (50, 50−44f)（y 最小 = 顶端）
        val top = facePoint(0, 1.0)
        assertEquals(50.0, top.first, 1e-9)
        assertEquals(6.0, top.second, 1e-9) // 50 − 44
        // 3 号（i=3, θ=90°）= 3 点方向顺时针：x 最大、y=50
        val right = facePoint(3, 1.0)
        assertEquals(94.0, right.first, 1e-9)
        assertEquals(50.0, right.second, 1e-9)
    }

    @Test
    fun agentTintTablesMatchIosMirror() {
        // 声明序 = 跨端契约（照抄 AgentTintName），十色 wire 名一致
        assertEquals(
            listOf("GRAY", "BLUE", "RED", "ORANGE", "YELLOW", "GREEN", "CYAN", "PURPLE", "MAGENTA", "BROWN"),
            AgentTint.entries.map { it.wire },
        )
        assertEquals(0x8E8E93L, AgentTint.GRAY.strongLight)
        assertEquals(0x0A84FFL, AgentTint.BLUE.strongDark)
        assertEquals(0xBF5AF2L, AgentTint.PURPLE.strongDark) // InkTheme.swift:219 strongDark 表
        assertEquals(AgentTint.MAGENTA, AgentTint.of(AvatarPalette.MAGENTA))
    }

    @Test
    fun kotlinHashCodeMatchesJvmStringHashCode() {
        // 与 Swift kotlinHashCode 同源算法（Int32 环绕），对 JVM 即 String.hashCode
        assertEquals("main".hashCode(), kotlinHashCode("main"))
        assertEquals("01JGW000000000000000000000".hashCode(), kotlinHashCode("01JGW000000000000000000000"))
    }

    @Test
    fun statusExpressionMappingMirrorsComponents() {
        assertEquals(AgentExpression.THINKING, AgentAvatarStatus.RUNNING.resolveExpression(AgentExpression.CALM))
        assertEquals(AgentExpression.LOVE, AgentAvatarStatus.UNREAD.resolveExpression(AgentExpression.CALM))
        assertEquals(AgentExpression.SAD, AgentAvatarStatus.OFFLINE.resolveExpression(AgentExpression.CALM))
        assertEquals(AgentExpression.HAPPY, AgentAvatarStatus.INITIAL.resolveExpression(AgentExpression.CALM))
        // configured 无覆盖：配置表情透传（编辑面用户所选表情不被状态替换）
        assertEquals(AgentExpression.SMUG, AgentAvatarStatus.CONFIGURED.resolveExpression(AgentExpression.SMUG))
    }

    // ── ③ 收敛规则（last-write-wins，updatedAt 大者胜） ──

    private fun profile(updatedAt: String, motion: AvatarMotion = AvatarMotion.STANDARD) =
        AvatarProfile(DEFAULT_FACE_FACTORS, AvatarPalette.GRAY, motion, updatedAt)

    @Test
    fun convergeNewerUpdatedAtWins() {
        val old = profile("2026-11-20T08:00:00.000Z")
        val new = profile("2026-11-20T09:00:00.000Z", AvatarMotion.OFF)
        val merged = convergeAvatarProfile(old, new)
        assertEquals(AvatarMotion.OFF, merged?.motionLevel)
        assertEquals("2026-11-20T09:00:00.000Z", merged?.updatedAt)
    }

    @Test
    fun convergeEqualUpdatedAtSkipsIdempotently() {
        val same = profile("2026-11-20T08:00:00.000Z")
        val incoming = profile("2026-11-20T08:00:00.000Z", AvatarMotion.OFF)
        val merged = convergeAvatarProfile(same, incoming)
        // 同值（updatedAt 相等）幂等跳过：保留现值，不因自收帧回环闪烁
        assertEquals(AvatarMotion.STANDARD, merged?.motionLevel)
    }

    @Test
    fun convergeOlderIncomingLoses() {
        val new_ = profile("2026-11-20T09:00:00.000Z")
        val old = profile("2026-11-20T08:00:00.000Z", AvatarMotion.OFF)
        assertEquals(new_, convergeAvatarProfile(new_, old))
    }

    @Test
    fun convergeNullCurrentAcceptsAnyEpochSentinelSmallest() {
        assertNull(convergeAvatarProfile(null, null))
        assertEquals(DEFAULT_AVATAR_PROFILE, convergeAvatarProfile(null, DEFAULT_AVATAR_PROFILE))
        // epoch 哨兵恒最小：任何真实时间戳胜出（两个方向都以新者胜——真实档）
        val real = profile("2026-11-20T08:00:00.000Z")
        assertEquals(real, convergeAvatarProfile(DEFAULT_AVATAR_PROFILE, real))
        assertEquals(real, convergeAvatarProfile(real, DEFAULT_AVATAR_PROFILE))
    }

    @Test
    fun updatedAtNewerHandlesMillisecondDrift() {
        // 无毫秒段的漂移形态：解析回退路径仍然比较时序
        assertTrue(avatarUpdatedAtNewer("2026-11-20T09:00:00Z", "2026-11-20T08:00:00.000Z"))
        assertFalse(avatarUpdatedAtNewer("2026-11-20T08:00:00.000Z", "2026-11-20T08:00:00.000Z"))
    }

    // ── ④ 线格式：请求体三字段恒在、updatedAt 不入请求（契约 AvatarProfileRequest） ──

    @Test
    fun requestWireShapeAlwaysEncodesThreeFields() {
        // 全缺省值也必须编码（encodeDefaults=false 的 HubJson 会整个省掉缺省字段 → 服务端
        // 400 缺必填——@EncodeDefault(Mode.ALWAYS) 是防呆必需）
        assertEquals(
            """{"faceFactors":[1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0,1.0],"paletteId":"GRAY","motionLevel":"standard"}""",
            AvatarProfileJson.encodeToString(AvatarProfileRequest.serializer(), AvatarProfileRequest(DEFAULT_AVATAR_PROFILE)),
        )
        // 响应面（AvatarProfile）四字段恒在（含 updatedAt）
        val wire = AvatarProfileJson.encodeToString(AvatarProfile.serializer(), DEFAULT_AVATAR_PROFILE)
        assertTrue(wire.contains(""""updatedAt":"$UPDATED_AT_EPOCH""""))
        assertTrue(wire.contains(""""paletteId":"GRAY""""))
    }

    // ── ⑤ WSS avatar.updated 帧解码 ──

    private fun newWs(): DefaultHubWsClient = DefaultHubWsClient(
        config = HubConfig("http://x/v1", "ws://x/v1/ws"),
        accessTokenProvider = { null },
        scope = kotlinx.coroutines.MainScope(),
    )

    @Test
    fun wsAvatarUpdatedFrameDecodes() {
        val ws = newWs()
        val frame = ws.decodeFrame(
            """{"type":"avatar.updated","data":{"profile":{"faceFactors":[1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95],"paletteId":"PURPLE","motionLevel":"standard","updatedAt":"2026-11-20T08:00:00.000Z"}}}""",
        )
        val updated = assertIs<HubServerFrame.AvatarUpdated>(frame)
        assertEquals(AvatarPalette.PURPLE, updated.profile.paletteId)
        assertEquals(12, updated.profile.faceFactors.size)
    }

    @Test
    fun wsAvatarUpdatedFrameShapeDriftSilentlyIgnored() {
        val ws = newWs()
        assertNull(ws.decodeFrame("""{"type":"avatar.updated","data":{}}""")) // 缺 profile
        assertNull(ws.decodeFrame("""{"type":"avatar.updated"}""")) // 缺 data
        assertNull(ws.decodeFrame("""{"type":"avatar.updated","data":{"profile":{"faceFactors":"bad"}}}""")) // 形状漂移
        assertNull(ws.decodeFrame("""{"type":"totally.future.frame","data":{}}""")) // 未知 type 静默忽略
    }

    // ── ⑥ DefaultSyncScheduler 双钩子（Ready 回拉 / avatar.updated 推送） ──

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun schedulerHooksFireOnReadyAndAvatarFrame() = runTest {
        val db = newDb()
        val cursors = DbEventCursorStore(db)
        val engine = DefaultSyncEngine(StubHubApi(), StubAuth(), DefaultEventProjector(db), cursors)
        val ws = FakeWs()
        var readyCount = 0
        val updatedProfiles = ArrayList<AvatarProfile>()
        val scheduler = DefaultSyncScheduler(
            backgroundScope, engine, ws, cursors,
            onReady = { readyCount++ },
            onAvatarUpdated = { updatedProfiles.add(it) },
        )
        scheduler.start()
        testScheduler.runCurrent() // runLoop 至首挂起点，帧收集器已订阅

        ws.emit(HubServerFrame.Ready("01JWM000000000000000000000"))
        testScheduler.runCurrent()
        assertEquals(1, readyCount) // sync.ready → 回拉钩子（登录/重连回拉时点）
        assertEquals(SyncPhase.Linked, scheduler.phase.value) // 相位语义不变

        ws.emit(
            HubServerFrame.AvatarUpdated(
                AvatarProfile(listOf(1.06, 0.96, 1.02, 1.03, 0.92, 1.01, 1.06, 0.96, 1.02, 1.03, 0.92, 1.01),
                    AvatarPalette.BLUE, AvatarMotion.OFF, "2026-11-20T08:00:00.000Z"),
            ),
        )
        testScheduler.runCurrent()
        assertEquals(1, updatedProfiles.size) // 控制帧直达推送钩子
        assertEquals(AvatarPalette.BLUE, updatedProfiles.single().paletteId)
        scheduler.stop()
    }

    // ── ⑦ DefaultHubApi #30/#31 路径断言（MockEngine 实管线） ──

    @Test
    fun hubApiGetAvatarProfileHitsContractPath() = runTest {
        val recorded = ArrayList<String>()
        val api = mockApi(recorded, responseBody = DEFAULT_AVATAR_PROFILE.let {
            AvatarProfileJson.encodeToString(AvatarProfile.serializer(), it)
        })
        val profile = api.getAvatarProfile()
        assertEquals(listOf("GET /v1/me/avatar-profile"), recorded)
        assertEquals(UPDATED_AT_EPOCH, profile.updatedAt) // 未设置过 = 默认档 + epoch 哨兵（无 404 语义）
        assertEquals(DEFAULT_FACE_FACTORS, profile.faceFactors)
    }

    @Test
    fun hubApiPutAvatarProfileHitsContractPathAndDecodesAuthority() = runTest {
        val recorded = ArrayList<String>()
        val capturedBody = StringBuilder()
        val authority = AvatarProfile(DEFAULT_FACE_FACTORS, AvatarPalette.GREEN, AvatarMotion.OFF, "2026-11-20T10:00:00.000Z")
        val api = mockApi(recorded, responseBody = AvatarProfileJson.encodeToString(AvatarProfile.serializer(), authority)) { body ->
            capturedBody.append(body)
        }
        val req = AvatarProfileRequest(AvatarProfile(listOf(0.94, 0.98, 1.06, 1.10, 1.06, 0.98, 0.94, 0.98, 1.06, 1.10, 1.06, 0.98), AvatarPalette.CYAN, AvatarMotion.STANDARD))
        val saved = api.putAvatarProfile(req)
        assertEquals(listOf("PUT /v1/me/avatar-profile"), recorded)
        // 200 回服务端权威档（updatedAt 直接回填，客户端无二程 GET）
        assertEquals("2026-11-20T10:00:00.000Z", saved.updatedAt)
        assertEquals(AvatarPalette.GREEN, saved.paletteId)
        // 请求体：faceFactors/paletteId/motionLevel 三可编辑字段在、updatedAt 不在
        val bodyJson = Json.parseToJsonElement(capturedBody.toString()).jsonObject
        assertEquals(12, bodyJson["faceFactors"]!!.jsonArray.size)
        assertEquals("CYAN", bodyJson["paletteId"]!!.jsonPrimitive.content)
        assertEquals("standard", bodyJson["motionLevel"]!!.jsonPrimitive.content)
        assertNull(bodyJson["updatedAt"]) // updatedAt 恒由服务端权威设定，不入请求
    }

    // ── fixture ──

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    /** MockEngine 实管线桩：记录「METHOD 路径」并以 [responseBody] 回 200。 */
    private fun mockApi(
        recorded: MutableList<String>,
        responseBody: String,
        onBody: (String) -> Unit = {},
    ): HubApi = DefaultHubApi(
        config = HubConfig("http://hub.test/v1", "ws://hub.test/v1/ws"),
        accessTokenProvider = { "test-token" },
        http = HttpClient(MockEngine { request ->
            recorded.add("${request.method.value} ${request.url.encodedPath}")
            // ContentNegotiation 序列化后的请求体（TextContent）；GET 无体为空串
            val bodyText = (request.body as? io.ktor.http.content.TextContent)?.text ?: ""
            onBody(bodyText)
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json(HubJson) }
        },
    )

    private open class StubHubApi(
        /** bootstrap 必须可运行（否则 runLoop 首步即抛、帧收集器永不挂上）：空系统最小快照。 */
        private val snapshot: dev.sharpxia.agentreins.hub.projection.SnapshotResponse =
            dev.sharpxia.agentreins.hub.projection.SnapshotResponse(
                snapshotWatermark = "01JWM000000000000000000000",
                generatedAt = "2026-11-20T08:00:00.000Z",
            ),
    ) : HubApi {
        override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = TODO()
        override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = TODO()
        override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = TODO()
        override suspend fun listDevices() = TODO()
        override suspend fun deleteDevice(deviceId: String) = TODO()
        override suspend fun registerApnsToken(req: dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest) = TODO()
        override suspend fun gateways() = TODO()
        override suspend fun conversations(limit: Int?, cursor: String?) =
            dev.sharpxia.agentreins.hub.projection.ConversationsPage(conversations = emptyList())
        override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
        override suspend fun sendMessage(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.SendMessageRequest) = TODO()
        override suspend fun uploadMedia(conversationId: String, bytes: ByteArray, mime: String, fileName: String?, width: Int?, height: Int?) = TODO()
        override suspend fun fetchMediaContent(mediaId: String) = TODO()
        override suspend fun commands(conversationId: String) = TODO()
        override suspend fun models(conversationId: String) = TODO()
        override suspend fun markRead(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.ReadCursorRequest) = TODO()
        override suspend fun createConversation(gatewayId: String, agentId: String, req: dev.sharpxia.agentreins.hub.projection.CreateConversationRequest) = TODO()
        override suspend fun compactConversation(conversationId: String) = TODO()
        override suspend fun agentStatus(gatewayId: String, agentId: String): dev.sharpxia.agentreins.hub.projection.AgentStatusResponse = TODO()
        override suspend fun renameConversation(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.RenameConversationRequest) = TODO()
        override suspend fun cancelRun(runId: String, req: dev.sharpxia.agentreins.hub.projection.CancelRunRequest) = TODO()
        override suspend fun decideApproval(approvalId: String, req: dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest) = TODO()
        override suspend fun snapshot(): dev.sharpxia.agentreins.hub.projection.SnapshotResponse = snapshot
        override suspend fun createGateway(req: dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest): dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse = TODO()
        override suspend fun rotateBridgeToken(gatewayId: String): dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse = TODO()
        override suspend fun deleteGateway(gatewayId: String) = TODO()
        override suspend fun events(cursor: String, limit: Int?) =
            dev.sharpxia.agentreins.hub.projection.EventsPage(
                events = emptyList(), nextCursor = cursor, hasMore = false,
                currentWatermark = "01JWM000000000000000000000",
            )
    }

    private open class StubAuth : TokenManager {
        override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
        override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
        override suspend fun restoreFromStoredRefreshToken() = false
        override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("avatar-test-token")
        override fun currentAccessToken(): String? = null
        override suspend fun clearCredentials() {}
    }

    /** 帧投喂假件（HubSyncFixtureTest PhaseFakeWs 同式，无 replay 先订阅后投喂）。 */
    private class FakeWs : HubWsClient {
        private val outgoing = MutableSharedFlow<HubServerFrame>(extraBufferCapacity = 16)
        override val closed = MutableStateFlow<HubWsClosed?>(null)
        // 传输层活性计数（FLOW-5）：emit 即计活，与真件「任意到达帧刷活性」同语义
        override val transportActivity = MutableStateFlow(0L)
        override suspend fun connect(cursor: String) {}
        override suspend fun close() {}
        override fun frames(): Flow<HubServerFrame> = outgoing

        fun emit(frame: HubServerFrame) {
            check(outgoing.tryEmit(frame)) { "fake ws emit buffer overflow" }
            transportActivity.value += 1
        }
    }
}
