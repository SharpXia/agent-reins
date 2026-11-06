package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #16 主线锚定（v1.1 additive，2026-11-06）请求体线格式用例：CreateConversationRequest.mainSession
 * 的序列化口径（HubJson：encodeDefaults=false + explicitNulls=false）——
 * ① 显式 true（DefaultCommandClient.createConversation 固定携带）必编码：请求体含 "mainSession":true，
 *    服务端据此组装 sessionKey=agent:<agentId>:main（客户端永不拼键，ADR-14）；
 * ② label 与 mainSession 组合编码正确；
 * ③ 缺省（null）不编码：请求体不含 mainSession 键 = 现状 dashboard 键行为不受影响。
 */
class ConversationCreateMainSessionTest {

    @Test
    fun mainSessionTrueIsEncoded() {
        assertEquals(
            """{"mainSession":true}""",
            HubJson.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest(mainSession = true)),
        )
    }

    @Test
    fun labelAndMainSessionEncodeTogether() {
        assertEquals(
            """{"label":"新会话","mainSession":true}""",
            HubJson.encodeToString(
                CreateConversationRequest.serializer(),
                CreateConversationRequest(label = "新会话", mainSession = true),
            ),
        )
    }

    @Test
    fun mainSessionAbsentByDefault() {
        // 缺省（不带字段）= null 不编码：与既有 0.5.x 线格式完全一致（契约 additive）
        assertEquals("{}", HubJson.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest()))
        assertEquals(
            """{"label":"重构讨论"}""",
            HubJson.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest(label = "重构讨论")),
        )
    }
}
