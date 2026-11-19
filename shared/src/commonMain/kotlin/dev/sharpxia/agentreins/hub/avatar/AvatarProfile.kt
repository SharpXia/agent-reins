package dev.sharpxia.agentreins.hub.avatar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 用户级机器人形象档——契约 v1.5.0（`AgentReinService/api/hub-api-v1.yaml` 端点 #30/#31
 * /v1/me/avatar-profile + AvatarProfile components；冻结 tag hub-api-v1.5.0）。
 *
 * 契约形状（AvatarProfile schema，四字段全 required）：
 * - faceFactors：12 控制点半径系数，逐值 ∈ [0.70,1.10]（服务端逐字段校验越界/长度≠12/非数
 *   → 400 HUB-BIZ-004；客户端**显示层**宽松解析，见 [AvatarProfile.sanitized]）。
 * - paletteId：十色闭集（声明序 = 跨端契约，[AvatarPalette] 照抄 iOS AgentTintName 序）。
 * - motionLevel：{off, standard}（`lively` 为后续 additive 保留位，不入本轮契约）。
 * - updatedAt：Rfc3339Millis 服务端权威时间戳——epoch 哨兵 = 从未设置；last-write-wins
 *   收敛比较键（大者胜，同值幂等跳过防广播回环）。
 *
 * 序列化 parity：JSON number 不保尾零（1.0≠1.00 表达差），**缓存与收敛比较一律用规范化
 * face 串**（[encodeFaceFactors] 同形 59 字符 = iOS ClayFace.encode 逐字口径）而非原始数组。
 */
