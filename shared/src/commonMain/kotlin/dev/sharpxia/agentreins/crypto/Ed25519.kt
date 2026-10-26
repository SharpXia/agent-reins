package dev.sharpxia.agentreins.crypto

/**
 * 纯 Kotlin Ed25519（RFC 8032）——只实现设备签名所需：seed → publicKey、sign。
 *
 * field 用 radix-2^51（LongArray(5)，ref10 风格）实现；全部曲线常数运行时派生
 * （d = -121665·inv(121666)、y_B = 4·inv(5)、x_B 走 RFC 恢复公式），不硬编码大常数。
 * 选择纯 Kotlin：Kotlin/Native 无法 import Swift-only 的 CryptoKit，Security framework
 * 无公开 Ed25519 API，第三方 KMP 库需捆绑 OpenSSL。正确性由 RFC 8032 测试向量保证。
 *
 * scalarmult 为非常数时间 double-and-add：M1 场景（本机私钥、单次签名）可接受，
 * v1.0 打磨时评估替换（技术债登记于 DEVELOPMENT_PLAN）。
 */
internal object Ed25519 {

    private const val MASK27: Long = (1L shl 27) - 1
    private const val LIMBS = 10

    // 2^(27·10) mod p = 2^270 ≡ 19·2^15 = 622592：10-limb radix-2^27 的高段折回系数
    private const val FOLD: Long = 622592L

    /** 大端 hex（64 字符）转 32B 小端。指数常数按位扫描用。 */
    private fun String.hexLE(): ByteArray {
        val be = ByteArray(length / 2) {
            ((hexDigit(this@hexLE[2 * it]) shl 4) + hexDigit(this@hexLE[2 * it + 1])).toByte()
        }
        return ByteArray(32) { be[31 - it] }
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex char: $c")
    }

    // p = 2^255-19；三个指数（小端 32B）
    private val P_LE = ("7" + "f".repeat(61) + "ed").hexLE()
    private val P_MINUS_2 = ("7" + "f".repeat(61) + "eb").hexLE()
    private val P_MINUS_5_DIV_8 = ("0" + "f".repeat(62) + "d").hexLE() // 2^252-3
    private val P_MINUS_1_DIV_4 = ("1" + "f".repeat(62) + "b").hexLE() // 2^253-5

    private val L = byteArrayOf(
        0xed.toByte(), 0xd3.toByte(), 0xf5.toByte(), 0x5c.toByte(), 0x1a, 0x63, 0x12, 0x58,
        0xd6.toByte(), 0x9c.toByte(), 0xf7.toByte(), 0xa2.toByte(), 0xde.toByte(), 0xf9.toByte(), 0xde.toByte(), 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )

    private fun bit(bytes: ByteArray, i: Int): Int = (bytes[i ushr 3].toInt() shr (i and 7)) and 1

    // ---- field：LongArray(5) radix 2^51 ----

    private fun fe(): LongArray = LongArray(LIMBS)
    private fun feOne(): LongArray = LongArray(LIMBS).also { it[0] = 1 }
    private fun feSmall(n: Long): LongArray = LongArray(LIMBS).also { it[0] = n }

    /** 规范化：各 limb 收敛到 [0, 2^27)，溢出按 2^255≡19 折回（所有步骤为模 p 同余变换）。 */
    private fun feCarry(a: LongArray): LongArray {
        val r = a.copyOf()
        for (pass in 0 until 2) {
            var carry = 0L
            for (i in 0 until LIMBS - 1) {
                val t = r[i] + carry
                r[i] = t and MASK27
                carry = t shr 27
            }
            var top = r[LIMBS - 1] + carry
            r[LIMBS - 1] = top and MASK27
            top = top shr 27
            r[0] += top * FOLD
        }
        var c = r[0] shr 27
        if (c != 0L) {
            r[0] = r[0] and MASK27
            for (i in 1 until LIMBS) {
                if (c == 0L) break
                val t = r[i] + c
                r[i] = t and MASK27
                c = t shr 27
            }
            r[0] += c * FOLD
            r[0] = r[0] and MASK27
        }
        return r
    }

    private fun feAdd(a: LongArray, b: LongArray): LongArray = LongArray(LIMBS) { a[it] + b[it] }

    private fun feSub(a: LongArray, b: LongArray): LongArray = LongArray(LIMBS) { a[it] - b[it] }

    private fun feNeg(a: LongArray): LongArray = feSub(fe(), a)

