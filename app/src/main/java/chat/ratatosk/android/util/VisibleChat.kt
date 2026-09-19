package chat.ratatosk.android.util

/**
 * Какой чат человек видит прямо сейчас.
 *
 * Живёт в процессе, а не в экране и не в сервисе: знает об этом экран,
 * а нужно это сервису уведомлений, и между ними нет общей модели. Оба
 * пересоздаются независимо, поэтому признак хранится здесь.
 *
 * «Видит» — это два условия сразу: чат открыт и приложение на переднем плане.
 * Свёрнутое окно с открытым чатом — не «видит», и уведомление там нужно.
 */
object VisibleChat {
    @Volatile
    private var chatIdHex: String? = null

    @Volatile
    private var foreground: Boolean = false

    fun setOpenChat(hex: String?) {
        chatIdHex = hex
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    /** Чат открыт и виден — уведомлять о нём незачем. */
    fun isVisible(hex: String): Boolean = foreground && chatIdHex == hex

    fun clear() {
        chatIdHex = null
        foreground = false
    }
}