@Serializable
data class AvatarProfile(
    @Serializable(with = FaceFactorsSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val faceFactors: List<Double>,
    @Serializable(with = AvatarPaletteSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val paletteId: AvatarPalette = AvatarPalette.GRAY,
    @Serializable(with = AvatarMotionSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val motionLevel: AvatarMotion = AvatarMotion.STANDARD,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val updatedAt: String = UPDATED_AT_EPOCH,
) {
    /** 规范化 face 串（encode 同形）：收敛比较与平台缓存键（原始数组不保尾零不可比）。 */
    val faceKey: String get() = encodeFaceFactors(faceFactors)

    /**
     * 收侧宽松规范化（显示层兜底，服务端已守门）：长度 ≠ 12 或任一项越界 [0.70,1.10] /
     * 非有限数 → 整体回退正圆（iOS ClayFace.parse 失败回退正圆同纪律，设计稿 §dataModel 规则 4）。
     */
    fun sanitized(): AvatarProfile {
        val ok = faceFactors.size == FACE_POINT_COUNT && faceFactors.all { it.isFinite() && it in FACE_MIN..FACE_MAX }
        return if (ok) this else copy(faceFactors = DEFAULT_FACE_FACTORS)
    }
}

/** 形象档保存请求体（端点 #31）：三可编辑字段全量覆盖提交——updatedAt 不入请求（服务端权威设定）。 */
@Serializable
data class AvatarProfileRequest(
    @Serializable(with = FaceFactorsSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val faceFactors: List<Double>,
    @Serializable(with = AvatarPaletteSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val paletteId: AvatarPalette,
    @Serializable(with = AvatarMotionSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val motionLevel: AvatarMotion,
) {
    constructor(profile: AvatarProfile) : this(profile.faceFactors, profile.paletteId, profile.motionLevel)
}

/**
 * 配色闭集（十色，声明序 = 跨端契约）：与 iOS `AgentTintName`（InkTheme.swift:206-208）
 * 一字对齐——Kotlin 重建时照抄顺序（avatar-spec §1）。
 */
@Serializable(with = AvatarPaletteSerializer::class)
enum class AvatarPalette(val wire: String) {
    GRAY("GRAY"),
    BLUE("BLUE"),
    RED("RED"),
    ORANGE("ORANGE"),
    YELLOW("YELLOW"),
    GREEN("GREEN"),
    CYAN("CYAN"),
    PURPLE("PURPLE"),
    MAGENTA("MAGENTA"),
    BROWN("BROWN"),
    ;

    companion object {
        /** 宽松解析：未知取值回落 GRAY（缺省档色；设计稿 §dataModel 规则 4）。 */
        fun lenient(raw: String?): AvatarPalette =
            entries.firstOrNull { it.wire == raw } ?: GRAY
    }
}

/** 动效档闭集：off = 静止（仅入场淡入 + 表情即时切换），standard = +待机呼吸+过渡动画。 */
@Serializable(with = AvatarMotionSerializer::class)
enum class AvatarMotion(val wire: String) {
    OFF("off"),
    STANDARD("standard"),
    ;

    companion object {
        /** 宽松解析：未知取值回落 standard（缺省档动效；设计稿 §dataModel 规则 4）。 */
        fun lenient(raw: String?): AvatarMotion =
            entries.firstOrNull { it.wire == raw } ?: STANDARD
    }
}

/** 十色枚举序列化：未知字符串收侧回落 GRAY（宽松解析义务，08 §6.1 客户端义务）。 */
object AvatarPaletteSerializer : KSerializer<AvatarPalette> {
    override val descriptor = PrimitiveSerialDescriptor("AvatarPalette", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): AvatarPalette = AvatarPalette.lenient(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: AvatarPalette) = encoder.encodeString(value.wire)
}

/** 动效档枚举序列化：未知字符串收侧回落 standard（同上宽松纪律）。 */
object AvatarMotionSerializer : KSerializer<AvatarMotion> {
    override val descriptor = PrimitiveSerialDescriptor("AvatarMotion", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): AvatarMotion = AvatarMotion.lenient(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: AvatarMotion) = encoder.encodeString(value.wire)
}

/**
 * faceFactors 序列化（收侧宽松解析）：数组缺失/长度 ≠ 12/任一项越界 [0.70,1.10] 或非有限数
 * → 回退正圆 12×1.00（显示层兜底——服务端守门 400 不经此处）。encode 侧恒出原始数组
 * （两位小数规范化由 [encodeFaceFactors] 串面承担，PUT 请求体用 [AvatarProfileRequest]）。
 */
object FaceFactorsSerializer : KSerializer<List<Double>> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<Double> {
        val raw = decoder.decodeSerializableValue(delegate)
        val ok = raw.size == FACE_POINT_COUNT && raw.all { it.isFinite() && it in FACE_MIN..FACE_MAX }
        return if (ok) raw else DEFAULT_FACE_FACTORS
    }
    override fun serialize(encoder: Encoder, value: List<Double>) =
        encoder.encodeSerializableValue(delegate, value)
}

// ── ClayFace 几何常量与 parse/encode（iOS RobotAvatar.swift:41-91 逐字镜像；face-sculpt §2/§4） ──

/** 12 控制点数 / 基准半径（100×100 坐标系，圆心 (50,50)）/ 系数上下限。 */
const val FACE_POINT_COUNT = 12
const val FACE_BASE_RADIUS = 44.0
const val FACE_MIN = 0.70
const val FACE_MAX = 1.10

/** 正圆系数表（全 1.00）：face 串空/解析失败/收侧越界的唯一兜底 = 默认档造型。 */
val DEFAULT_FACE_FACTORS: List<Double> = List(FACE_POINT_COUNT) { 1.0 }

/**
 * 默认档常量（双端同源）：正圆 + GRAY + standard + epoch 哨兵（资源恒存在——GET 未设置
 * 返回同形状，updatedAt=1970-01-01T00:00:00.000Z 表示从未设置；契约 AvatarProfile schema）。
 */
val DEFAULT_AVATAR_PROFILE = AvatarProfile(
    faceFactors = DEFAULT_FACE_FACTORS,
    paletteId = AvatarPalette.GRAY,
    motionLevel = AvatarMotion.STANDARD,
    updatedAt = UPDATED_AT_EPOCH,
)

/** epoch 哨兵（Rfc3339Millis）：从未设置语义；last-write-wins 比较中恒最小。 */
const val UPDATED_AT_EPOCH = "1970-01-01T00:00:00.000Z"

fun clampFaceFactor(f: Double): Double = f.coerceIn(FACE_MIN, FACE_MAX)

/**
 * 控制点坐标（face-sculpt §2 键形，iOS ClayFace.point 逐字一致）：
 * x = 50 + 44·f·sin(θ_i)、y = 50 − 44·f·cos(θ_i)（θ_i = i×30°，0 号在 12 点方向、顺时针）。
 * 返回 (x, y) 于 100×100 逻辑坐标系；渲染侧自乘 scale。
 */
fun facePoint(i: Int, f: Double): Pair<Double, Double> {
    val theta = (i % FACE_POINT_COUNT) * (2.0 * PI / FACE_POINT_COUNT)
    val radius = FACE_BASE_RADIUS * clampFaceFactor(f)
    return 50.0 + radius * sin(theta) to 50.0 - radius * cos(theta)
}

/**
 * 解析 face 串（iOS ClayFace.parse 同纪律）：12 段逗号分隔小数，逐值 clamp 到 [0.70,1.10]；
 * 段数 ≠ 12 或任一段非有限数 → null（调用方按正圆处理）。
 */
fun parseFaceFactors(raw: String): List<Double>? {
    val parts = raw.split(",")
    if (parts.size != FACE_POINT_COUNT) return null
    val out = ArrayList<Double>(FACE_POINT_COUNT)
    for (p in parts) {
        val v = p.trim().toDoubleOrNull() ?: return null
        if (!v.isFinite()) return null
        out.add(clampFaceFactor(v))
    }
    return out
}

/**
 * 编码 face 串（iOS ClayFace.encode 逐字口径）：恒 12 段、各两位小数（如 `1.00,0.92,…`，
 * 12×4−1=59 字符）——先 clamp 后 ×100 取整限 [70,110]，两位补零拼小数，不用 %-format
 * 防本地化污染。长度 ≠ 12 的入参按正圆补齐/截断到 12（调用面保证恒 12，防御不炸）。
 */
fun encodeFaceFactors(fs: List<Double>): String {
    val cents = (0..<FACE_POINT_COUNT).map { i ->
        val v = clampFaceFactor(if (i < fs.size) fs[i] else 1.0)
        // Swift (v*100).rounded() = 最近舍入（正值域同 half-up）；round 而非 truncate 防浮点尾差
        round(v * 100).toInt().coerceIn(70, 110)
    }
    return cents.joinToString(",") { c ->
        val frac = (c % 100).toString().padStart(2, '0')
        if (c < 100) "0.$frac" else "1.$frac"
    }
}

// ── 收敛规则（last-write-wins，updatedAt 大者胜） ──

/**
 * 形象档收敛（设计稿 §syncMechanism 读路径②）：[incoming] updatedAt 严格大于 [current]
 * 才接受——同值幂等跳过（发起设备自收 avatar.updated 帧因 updatedAt 与 PUT 响应相等而
 * 幂等跳过，防广播回环双保险之一）；epoch 哨兵恒最小。Rfc3339Millis 定长同格式串字典序
 * 即时序；漂移形态回退 Instant 解析，再失败回退字典序（宽松纪律，不炸）。
 */
fun convergeAvatarProfile(current: AvatarProfile?, incoming: AvatarProfile?): AvatarProfile? {
    if (incoming == null) return current
    if (current == null) return incoming
    return if (avatarUpdatedAtNewer(incoming.updatedAt, current.updatedAt)) incoming else current
}

/** updatedAt 新旧判定：incoming 严格新于 current → true（同值 false = 幂等跳过）。 */
fun avatarUpdatedAtNewer(incoming: String, current: String): Boolean {
    if (incoming == current) return false
    val a = runCatching { kotlin.time.Instant.parse(incoming) }.getOrNull()
    val b = runCatching { kotlin.time.Instant.parse(current) }.getOrNull()
    if (a != null && b != null) return a > b
    return incoming > current
}

// ── JSON 便捷面（平台缓存/桥面交付：iOS 桥以 JSON 串跨边界，DTO 纪律） ──

/** 形象档 JSON 编解码器（HubJson 同族配置：ignoreUnknownKeys、未知字段忽略）。 */
val AvatarProfileJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** 编码为 JSON 串（平台缓存/桥面交付形态；@EncodeDefault(Mode.ALWAYS) 保证四字段恒在）。 */
fun AvatarProfile.encodeJson(): String = AvatarProfileJson.encodeToString(AvatarProfile.serializer(), this)

/** 宽松解析 JSON 串 → 形象档（失败/非 object → null，调用方回默认档；收侧 sanitized 兜底）。 */
fun parseAvatarProfileJson(raw: String?): AvatarProfile? =
    raw?.let { json ->
        runCatching { AvatarProfileJson.decodeFromString(AvatarProfile.serializer(), json).sanitized() }.getOrNull()
    }

/**
 * WSS 控制帧 `avatar.updated` 载荷抽取（v1.5.0，hub-wss-frames-v1 同日注册）：帧形
 * `{"type":"avatar.updated","data":{"profile":<AvatarProfile>}}`——非事件域（不入事件类型
 * 闭集/eventlog/seq/投影）。宽松抽取：形状漂移（缺 data.profile）→ null（静默忽略，不断连）。
 */
fun parseAvatarUpdatedFrame(obj: JsonObject): AvatarProfile? {
    val data = obj["data"] as? JsonObject ?: return null
    val profile = data["profile"] as? JsonObject ?: return null
    return runCatching {
        AvatarProfileJson.decodeFromJsonElement(AvatarProfile.serializer(), profile).sanitized()
    }.getOrNull()
}