    /**
     * 卷积 t[k]=Σa[i]b[j]（k≤18）→ 27 位进位链压幅值 → 高段 t[10..18] 按 2^270≡FOLD
     * 逐项折回 t[0..8]（t[k]·2^(27k) ≡ t[k]·FOLD·2^(27(k-10)))，再常规 carry。
     * 幅值：卷积项 < 2^56、进位后 < 2^28、折叠项 < 2^28·FOLD < 2^47，全程 Long 安全。
     */
    private fun feMul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(19)
        for (i in 0 until LIMBS) {
            val ai = a[i]
            if (ai == 0L) continue
            for (j in 0 until LIMBS) t[i + j] += ai * b[j]
        }
        // 27 位进位链贯穿 t[0..18]，把每项压到 < 2^27；
        // t[18] 卷积可达 2^57，若不归一直接 ×FOLD 会溢出 Long（实测踩坑）
        var c = 0L
        for (i in 0 until 19) {
            val v = t[i] + c
            t[i] = v and MASK27
            c = v shr 27
        }
        // 链尾进位权重 2^(27·19) = 2^513 ≡ 19²·2³ = 2888 (mod p)
        t[0] += c * 2888
        for (k in 18 downTo LIMBS) {
            val v = t[k]
            if (v == 0L) continue
            t[k - LIMBS] += v * FOLD
        }
        return feCarry(t.copyOf(LIMBS))
    }

    /** base^exp（exp 小端 32B，按位 square-and-multiply；非常数时间）。 */
    private fun fePow(base: LongArray, exp: ByteArray): LongArray {
        var r = feOne()
        for (i in 254 downTo 0) {
            r = feMul(r, r)
            if (bit(exp, i) == 1) r = feMul(r, base)
        }
        return r
    }

    private fun feInvert(a: LongArray): LongArray = fePow(a, P_MINUS_2)

    /** field → 小端 32B（规范非负，条件减 p）。 */
    private fun feToBytes(a0: LongArray): ByteArray {
        val a = feCarry(a0)
        // radix-2^27×10 的值可达 2^270：先取低 256 位，溢出段（bit256..269）按 2^256≡38 折回，
        // 再条件减 p 至多三次（lo<2^256 ≤ 2p+37，加 38·extra<2^20 后仍 <3p）——彻底归约到 [0,p)。
        // （此前版本静默丢弃 bit256..269，是所有向量失败的根因）
        val out = ByteArray(32)
        for (i in 0 until LIMBS) {
            for (j in 0 until 27) {
                val idx = i * 27 + j
                if (idx < 256 && (a[i] shr j) and 1L == 1L) {
                    out[idx ushr 3] = (out[idx ushr 3].toInt() or (1 shl (idx and 7))).toByte()
                }
            }
        }
        val extra = (a[LIMBS - 1] ushr 13) // bit256..269，< 2^14
        if (extra != 0L) addSmallInPlace(out, extra * 38)
        repeat(3) { if (geq(out, P_LE)) subInPlace(out, P_LE) }
        return out
    }

    /** out += v（v < 2^48），进位向高位传播。 */
    private fun addSmallInPlace(a: ByteArray, v: Long) {
        var carry = v
        for (i in 0 until 32) {
            if (carry == 0L) break
            val t = (a[i].toInt() and 0xff) + (carry and 0xff)
            a[i] = (t and 0xff).toByte()
            carry = (carry shr 8) + (t shr 8)
        }
    }

    private fun geq(a: ByteArray, b: ByteArray): Boolean {
        for (i in 31 downTo 0) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x > y
        }
        return true
    }

    private fun subInPlace(a: ByteArray, b: ByteArray) {
        var borrow = 0
        for (i in 0 until 32) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff) - borrow
            borrow = if (d < 0) 1 else 0
            a[i] = (d + borrow * 256).toByte()
        }
    }

    private fun bytesEq(a: ByteArray, b: ByteArray): Boolean = a.contentEquals(b)

    // ---- 点运算：扩展坐标 (X:Y:Z:T=XY/Z)；d 非平方 → 统一公式完备（P=Q 给 2P） ----

    private class Point(val x: LongArray, val y: LongArray, val z: LongArray, val t: LongArray)

    private val curveD: LongArray by lazy { feMul(feNeg(feSmall(121665)), feInvert(feSmall(121666))) }
    private val curveD2: LongArray by lazy { feAdd(curveD, curveD) }

    private fun neutral(): Point = Point(fe(), feOne(), feOne(), fe())

    private fun addPoints(p: Point, q: Point): Point {
        val a = feMul(feSub(p.y, p.x), feSub(q.y, q.x))
        val b = feMul(feAdd(p.y, p.x), feAdd(q.y, q.x))
        val c = feMul(feMul(curveD2, p.t), q.t)
        val d = feAdd(feMul(p.z, q.z), feMul(p.z, q.z))
        val e = feSub(b, a)
        val f = feSub(d, c)
        val g = feAdd(d, c)
        val h = feAdd(b, a)
        return Point(feMul(e, f), feMul(h, g), feMul(g, f), feMul(e, h))
    }

    private fun encodePoint(p: Point): ByteArray {
        val zi = feInvert(p.z)
        val x = feMul(p.x, zi)
        val y = feMul(p.y, zi)
        val out = feToBytes(y)
        if (feToBytes(x)[0].toInt() and 1 == 1) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    private fun scalarmult(q: Point, s: ByteArray): Point {
        var p = neutral()
        for (i in 255 downTo 0) {
            p = addPoints(p, p)
            if (bit(s, i) == 1) p = addPoints(p, q)
        }
        return p
    }

    // ---- 基点：y = 4/5，x 由 RFC 8032 §5.1.3 恢复公式派生（sign=0 取偶 x） ----

    private val basePoint: Point by lazy {
        val y = feMul(feSmall(4), feInvert(feSmall(5)))
        val yy = feMul(y, y)
        val u = feSub(yy, feOne())
        val v = feAdd(feMul(curveD, yy), feOne())
        val v3 = feMul(feMul(v, v), v)
        val uv7 = feMul(u, feMul(feMul(v3, v3), v))
        var x = feMul(feMul(u, v3), fePow(uv7, P_MINUS_5_DIV_8))
        val vxx = feMul(v, feMul(x, x))
        val uCan = feCarry(u)
        val negUCan = feCarry(feNeg(u))
        when {
            bytesEq(feToBytes(vxx), feToBytes(uCan)) -> Unit
            bytesEq(feToBytes(vxx), feToBytes(negUCan)) -> x = feMul(x, fePow(feSmall(2), P_MINUS_1_DIV_4))
            else -> error("base point recovery failed")
        }
        require(!bytesEq(feToBytes(x), ByteArray(32))) { "recovered x is zero" }
        if (feToBytes(x)[0].toInt() and 1 == 1) x = feNeg(x)
        Point(feCarry(x), feCarry(y), feOne(), feMul(feCarry(x), feCarry(y)))
    }

    // ---- 标量 mod L（512-bit 逐位取模：rem = rem·2 | bit，≥L 则减 L；自证正确，非常数时间） ----

    private fun scalarModL(x: ByteArray): ByteArray {
        require(x.size == 64)
        val rem = ByteArray(32)
        for (i in 511 downTo 0) {
            shiftLeft1(rem)
            rem[0] = (rem[0].toInt() or bit(x, i)).toByte()
            if (geq(rem, L)) subInPlace(rem, L)
        }
        return rem
    }

    private fun shiftLeft1(a: ByteArray) {
        var carry = 0
        for (i in 0 until 32) {
            val v = a[i].toInt() and 0xff
            a[i] = ((v shl 1) or carry).toByte()
            carry = v shr 7
        }
    }

    private fun reduceScalar(hash: ByteArray): ByteArray = scalarModL(hash)

    // ---- 公开 API ----

    /** 由 32B seed 派生 32B 公钥（raw）。 */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val a = clamp(sha512(seed).copyOf(32))
        return encodePoint(scalarmult(basePoint, a))
    }

    /** Ed25519 签名（RFC 8032 PureEdDSA）：64B sig = R || S。 */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val pub = publicKey(seed)
        val h = sha512(seed)
        val a = clamp(h.copyOf(32))
        val prefix = h.copyOfRange(32, 64)
        val r = reduceScalar(sha512(prefix + message))
        val bigR = encodePoint(scalarmult(basePoint, r))
        val k = reduceScalar(sha512(bigR + pub + message))
        // S = (r + k·a) mod L：schoolbook 乘加（k·a < 2^504，加 r 后 < 2^505 < 2^512）
        val acc = LongArray(64)
        for (i in 0 until 32) {
            val ki = k[i].toLong() and 0xff
            if (ki == 0L) continue
            for (j in 0 until 32) acc[i + j] += ki * (a[j].toLong() and 0xff)
        }
        val xb = ByteArray(64)
        var carry = 0L
        for (i in 0 until 64) {
            val t = acc[i] + carry + (if (i < 32) r[i].toLong() and 0xff else 0L)
            xb[i] = (t and 0xff).toByte()
            carry = t shr 8
        }
        check(carry == 0L)
        return bigR + scalarModL(xb)
    }

    private fun clamp(h: ByteArray): ByteArray = h.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }
}
