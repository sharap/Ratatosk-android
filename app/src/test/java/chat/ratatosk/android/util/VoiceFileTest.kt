package chat.ratatosk.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя голосового — это весь наш признак: отдельного поля у ядра нет.
 * Значит разбор обязан быть строгим, а число — проверенным: имя выбирает
 * отправитель, и «12 миллионов часов» прислать ничто не мешает.
 */
class VoiceFileTest {
    @Test
    fun aRecordingIsRecognisedByItsName() {
        assertEquals(12345L, VoiceFile.durationMsOf(VoiceFile.name(12345)))
        assertTrue(VoiceFile.isVoice("12345.voice.ogg"))
    }

    /** Присланный `.ogg` из чужой коллекции голосовым не становится. */
    @Test
    fun anOrdinaryOggIsNotAVoiceMessage() {
        assertNull(VoiceFile.durationMsOf("song.ogg"))
        assertNull(VoiceFile.durationMsOf("12345.ogg"))
        assertNull(VoiceFile.durationMsOf("voice.ogg"))
        assertFalse(VoiceFile.isVoice("подкаст.voice.mp3"))
    }

    /** До суффикса — только цифры: ни пробелов, ни знаков, ни путей. */
    @Test
    fun onlyDigitsCountAsDuration() {
        assertNull(VoiceFile.durationMsOf("12 345.voice.ogg"))
        assertNull(VoiceFile.durationMsOf("-5.voice.ogg"))
        assertNull(VoiceFile.durationMsOf("12.5.voice.ogg"))
        assertNull(VoiceFile.durationMsOf("../12345.voice.ogg"))
    }

    /** Несусветная длительность — это не голосовое, а мусор. */
    @Test
    fun anAbsurdDurationIsRefused() {
        assertNull(VoiceFile.durationMsOf("999999999.voice.ogg"))
        assertNull(VoiceFile.durationMsOf("0.voice.ogg"))
        assertEquals(VoiceFile.MAX_DURATION_MS, VoiceFile.durationMsOf("3600000.voice.ogg"))
    }

    /** Своя запись длиннее потолка обрезается, а не уезжает как есть. */
    @Test
    fun ourOwnNameStaysWithinTheCap() {
        assertEquals("3600000.voice.ogg", VoiceFile.name(VoiceFile.MAX_DURATION_MS * 10))
    }

    @Test
    fun theSavedNameIsForPeopleNotForUs() {
        val name = VoiceFile.humanName(0L, "Голосовое")
        assertTrue(name, name.startsWith("Голосовое "))
        assertTrue(name, name.endsWith(".ogg"))
        assertFalse(name, name.contains(".voice."))
    }
}
