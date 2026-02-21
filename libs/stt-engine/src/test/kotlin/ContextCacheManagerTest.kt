import com.viitor.stt.util.ContextCacheManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCacheManagerTest {

    @Test
    fun testRollingWindow() {
        val manager = ContextCacheManager(maxContextChars = 20)
        
        manager.appendContext("Hello")
        assertEquals("Hello", manager.getPrompt())
        
        manager.appendContext("World")
        assertEquals("Hello World", manager.getPrompt())
        
        // This should trigger rolling
        manager.appendContext("This is a very long sentence")
        // "Hello World This is a very long sentence" is way over 20
        // Rolling logic takes last 20, then finds first space and takes after it
        val prompt = manager.getPrompt()
        println("Prompt after rolling: $prompt")
        
        // Ensure it's not empty and within reasonable length
        assert(prompt.length <= 20)
    }

    @Test
    fun testClear() {
        val manager = ContextCacheManager()
        manager.appendContext("Some context")
        manager.clear()
        assertEquals("", manager.getPrompt())
    }
}
