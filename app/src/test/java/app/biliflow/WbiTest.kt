package app.biliflow

import org.junit.Assert.*
import org.junit.Test

class WbiTest {
    @Test fun keyPermutationMatchesPublishedVector() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", Wbi.mixin("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"))
    }
    @Test fun signatureIsSortedEscapedAndStable() {
        val result = Wbi.sign(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), "ea1db124af3c7062474693fa704f4ff8", 1702204169)
        assertEquals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4", result)
    }
    @Test fun filterCharactersBeforeEncodingAndUsePercent20ForSpaces() {
        val result = Wbi.sign(mapOf("q" to "a b!'()*中文"), "test", 100)
        assertTrue(result.startsWith("q=a%20b%E4%B8%AD%E6%96%87&wts=100&w_rid="))
        assertEquals(32, result.substringAfter("w_rid=").length)
    }
}
