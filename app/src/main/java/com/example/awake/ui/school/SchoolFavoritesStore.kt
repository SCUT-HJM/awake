package com.example.awake.ui.school

import android.content.Context

/** 用 SharedPreferences 保存学校收藏，保持简单、无新依赖。 */
class SchoolFavoritesStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("school_favorites", Context.MODE_PRIVATE)

    fun read(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun toggle(code: String) {
        val current = read().toMutableSet()
        if (!current.add(code)) {
            current.remove(code)
        }
        prefs.edit().putStringSet(KEY, current).apply()
    }

    private companion object {
        const val KEY = "favorite_school_codes"
    }
}
