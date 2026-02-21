import com.viitor.stt.WhisperSTTProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperSTTProcessorTest {
    @Test
    fun testDecodeTokensEnglish() {
        val processor = WhisperSTTProcessor()
        // 'H', 'e', 'l', 'l', 'o' tokens (0-255 mapping)
        val tokens = intArrayOf('H'.toInt(), 'e'.toInt(), 'l'.toInt(), 'l'.toInt(), 'o'.toInt())
        val result = processor.decodeTokens(tokens)
        assertEquals("Hello", result)
    }

    @Test
    fun testDecodeTokensKorean() {
        val processor = WhisperSTTProcessor()
        // "안녕" in UTF-8: EB 90 88 (안), EB 85 95 (녕)
        val tokens = intArrayOf(
            0xEB, 0x90, 0x88, // 안
            0xEB, 0x85, 0x95  // 녕
        )
        val result = processor.decodeTokens(tokens)
        assertEquals("안녕", result)
    }

    @Test
    fun testDecodeTokensMixed() {
        val processor = WhisperSTTProcessor()
        // "Hi 안녕"
        val tokens = intArrayOf(
            'H'.toInt(), 'i'.toInt(), ' '.toInt(),
            0xEB, 0x90, 0x88, 0xEB, 0x85, 0x95
        )
        val result = processor.decodeTokens(tokens)
        assertEquals("Hi 안녕", result)
    }
}
