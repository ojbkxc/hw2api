package com.lxseek.hw2api.core.model

/**
 * 账号存储接口（core 只定义契约，实现放 app 的 AccountRepository/EncryptedSharedPreferences）。
 * map 语义多账号 + active 账号切换；UI 首版仅展示 active，数据层已兼容账号池。
 */
interface AccountStoreApi {

    /** 保存/覆盖一个账号（同 accountId 覆盖，用于续期后凭证整体回写） */
    fun save(account: Account)

    /** 按 accountId 读取；不存在返回 null */
    fun load(accountId: String): Account?

    /** 列出全部账号 */
    fun list(): List<Account>

    /** 当前 active 账号；未登录（池空或未设置）返回 null */
    fun activeAccount(): Account?

    /** 切换 active 账号；accountId 不存在返回 false */
    fun setActive(accountId: String): Boolean

    /** 删除账号；不存在返回 false。删除的是 active 时 active 置空 */
    fun delete(accountId: String): Boolean
}