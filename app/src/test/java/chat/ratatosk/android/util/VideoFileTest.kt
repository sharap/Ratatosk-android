package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя видеосообщения — вся договорённость о нём.
 *
 * Поля у ядра нет, и ошибиться здесь значит показать кружок вместо
 * файла (или наоборот) у собеседника, а не у себя: имя выбирает
 * отправитель, и приезжает оно с чужой стороны.
 */
class VideoFileTest {
    @Test
    fun aNameCarriesTheDuration() {
        assertEquals("12345.video.mp4", VideoFile.name(12345))
        assertEquals(12345L, VideoFile.durationMsOf("12345.video.mp4"))
    }

    @Test
    fun anOrdinaryMp4IsNotAVideoMessage() {
        assertNull(VideoFile.durationMsOf("холодильник.mp4"))
        assertNull(VideoFile.durationMsOf("12345.mp4"))
        assertNull(VideoFile.durationMsOf("12345.voice.ogg"))
        assertNull(VideoFile.durationMsOf(""))
    }

    /** Чужое имя — не обещание: год «длительности» рисовать нечем. */
    @Test
    fun anAbsurdDurationIsRefused() {
        assertNull(VideoFile.durationMsOf("999999999.video.mp4"))
        assertNull(VideoFile.durationMsOf("0.video.mp4"))
        assertNull(VideoFile.durationMsOf("-5.video.mp4"))
    }

    /** Потолок один и тот же и при записи имени, и при чтении. */
    @Test
    fun theCeilingHoldsOnBothSides() {
        val name = VideoFile.name(VideoFile.MAX_DURATION_MS * 10)
        assertEquals(VideoFile.MAX_DURATION_MS, VideoFile.durationMsOf(name))
    }

    @Test
    fun aHumanNameHasADateAndAPlainExtension() {
        val human = VideoFile.humanName(0L, "Видео")
        assertTrue(human.startsWith("Видео "))
        assertTrue(human.endsWith(".mp4"))
        assertTrue(".video." !in human)
    }

    /** Формат — по сигнатуре: имя выбирает отправитель. */
    @Test
    fun theFormatIsCheckedByItsSignature() {
        val dir = java.nio.file.Files.createTempDirectory("video").toFile()
        val real = java.io.File(dir, "a.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(8))
        }
        val fake = java.io.File(dir, "b.mp4").apply { writeBytes("не видео".toByteArray()) }
        assertTrue(VideoFile.looksLikeMp4(real))
        assertTrue(!VideoFile.looksLikeMp4(fake))
        dir.deleteRecursively()
    }
}
