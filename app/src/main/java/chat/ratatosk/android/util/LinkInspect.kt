package chat.ratatosk.android.util

/**
 * Что можно сказать о ссылке, не открывая её.
 *
 * @param host куда она ведёт на самом деле; `null` — адрес не разобрался.
 * @param scheme чем её открывать: `https`, `mailto`, `intent`…
 * @param web обычный веб-адрес (`http`/`https`).
 * @param punycode хост записан кодом (`xn--`): так пишут и настоящие
 *   нелатинские домена, и подделки под латиницу.
 * @param hasUserInfo в адресе есть часть до `@` — `https://банк.рф@чужой.сайт`
 *   ведёт на «чужой.сайт», хотя читается наоборот.
 * @param shownHost хост, который человек видит в тексте ссылки, если он
 *   есть и **не совпадает** с настоящим: подпись может быть любой.
 */
data class LinkLooks(
    val url: String,
    val host: String?,
    val scheme: String?,
    val web: Boolean,
    val punycode: Boolean,
    val hasUserInfo: Boolean,
    val shownHost: String?,
)

/**
 * Разбирает ссылку перед переходом.
 *
 * Ссылку пишет собеседник, и текст у неё может быть какой угодно:
 * `[сбербанк.рф](http://зло.example)` выглядит как банк, а ведёт
 * куда угодно. Поэтому решение принимает человек, а показать ему надо
 * не подпись, а адрес — и то, чем эта пара странна.
 */
fun inspectLink(url: String, shownText: String = ""): LinkLooks {
    val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull()
    val scheme = parsed?.scheme?.lowercase()
    // `URI` не разбирает хост у адресов вроде `http://хост_с_подчерком/`,
    // поэтому запасной путь — по самой строке.
    val host = (parsed?.host ?: hostFromString(url))?.lowercase()
    val shown = hostFromString(shownText)?.lowercase()

    return LinkLooks(
        url = url,
        host = host,
        scheme = scheme,
        web = scheme == "http" || scheme == "https",
        punycode = host?.split(".")?.any { it.startsWith("xn--") } == true,
        hasUserInfo = parsed?.userInfo != null || userInfoFromString(url),
        shownHost = shown?.takeIf { host != null && it != host },
    )
}

/** Хост из строки, даже если она не разбирается как адрес целиком. */
private fun hostFromString(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val afterScheme = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    // Часть до `@` — не хост: хост тот, что после.
    val hostPart = authority.substringAfterLast('@').substringBefore(':')
    if (hostPart.isEmpty() || !hostPart.contains('.')) return null
    return hostPart.trimEnd('.')
}

private fun userInfoFromString(url: String): Boolean {
    val afterScheme = url.trim().substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return false
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.contains('@')
}
