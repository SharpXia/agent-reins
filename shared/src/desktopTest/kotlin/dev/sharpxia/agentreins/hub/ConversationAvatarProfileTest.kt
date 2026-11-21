package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileJson
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileRequest
import dev.sharpxia.agentreins.hub.avatar.ConversationAvatarProfileEntry
import dev.sharpxia.agentreins.hub.avatar.DEFAULT_FACE_FACTORS
import dev.sharpxia.agentreins.hub.avatar.conversationAvatarMapFromEntries
import dev.sharpxia.agentreins.hub.avatar.encodeConversationAvatarMapJson
import dev.sharpxia.agentreins.hub.avatar.mergeConversationAvatarProfile
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hub.sync.DefaultSyncScheduler
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.5.2 会话级形象覆盖档 shared 测试（镜像 AvatarProfileTest v1.5.0 用户档口径）：
 * ① conversation.avatar.updated 帧解码——profile 帧抽取 / **profile=null 清除帧合法交付**（非
 *    脏帧）/ 形状漂移静默（缺 data/conversationId/profile 键、档解析失败）/ 未知帧忽略；
 * ② ConversationAvatarProfileEntry 线格式（三字段 required、updatedAt ≡ profile.updatedAt）+
 *    覆盖档映射纯函数（per-key updatedAt 大者胜 / 同值幂等跳过 / 清除按键移除且重复应用无
 *    副作用 / 未水合期清除保持 null / 空清单水合 = emptyMap 非 null）；
 * ③ DefaultHubApi #32/#33/#34 路径断言（MockEngine 实管线：PUT 路径+请求体线格式 updatedAt
 *    不入、DELETE 204、GET 列表解码含空数组）；
 * ④ DefaultSyncScheduler 会话覆盖档推送钩子（profile 帧与 null 清除帧直达）；
 * ⑤ DefaultHubClient store 语义（conversationAvatarApiOverride 注入通道，镜像 sessionAdminApiOverride
 *    先例）：PUT 先成功再落 store、DELETE 先 204 再清键（重复 DELETE 幂等）、#34 水合整体替换
 *    （陈旧键随清单缺席清除）、空清单水合 = emptyMap、未登录门面抛 IllegalStateException、
 *    wipeLocalData 复位 null。
 */
class ConversationAvatarProfileTest {

    // ── ① WSS conversation.avatar.updated 帧解码 ──

    private fun newWs(): DefaultHubWsClient = DefaultHubWsClient(
        config = HubConfig("http://x/v1", "ws://x/v1/ws"),
        accessTokenProvider = { null },
        scope = kotlinx.coroutines.MainScope(),
    )

    @Test
    fun wsConversationAvatarFrameDecodesProfile() {
        val ws = newWs()
        val frame = ws.decodeFrame(
            """{"type":"conversation.avatar.updated","data":{"conversationId":"$CONV","profile":{"faceFactors":[1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95,1.05,0.95],"paletteId":"PURPLE","motionLevel":"standard","updatedAt":"$T1"}}}""",
        )
        val updated = assertIs<HubServerFrame.ConversationAvatarUpdated>(frame)
        assertEquals(CONV, updated.conversationId)
        assertEquals(AvatarPalette.PURPLE, updated.profile?.paletteId)
        assertEquals(12, updated.profile?.faceFactors?.size)
        assertEquals(T1, updated.profile?.updatedAt)
    }

    @Test
    fun wsConversationAvatarFrameNullProfileIsLegalClear() {
        // profile=null = 端点 #33 广播的合法清除帧（非脏帧）：按键交付 null，调用方移除覆盖回落 seed 派生
        val frame = newWs().decodeFrame(
            """{"type":"conversation.avatar.updated","data":{"conversationId":"$CONV","profile":null}}""",
        )
        val updated = assertIs<HubServerFrame.ConversationAvatarUpdated>(frame)
        assertEquals(CONV, updated.conversationId)
        assertNull(updated.profile)
    }

