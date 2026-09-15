package chat.ratatosk.android.util

import org.ratatosk.core.FfiContact
import org.ratatosk.core.FfiTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun Long.formatDateTime(): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(this))
}

/**
 * Слышно ли контакт прямо сейчас — то есть «рядом».
 *
 * Одно правило на все списки. Раньше точка присутствия смотрела только
 * на локальную сеть, и собеседник, которого мы отлично слышим по эфиру,
 * нигде не отмечался.
 *
 * Для человека это один и тот же факт: он недалеко. Каким радиомодулем
 * его услышали — вопрос не списка чатов; ступени целиком показывает
 * карточка контакта.
 *
 * `seenOnLan` — это, по словам ядра, тот же `addressable` у ступени LAN,
 * оставленный ярлыком ради самого частого вопроса списка. Для эфира
 * такого ярлыка нет, поэтому его ступень ищем в `reachability`.
 *
 * **Не то же, что «есть связь»:** маяк говорит «устройство в эфире»,
 * а установлена ли сессия — отвечает `directChannel`.
 */
val FfiContact.nearby: Boolean
    get() = seenOnLan || reachability.rungs.any { rung ->
        rung.transport == FfiTransport.BT && rung.addressable
    }
