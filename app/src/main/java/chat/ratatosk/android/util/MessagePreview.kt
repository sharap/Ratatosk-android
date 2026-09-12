package chat.ratatosk.android.util

import android.content.Context
import chat.ratatosk.android.R

/**
 * Одна строка, которой сообщение представляется вне переписки:
 * в уведомлении и в списке чатов.
 *
 * Одно место на оба, и это не экономия: подпись «[файл]» в уведомлении
 * и пустая строка в списке чатов — разные ответы на один вопрос, и
 * расходиться им незачем.
 *
 * Сообщение без текста раньше показывалось пустым. Пустая строка в списке
 * чатов читается как поломка, а в уведомлении — как сообщение без
 * содержимого, хотя на деле там вложение или карточка контакта.
 */
object MessagePreview {

    /**
     * @param body текст сообщения, как есть, с разметкой.
     * @param fileNames имена вложений — по ним определяется вид.
     * @param hasSharedContact приложена ли карточка контакта.
     */
    fun of(
        context: Context,
        body: String,
        fileNames: List<String>,
        hasSharedContact: Boolean
    ): String {
        val text = MarkdownUtils.toPlainText(body, context.getString(R.string.spoiler))
        if (text.isNotBlank()) return text

        // Текста нет — значит сообщение и есть вложение или карточка.
        if (hasSharedContact) return context.getString(R.string.preview_contact)

        if (fileNames.size == 1) {
            val name = fileNames.first()
            val label = when {
                FileUtils.isImage(name) -> R.string.preview_photo
                FileUtils.isVideo(name) -> R.string.preview_video
                FileUtils.isAudio(name) -> R.string.preview_audio
                else -> R.string.preview_file
            }
            return context.getString(label)
        }
        if (fileNames.size > 1) {
            // Через plurals: у русского три формы, и «3 вложения»
            // против «5 вложений» строкой с %d не выразить.
            return context.resources.getQuantityString(
                R.plurals.preview_attachments, fileNames.size, fileNames.size
            )
        }

        // Ни текста, ни вложений: отозванное или незнакомое этой сборке.
        return context.getString(R.string.preview_empty)
    }
}