    @Test
    fun wsConversationAvatarFrameShapeDriftSilentlyIgnored() {
        val ws = newWs()
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated"}""")) // 缺 data
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated","data":{}}""")) // 缺 conversationId/profile
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated","data":{"conversationId":"$CONV"}}""")) // profile 键缺席 = 漂移（清除的权威形态是显式 null）
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated","data":{"conversationId":"","profile":null}}""")) // 空 conversationId
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated","data":{"conversationId":42,"profile":null}}""")) // 非 string conversationId
        assertNull(ws.decodeFrame("""{"type":"conversation.avatar.updated","data":{"conversationId":"$CONV","profile":{"faceFactors":"bad"}}}""")) // 档形状漂移
        assertNull(ws.decodeFrame("""{"type":"totally.future.frame","data":{}}""")) // 未知 type 静默忽略（既有纪律）
    }

    // ── ② 条目线格式 + 覆盖档映射纯函数（per-key LWW 镜像 convergeAvatarProfile） ──

    private fun profile(updatedAt: String, palette: AvatarPalette = AvatarPalette.GRAY, motion: AvatarMotion = AvatarMotion.STANDARD) =
        AvatarProfile(DEFAULT_FACE_FACTORS, palette, motion, updatedAt)

    private fun entry(conversationId: String, updatedAt: String, palette: AvatarPalette) =
        ConversationAvatarProfileEntry(conversationId, profile(updatedAt, palette), updatedAt)

    @Test
    fun entryWireShapeRequiresThreeFields() {
        val e = entry(CONV, T1, AvatarPalette.CYAN)
        val wire = AvatarProfileJson.encodeToString(ConversationAvatarProfileEntry.serializer(), e)
        assertTrue(wire.contains(""""conversationId":"$CONV""""))
        assertTrue(wire.contains(""""paletteId":"CYAN""""))
        assertTrue(wire.contains(""""updatedAt":"$T1"""")) // 三字段全 required；updatedAt ≡ profile.updatedAt
        assertEquals(e, AvatarProfileJson.decodeFromString(ConversationAvatarProfileEntry.serializer(), wire)) // decode 往返
    }

    @Test
    fun mergeNewerUpdatedAtWinsPerKey() {
        val map = mapOf(CONV to profile(T1, AvatarPalette.CYAN), CONV2 to profile(T1, AvatarPalette.BLUE))
        val merged = mergeConversationAvatarProfile(map, CONV, profile(T2, AvatarPalette.PURPLE))
        assertEquals(AvatarPalette.PURPLE, merged?.get(CONV)?.paletteId) // 大者胜只作用目标键
        assertEquals(AvatarPalette.BLUE, merged?.get(CONV2)?.paletteId) // 邻键零波及
        assertEquals(T2, merged?.get(CONV)?.updatedAt)
    }

    @Test
    fun mergeEqualUpdatedAtSkipsIdempotently() {
        // PUT 响应落 store（PURPLE@T2）后发起设备自收帧（updatedAt 相等、内容异形）→ 幂等跳过保现值
        val map = mapOf(CONV to profile(T2, AvatarPalette.PURPLE))
        val merged = mergeConversationAvatarProfile(map, CONV, profile(T2, AvatarPalette.CYAN, AvatarMotion.OFF))
        assertEquals(AvatarPalette.PURPLE, merged?.get(CONV)?.paletteId)
        assertEquals(AvatarMotion.STANDARD, merged?.get(CONV)?.motionLevel)
        // 陈旧帧（updatedAt 更小）同样保现值
        val stale = mergeConversationAvatarProfile(map, CONV, profile(T1, AvatarPalette.BROWN))
        assertEquals(AvatarPalette.PURPLE, stale?.get(CONV)?.paletteId)
    }

    @Test
    fun mergeNullProfileClearsKeyOnlyAndRepeatIsNoOp() {
        val map = mapOf(CONV to profile(T1, AvatarPalette.CYAN), CONV2 to profile(T1, AvatarPalette.BLUE))
        val cleared = mergeConversationAvatarProfile(map, CONV, null)
        assertEquals(setOf(CONV2), cleared?.keys) // 清除只按键移除（回落 seed 派生是渲染语义，store 不造默认值）
        assertEquals(AvatarPalette.BLUE, cleared?.get(CONV2)?.paletteId)
        // null 帧无 updatedAt：重复应用无副作用（键已不在场 = 同实例原样返回）
        assertEquals(cleared, mergeConversationAvatarProfile(cleared, CONV, null))
        val same = mapOf(CONV2 to profile(T1))
        assertTrue(mergeConversationAvatarProfile(same, CONV, null) === same) // 同实例 → StateFlow 同值不重推
    }

    @Test
    fun mergeOnUnhydratedKeepsNullForClearCreatesMapForProfile() {
        // 未水合期（null）：清除帧保持 null 不虚假升格 emptyMap（null = 尚未水合的独立状态）
        assertNull(mergeConversationAvatarProfile(null, CONV, null))
        // profile 帧则就地建表落键（未水合先 PUT 的合法路径，随后 #34 水合整体替换）
        assertEquals(mapOf(CONV to profile(T1, AvatarPalette.CYAN)), mergeConversationAvatarProfile(null, CONV, profile(T1, AvatarPalette.CYAN)))
    }

    @Test
    fun hydrateFromEntriesWholesaleAndEmptyListGivesEmptyMap() {
        val entries = listOf(entry(CONV, T1, AvatarPalette.CYAN), entry(CONV2, T2, AvatarPalette.BLUE))
        assertEquals(
            mapOf(CONV to profile(T1, AvatarPalette.CYAN), CONV2 to profile(T2, AvatarPalette.BLUE)),
            conversationAvatarMapFromEntries(entries),
        )
        // #34 空数组 = 已水合无覆盖：emptyMap 非 null
        assertEquals(emptyMap(), conversationAvatarMapFromEntries(emptyList()))
    }

    @Test
    fun conversationAvatarMapJsonEncodeRoundTrip() {
        val map = mapOf(CONV to profile(T1, AvatarPalette.CYAN))
        val wire = map.encodeConversationAvatarMapJson()
        assertEquals(map, AvatarProfileJson.decodeFromString(CONVERSATION_AVATAR_MAP_SERIALIZER, wire))
    }

    // ── ③ DefaultHubApi #32/#33/#34 路径断言（MockEngine 实管线） ──

    private fun routingApi(
        recorded: MutableList<String>,
        handler: (method: String, path: String, body: String) -> Pair<HttpStatusCode, String>,
    ): HubApi = DefaultHubApi(
        config = HubConfig("http://hub.test/v1", "ws://hub.test/v1/ws"),
        accessTokenProvider = { "test-token" },
        http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val bodyText = (request.body as? TextContent)?.text ?: ""
            recorded.add("${request.method.value} $path")
            val (status, content) = handler(request.method.value, path, bodyText)
            respond(content, status, io.ktor.http.headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(HubJson) }
        },
    )

    @Test
    fun hubApiPutConversationAvatarProfileHitsContractPathAndDecodesAuthority() = runTest {
        val recorded = ArrayList<String>()
        val capturedBody = StringBuilder()
        val authority = profile(T2, AvatarPalette.GREEN, AvatarMotion.OFF)
        val api = routingApi(recorded) { method, path, body ->
            assertEquals("PUT", method)
            assertEquals("/v1/conversations/$CONV/avatar-profile", path) // #32 契约路径
            capturedBody.append(body)
            HttpStatusCode.OK to AvatarProfileJson.encodeToString(AvatarProfile.serializer(), authority)
        }
        val saved = api.putConversationAvatarProfile(CONV, AvatarProfileRequest(profile(T1, AvatarPalette.CYAN)))
        assertEquals(listOf("PUT /v1/conversations/$CONV/avatar-profile"), recorded)
        // 200 回服务端权威档（updatedAt 直接回填，客户端无二程 GET）
        assertEquals(T2, saved.updatedAt)
        assertEquals(AvatarPalette.GREEN, saved.paletteId)
        // 请求体：faceFactors/paletteId/motionLevel 三可编辑字段在、updatedAt 不在（同 #31 体形）
        val bodyJson = Json.parseToJsonElement(capturedBody.toString()).jsonObject
        assertEquals(12, bodyJson["faceFactors"]!!.jsonArray.size)
        assertEquals("CYAN", bodyJson["paletteId"]!!.jsonPrimitive.content)
        assertNull(bodyJson["updatedAt"]) // updatedAt 恒由服务端权威设定
    }

    @Test
    fun hubApiDeleteConversationAvatarProfileHitsContractPath204() = runTest {
        val recorded = ArrayList<String>()
        val api = routingApi(recorded) { method, path, _ ->
            assertEquals("DELETE", method)
            assertEquals("/v1/conversations/$CONV/avatar-profile", path) // #33 契约路径
            HttpStatusCode.NoContent to "" // 204 无响应体幂等终态
        }
        api.deleteConversationAvatarProfile(CONV) // 不抛 = 204 通路（无覆盖重复 DELETE 同）
        assertEquals(listOf("DELETE /v1/conversations/$CONV/avatar-profile"), recorded)
    }

    @Test
    fun hubApiListConversationAvatarProfilesDecodesEntriesAndEmptyArray() = runTest {
        val recorded = ArrayList<String>()
        val entries = listOf(entry(CONV, T1, AvatarPalette.CYAN), entry(CONV2, T2, AvatarPalette.BLUE))
        val api = routingApi(recorded) { method, path, _ ->
            assertEquals("GET", method)
            assertEquals("/v1/me/conversation-avatar-profiles", path) // #34 契约路径
            HttpStatusCode.OK to AvatarProfileJson.encodeToString(
                ListSerializer(ConversationAvatarProfileEntry.serializer()), entries,
            )
        }
        val list = api.listConversationAvatarProfiles()
        assertEquals(listOf("GET /v1/me/conversation-avatar-profiles"), recorded)
        assertEquals(2, list.size)
        assertEquals(CONV, list[0].conversationId)
        assertEquals(T1, list[0].updatedAt)
        assertEquals(list[0].updatedAt, list[0].profile.updatedAt) // 条目 updatedAt ≡ profile.updatedAt 同源
        // 空数组 = 无覆盖（非 null、非错误）
        val empty = routingApi(ArrayList<String>()) { _, _, _ -> HttpStatusCode.OK to "[]" }.listConversationAvatarProfiles()
        assertTrue(empty.isEmpty())
    }

    // ── ④ DefaultSyncScheduler 会话覆盖档推送钩子（profile 帧 + null 清除帧直达） ──

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun schedulerConversationAvatarHookFiresOnProfileAndClearFrames() = runTest {
        val db = newDb()
        val cursors = DbEventCursorStore(db)
        val engine = DefaultSyncEngine(ConvAvatarStubApi(), ConvAvatarStubAuth(), DefaultEventProjector(db), cursors)
        val ws = ConvAvatarFakeWs()
        val frames = ArrayList<Pair<String, AvatarProfile?>>()
        val scheduler = DefaultSyncScheduler(
            backgroundScope, engine, ws, cursors,
            onConversationAvatarUpdated = { id, p -> frames.add(id to p) },
        )
        scheduler.start()
        testScheduler.runCurrent() // runLoop 至首挂起点，帧收集器已订阅

        ws.emit(HubServerFrame.Ready(WM))
        testScheduler.runCurrent()
        ws.emit(HubServerFrame.ConversationAvatarUpdated(CONV, profile(T1, AvatarPalette.CYAN)))
        testScheduler.runCurrent()
        ws.emit(HubServerFrame.ConversationAvatarUpdated(CONV, null)) // 清除帧原样直达（不判脏）
        testScheduler.runCurrent()
        assertEquals(
            listOf(CONV to profile(T1, AvatarPalette.CYAN), CONV to null),
            frames,
        )
        scheduler.stop()
    }

    // ── ⑤ DefaultHubClient store 语义（override 注入通道；db 纯内存态面不落投影库） ──

    /** 未登录客户端：门面走 conversationAvatarApiOverride（fake 自管鉴权），镜像 GatewaySurfaceTest 先例。 */
    private fun newClient(scope: CoroutineScope) =
        DefaultHubClient(scope, HubRuntime(scope, ConvAvatarMemoryStore()), null)

    @Test
    fun facadePutLandsInStoreAfterSuccess() = runTest {
        val client = newClient(backgroundScope)
        assertNull(client.conversationAvatarProfiles.value) // 未水合 = null
        val api = ConvAvatarFakeApi(authority = profile(T2, AvatarPalette.PURPLE))
        client.conversationAvatarApiOverride = api
        val saved = client.putConversationAvatarProfile(CONV, profile(T1, AvatarPalette.CYAN))
        assertEquals(T2, saved.updatedAt) // 服务端权威 updatedAt 回填
        assertEquals(AvatarPalette.PURPLE, client.conversationAvatarProfiles.value?.get(CONV)?.paletteId) // 先 PUT 成功再落 store
        assertEquals(1, api.puts)
    }

    @Test
    fun facadeDeleteClearsStoreAndRepeatIsIdempotent() = runTest {
        val client = newClient(backgroundScope)
        val api = ConvAvatarFakeApi(authority = profile(T2, AvatarPalette.CYAN))
        client.conversationAvatarApiOverride = api
        client.putConversationAvatarProfile(CONV, profile(T1))
        assertEquals(AvatarPalette.CYAN, client.conversationAvatarProfiles.value?.get(CONV)?.paletteId)

        client.deleteConversationAvatarProfile(CONV) // 204 → 键移除（回落 seed 派生是渲染语义）
        assertNull(client.conversationAvatarProfiles.value?.get(CONV))
        assertTrue(client.conversationAvatarProfiles.value?.isEmpty() == true)

        client.deleteConversationAvatarProfile(CONV) // 无覆盖重复 DELETE 亦 204（幂等不依赖读状态）
        assertEquals(2, api.deletes)
        assertTrue(client.conversationAvatarProfiles.value?.isEmpty() == true)
    }

    @Test
    fun facadeHydrateReplacesWholesaleRemovingStaleKeys() = runTest {
        val client = newClient(backgroundScope)
        val api = ConvAvatarFakeApi()
        client.conversationAvatarApiOverride = api
        // 首次水合：两键落表
        api.listResult = listOf(entry(CONV, T1, AvatarPalette.CYAN), entry(CONV2, T1, AvatarPalette.BLUE))
        client.listConversationAvatarProfiles()
        assertEquals(2, client.conversationAvatarProfiles.value?.size)
        // 二次水合：清单只剩一键（他端已清除 CONV2）→ #34 权威整体替换，陈旧键随缺席清除
        api.listResult = listOf(entry(CONV, T1, AvatarPalette.CYAN))
        client.listConversationAvatarProfiles()
        assertEquals(setOf(CONV), client.conversationAvatarProfiles.value?.keys)
    }

    @Test
    fun facadeHydrateEmptyListYieldsEmptyMapNotNull() = runTest {
        val client = newClient(backgroundScope)
        val api = ConvAvatarFakeApi(listResult = emptyList())
        client.conversationAvatarApiOverride = api
        client.listConversationAvatarProfiles()
        assertEquals(emptyMap(), client.conversationAvatarProfiles.value) // 已水合无覆盖 = emptyMap（非 null）
    }

    @Test
    fun facadesWithoutLoginThrow() = runTest {
        val client = newClient(backgroundScope) // 无 override 且未登录：栈查找降级抛
        assertFailsWith<IllegalStateException> { client.putConversationAvatarProfile(CONV, profile(T1)) }
        assertFailsWith<IllegalStateException> { client.deleteConversationAvatarProfile(CONV) }
        assertFailsWith<IllegalStateException> { client.listConversationAvatarProfiles() }
    }

    @Test
    fun wipeLocalDataResetsStoreToNull() = runTest {
        val client = newClient(backgroundScope)
        val api = ConvAvatarFakeApi(listResult = listOf(entry(CONV, T1, AvatarPalette.CYAN)))
        client.conversationAvatarApiOverride = api
        client.listConversationAvatarProfiles()
        assertTrue(client.conversationAvatarProfiles.value?.isNotEmpty() == true)
        client.wipeLocalData() // 登出闭环：档随账号清空（旧用户较新 updatedAt 不得把新用户拒收至其首次写）
        assertNull(client.conversationAvatarProfiles.value)
    }

    // ── fixture ──

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    /**
     * 帧面/引擎最小桩：snapshot/events 供 scheduler bootstrap（HubApnsTokenTest RecordingApi 同式
     * 占位）；其余抽象方法恒 TODO（不触达）。#28/#29/#30/#31 与 #32/#33/#34 走接口默认实现
     * （UnsupportedOperationException 纪律——存量 fake 不必逐一补 stub）。
     */
    private open class ConvAvatarStubApi : HubApi {
        override suspend fun login(req: LoginRequest) = TODO()
        override suspend fun refresh(req: RefreshRequest): RefreshOk = TODO()
        override suspend fun registerDevice(req: DeviceRegistrationRequest) = TODO()
        override suspend fun listDevices(): dev.sharpxia.agentreins.hub.auth.DevicesPage = TODO()
        override suspend fun deleteDevice(deviceId: String) = TODO()
        override suspend fun registerApnsToken(req: ApnsTokenRequest) = TODO()
        override suspend fun gateways(): dev.sharpxia.agentreins.hub.projection.GatewaysPage = TODO()
        override suspend fun createGateway(req: dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest): dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse = TODO()
        override suspend fun rotateBridgeToken(gatewayId: String): dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse = TODO()
        override suspend fun deleteGateway(gatewayId: String) = TODO()
        override suspend fun conversations(limit: Int?, cursor: String?): dev.sharpxia.agentreins.hub.projection.ConversationsPage =
            dev.sharpxia.agentreins.hub.projection.ConversationsPage(conversations = emptyList())
        override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
        override suspend fun sendMessage(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.SendMessageRequest) = TODO()
        override suspend fun uploadMedia(conversationId: String, bytes: ByteArray, mime: String, fileName: String?, width: Int?, height: Int?) = TODO()
        override suspend fun fetchMediaContent(mediaId: String) = TODO()
        override suspend fun commands(conversationId: String): dev.sharpxia.agentreins.hub.projection.CommandsList = TODO()
        override suspend fun models(conversationId: String): dev.sharpxia.agentreins.hub.projection.ModelsList = TODO()
        override suspend fun markRead(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.ReadCursorRequest) = TODO()
        override suspend fun createConversation(gatewayId: String, agentId: String, req: dev.sharpxia.agentreins.hub.projection.CreateConversationRequest) = TODO()
        override suspend fun compactConversation(conversationId: String) = TODO()
        override suspend fun agentStatus(gatewayId: String, agentId: String): dev.sharpxia.agentreins.hub.projection.AgentStatusResponse = TODO()
        override suspend fun renameConversation(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.RenameConversationRequest) = TODO()
        override suspend fun cancelRun(runId: String, req: dev.sharpxia.agentreins.hub.projection.CancelRunRequest) = TODO()
        override suspend fun decideApproval(approvalId: String, req: dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest) = TODO()
        override suspend fun snapshot(): dev.sharpxia.agentreins.hub.projection.SnapshotResponse =
            dev.sharpxia.agentreins.hub.projection.SnapshotResponse(snapshotWatermark = WM, generatedAt = T1)
        override suspend fun events(cursor: String, limit: Int?) =
            dev.sharpxia.agentreins.hub.projection.EventsPage(
                events = emptyList(), nextCursor = cursor, hasMore = false, currentWatermark = WM,
            )
    }

    /** #32/#33/#34 编排桩（门面 store 用例注入，fake 自管鉴权）。 */
    private class ConvAvatarFakeApi(
        var authority: AvatarProfile? = null,
        var listResult: List<ConversationAvatarProfileEntry> = emptyList(),
    ) : ConvAvatarStubApi() {
        var puts = 0
        var deletes = 0
        var lists = 0

        override suspend fun putConversationAvatarProfile(conversationId: String, req: AvatarProfileRequest): AvatarProfile {
            puts++
            return authority ?: error("PUT 权威档未编排")
        }

        override suspend fun deleteConversationAvatarProfile(conversationId: String) {
            deletes++
        }

        override suspend fun listConversationAvatarProfiles(): List<ConversationAvatarProfileEntry> {
            lists++
            return listResult
        }
    }

    private open class ConvAvatarStubAuth : TokenManager {
        override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
        override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
        override suspend fun restoreFromStoredRefreshToken() = false
        override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("conv-avatar-test-token")
        override fun currentAccessToken(): String? = null
        override suspend fun clearCredentials() {}
    }

    /** 内存凭据仓（避免触碰真实文件系统；GatewaySurfaceMemoryStore 同式独立命名）。 */
    private class ConvAvatarMemoryStore : CredentialStore {
        private var token: String? = null
        override suspend fun saveRefreshToken(token: String) { this.token = token }
        override suspend fun loadRefreshToken(): String? = token
        override suspend fun clear() { token = null }
    }

    /** 帧投喂假件（AvatarProfileTest FakeWs 同式，无 replay 先订阅后投喂）。 */
    private class ConvAvatarFakeWs : HubWsClient {
        private val outgoing = MutableSharedFlow<HubServerFrame>(extraBufferCapacity = 16)
        override val closed = MutableStateFlow<HubWsClosed?>(null)
        override suspend fun connect(cursor: String) {}
        override suspend fun close() {}
        override fun frames(): Flow<HubServerFrame> = outgoing

        fun emit(frame: HubServerFrame) {
            check(outgoing.tryEmit(frame)) { "fake ws emit buffer overflow" }
        }
    }

    companion object {
        private val CONVERSATION_AVATAR_MAP_SERIALIZER =
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), AvatarProfile.serializer())
        const val CONV = "01JCV000000000000000000000"
        const val CONV2 = "01JCV100000000000000000000"
        const val WM = "01JAAA0000000000000000000000"
        const val T1 = "2026-11-21T08:00:00.000Z"
        const val T2 = "2026-11-21T09:00:00.000Z"
    }
}
