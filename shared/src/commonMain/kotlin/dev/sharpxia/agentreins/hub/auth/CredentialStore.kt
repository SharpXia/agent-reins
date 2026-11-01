package dev.sharpxia.agentreins.hub.auth

/**
 * refreshToken 安全存储（P1 落三端实现并 expect/actual 化：iOS=Keychain、Android=Keystore、
 * Desktop=~/.agentreins 文件 chmod 600）。accessToken 永不经此存储——仅内存（08 §1.1/§2.1）。
 */
interface CredentialStore {
    suspend fun saveRefreshToken(token: String)

    suspend fun loadRefreshToken(): String?

    suspend fun clear()
}
