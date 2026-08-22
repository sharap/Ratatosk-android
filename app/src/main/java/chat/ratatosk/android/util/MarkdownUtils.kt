package chat.ratatosk.android.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*

object MarkdownUtils {
    private val extensions = listOf(
        AutolinkExtension.create(),
        StrikethroughExtension.create(),
        TablesExtension.create()
    )
    private val parser = Parser.builder().extensions(extensions).build()

    fun parseMarkdown(text: String, linkColor: Color): AnnotatedString {
        // Pre-processor for spoilers ||text||
        val processedText = text.replace(Regex("\\|\\|(.+?)\\|\\|"), "%%SPOILER_START%%$1%%SPOILER_END%%")
        
        val document = parser.parse(processedText)
        return buildAnnotatedString {
            val visitor = ComposeAnnotatedStringVisitor(this, linkColor)
            document.accept(visitor)
        }
    }

    fun formatForNotification(text: String, spoilerPlaceholder: String): String {
        // Replace spoilers first
        val noSpoilers = text.replace(Regex("\\|\\|.+?\\|\\|"), "[$spoilerPlaceholder]")
        
        // Very basic markdown stripping (bold, italic, strikethrough)
        return noSpoilers
            .replace(Regex("\\*\\*|__|\\*|_|~~"), "")
            .trim()
    }

    private class ComposeAnnotatedStringVisitor(
        private val builder: AnnotatedString.Builder,
        private val linkColor: Color
    ) : AbstractVisitor() {
        
        override fun visit(text: Text) {
            val content = text.literal
            if (content.contains("%%SPOILER_START%%")) {
                var remaining = content
                while (remaining.contains("%%SPOILER_START%%")) {
                    val start = remaining.indexOf("%%SPOILER_START%%")
                    val end = remaining.indexOf("%%SPOILER_END%%")
                    if (end > start) {
                        builder.append(remaining.substring(0, start))
                        val spoilerText = remaining.substring(start + "%%SPOILER_START%%".length, end)
                        // Just push annotation, UI will style it based on reveal state
                        builder.pushStringAnnotation("SPOILER", "spoiler")
                        builder.append(spoilerText)
                        builder.pop()
                        remaining = remaining.substring(end + "%%SPOILER_END%%".length)
                    } else {
                        break
                    }
                }
                builder.append(remaining)
            } else {
                builder.append(content)
            }
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            builder.append(" ")
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            builder.append("\n")
        }

        override fun visit(emphasis: Emphasis) {
            builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                visitChildren(emphasis)
            }
        }

        override fun visit(strongEmphasis: StrongEmphasis) {
            builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                visitChildren(strongEmphasis)
            }
        }

        override fun visit(code: Code) {
            builder.withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color.Gray.copy(alpha = 0.15f),
                color = Color(0xFFE91E63)
            )) {
                builder.append(code.literal)
            }
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            builder.append("\n")
            builder.withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color.Gray.copy(alpha = 0.1f)
            )) {
                builder.append(fencedCodeBlock.literal)
            }
            builder.append("\n")
        }

        override fun visit(link: Link) {
            builder.withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                builder.pushStringAnnotation(tag = "URL", annotation = link.destination)
                visitChildren(link)
                builder.pop()
            }
        }

        override fun visit(paragraph: Paragraph) {
            visitChildren(paragraph)
            if (paragraph.next != null) builder.append("\n\n")
        }

        override fun visit(heading: Heading) {
            val style = when (heading.level) {
                1 -> SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)
                3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)
                else -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            builder.append("\n")
            builder.withStyle(style) {
                visitChildren(heading)
            }
            builder.append("\n")
        }

        override fun visit(blockQuote: BlockQuote) {
            builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Gray)) {
                builder.append("> ")
                visitChildren(blockQuote)
            }
            if (blockQuote.next != null) builder.append("\n")
        }

        override fun visit(bulletList: BulletList) {
            visitChildren(bulletList)
            if (bulletList.next != null) builder.append("\n")
        }

        override fun visit(orderedList: OrderedList) {
            visitChildren(orderedList)
            if (orderedList.next != null) builder.append("\n")
        }

        override fun visit(listItem: ListItem) {
            builder.append("• ")
            visitChildren(listItem)
            builder.append("\n")
        }

        override fun visit(customNode: CustomNode) {
            when (customNode) {
                is Strikethrough -> {
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        visitChildren(customNode)
                    }
                }
                is TableHead -> {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        visitChildren(customNode)
                    }
                }
                is TableRow -> {
                    visitChildren(customNode)
                    builder.append("\n")
                }
                is TableCell -> {
                    visitChildren(customNode)
                    builder.append(" | ")
                }
                else -> super.visit(customNode)
            }
        }

        override fun visit(customBlock: CustomBlock) {
            when (customBlock) {
                is TableBlock -> {
                    builder.append("\n")
                    // Monospace font helps align "text-based" tables
                    builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                        visitChildren(customBlock)
                    }
                    builder.append("\n")
                }
                is TableBody -> {
                    visitChildren(customBlock)
                }
                else -> super.visit(customBlock)
            }
        }

        override fun visit(thematicBreak: ThematicBreak) {
            builder.append("\n---\n")
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                builder.append(indentedCodeBlock.literal)
            }
        }

        override fun visit(image: Image) {
            // Safe: ignore
        }

        override fun visit(htmlInline: HtmlInline) {
            // Safe: ignore
        }

        override fun visit(htmlBlock: HtmlBlock) {
            // Safe: ignore
        }
    }
}
