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
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.Visitor
import org.commonmark.parser.Parser
import org.commonmark.ext.autolink.AutolinkExtension

object MarkdownUtils {
    private val extensions = listOf(AutolinkExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()

    fun parseMarkdown(text: String, linkColor: Color): AnnotatedString {
        val document = parser.parse(text)
        return buildAnnotatedString {
            val visitor = ComposeAnnotatedStringVisitor(this, linkColor)
            document.accept(visitor)
        }
    }

    private class ComposeAnnotatedStringVisitor(
        private val builder: AnnotatedString.Builder,
        private val linkColor: Color
    ) : Visitor {
        override fun visit(text: Text) {
            builder.append(text.literal)
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
            builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(alpha = 0.3f))) {
                builder.append(code.literal)
            }
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(alpha = 0.2f))) {
                builder.append(fencedCodeBlock.literal)
            }
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
                1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified) // Simple for chat
                else -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            builder.withStyle(style) {
                visitChildren(heading)
            }
            if (heading.next != null) builder.append("\n")
        }

        override fun visit(blockQuote: BlockQuote) {
            builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
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

        // We explicitly ignore Image and other nodes by not implementing them or doing nothing
        override fun visit(image: org.commonmark.node.Image) {
            // Safe: ignore images
        }

        override fun visit(customBlock: org.commonmark.node.CustomBlock) { visitChildren(customBlock) }
        override fun visit(customNode: org.commonmark.node.CustomNode) { visitChildren(customNode) }
        override fun visit(document: org.commonmark.node.Document) { visitChildren(document) }
        override fun visit(htmlInline: org.commonmark.node.HtmlInline) { /* ignore HTML */ }
        override fun visit(htmlBlock: org.commonmark.node.HtmlBlock) { /* ignore HTML */ }
        override fun visit(thematicBreak: org.commonmark.node.ThematicBreak) { builder.append("\n---\n") }
        override fun visit(indentedCodeBlock: org.commonmark.node.IndentedCodeBlock) {
            builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                builder.append(indentedCodeBlock.literal)
            }
        }

        override fun visit(linkReferenceDefinition: org.commonmark.node.LinkReferenceDefinition) {
            // Ignore
        }

        private fun visitChildren(node: Node) {
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                child.accept(this)
                child = next
            }
        }
    }
}
