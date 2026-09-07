package com.example.awake.data.remote

/**
 * 学校选择页使用的目录项。
 *
 * 未来新增学校时补充目录项，同时实现对应的课表 Provider 并注册到 AppContainer。
 */
data class SchoolCatalogEntry(
    val code: String,
    val displayName: String,
    val systemType: String,
    val initial: Char
)

object SchoolCatalog {
    val all = listOf(
        SchoolCatalogEntry("SCUT", "华南理工大学", "正方教务", 'H'),
        SchoolCatalogEntry("JNU", "暨南大学", "金智教务", 'J')
    )

    fun byCode(code: String): SchoolCatalogEntry? = all.firstOrNull { it.code == code }
}
