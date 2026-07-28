package com.lianyu.ai.wechat.ilink

interface IlinkSessionStore {
    suspend fun getSessionAccount(): IlinkAccount?

    suspend fun saveSessionAccount(account: IlinkAccount)

    suspend fun clearSessionAccount()

    suspend fun getCursor(): String

    suspend fun saveCursor(cursor: String)

    suspend fun getContextToken(accountId: String, userId: String): String?

    suspend fun saveContextToken(accountId: String, userId: String, token: String)

    suspend fun getContextTokens(accountId: String): Map<String, String>
}