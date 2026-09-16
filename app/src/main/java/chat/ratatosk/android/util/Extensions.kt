package chat.ratatosk.android.util

import androidx.annotation.StringRes
import chat.ratatosk.android.R
import org.ratatosk.core.FfiContact
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
 * Ядро держит два признака раздельно нарочно: эфира два, и гаснут они
 * порознь — Bluetooth выключили, а Wi-Fi остался, и наоборот; собеседник
 * бывает слышен в эфире и невидим в сети (разные точки доступа, гостевая
 * сеть с изоляцией). Клиенту оно прямо разрешает свести их в один значок,
 * если различие не нужно, — нам не нужно: для человека это один факт,
 * собеседник недалеко. Различать стоит там, где он выбирает эфир руками;
 * ступени по отдельности показывает карточка контакта.
 *
 * **У признака есть срок — полторы минуты.** Снимается он не сам собой
 * в нашем коде, а ядром, и приезжает это обычным `ContactChanged`, по
 * которому список контактов перечитывается целиком. Поэтому здесь нет
 * ни таймеров, ни сравнения времени: держать свой срок рядом с чужим
 * значило бы однажды разойтись с ним.
 *
 * **Не то же, что «есть связь»:** между «слышно» и «кадры пойдут»
 * рукопожатие, и отвечает за него `directChannel`.
 */
val FfiContact.nearby: Boolean
    get() = seenOnLan || seenOnBt

/**
 * Чем именно слышно контакт — подпись рядом с точкой присутствия.
 *
 * Показывать «В сети (LAN)» на контакте, услышанном по эфиру, нельзя:
 * человек видит выключенный Wi-Fi и справедливо считает это ошибкой.
 * А различать здесь и стоит — карточка контакта и шапка чата это как раз
 * те места, где разбираются, почему доходит или не доходит.
 *
 * Сам значок при этом остаётся один: [nearby] отвечает на вопрос «рядом
 * ли», а это — на вопрос «чем слышно».
 */
@get:StringRes
val FfiContact.nearbyLabelRes: Int
    get() = when {
        seenOnLan && seenOnBt -> R.string.online_lan_bt
        seenOnBt -> R.string.online_bt
        else -> R.string.online_lan
    }
