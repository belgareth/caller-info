package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity

data class HistoryFilter(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val recentOnly: Boolean = false
)

fun filterHistory(
    items: List<CallerInfoEntity>,
    filter: HistoryFilter,
    nowMillis: Long,
    recentWindowMillis: Long = 30L * 24 * 60 * 60 * 1000,
    normalizeNumber: (String) -> String = { "" }
): List<CallerInfoEntity> {
    val needle = filter.query.trim().lowercase()
    val normalizedNeedle = filter.query.trim()
        .takeIf(String::isNotBlank)
        ?.let(normalizeNumber)
        ?.takeIf(String::isNotBlank)
    return items.filter { item ->
        val matchesNormalizedNumber = normalizedNeedle != null &&
            normalizeNumber(item.number) == normalizedNeedle
        val matchesText = needle.isBlank() || matchesNormalizedNumber ||
            listOfNotNull(item.number, item.userAlias, item.name, item.carrier, item.country)
                .any { it.lowercase().contains(needle) }
        val matchesFavorite = !filter.favoritesOnly || item.favorite
        val matchesRecent = !filter.recentOnly || nowMillis - item.timestamp <= recentWindowMillis
        matchesText && matchesFavorite && matchesRecent
    }.sortedWith(compareByDescending<CallerInfoEntity> { it.favorite }.thenByDescending { it.timestamp })
}

fun CallerInfoEntity.displayName(): String? = userAlias?.takeIf(String::isNotBlank) ?: name
