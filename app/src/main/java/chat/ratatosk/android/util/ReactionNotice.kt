package chat.ratatosk.android.util

import org.ratatosk.core.FfiMessage
import org.ratatosk.core.FfiReaction

/**
 * О какой реакции стоит сказать человеку — и стоит ли вообще.
 *
 * Вынесено из сервиса отдельной чистой функцией нарочно: живьём реакцию
 * не воспроизвести без второго устройства, а решение здесь неочевидное
 * и состоит из трёх отказов.
 *
 * Событие ядра `ReactionChanged` одно на постановку и на снятие, и смайлика
 * в нём нет — есть только чат, сообщение и автор. Поэтому смотреть
 * приходится в само сообщение.
 *
 * @return реакцию, о которой надо уведомить, или null — молчать.
 */
fun reactionToAnnounce(msg: FfiMessage, authorIk: ByteArray): FfiReaction? {
    // На чужое сообщение реакция человека не касается: в группе на десять
    // участников это уведомление на каждый смайлик каждого.
    if (!msg.mine) return null

    // Реакции этого автора в сообщении нет — значит её сняли.
    // О снятии не уведомляем.
    val reaction = msg.reactions.firstOrNull { it.authorIk.contentEquals(authorIk) } ?: return null

    // Своя реакция приезжает тем же событием; уведомлять себя о себе незачем.
    if (reaction.mine) return null

    return reaction
}
