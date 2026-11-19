package dev.proxywatch.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersions() {
        assertTrue(Updates.isNewer("0.6.1", "0.6.0"))
        assertTrue(Updates.isNewer("v1.0", "0.9.9"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.0"))   // numeric, not alphabetical
        assertFalse(Updates.isNewer("0.6.0", "0.6.0"))
        assertFalse(Updates.isNewer("0.5.1", "0.6.0"))
        assertFalse(Updates.isNewer("0.6", "0.6.0"))
    }

    @Test fun parsesGitHubRelease() {
        val r = Updates.parse(
            """{"tag_name":"v0.6.1","html_url":"https://github.com/DocDPhD/Puffble/releases/tag/v0.6.1",
               "assets":[{"name":"Puffble-watch-0.6.1.pbw","browser_download_url":"https://x/w.pbw"},
                         {"name":"Puffble-companion-0.6.1.apk","browser_download_url":"https://x/c.apk"}]}"""
        )
        assertEquals("0.6.1", r.version)
        assertEquals("https://x/c.apk", r.apkUrl)
        assertEquals("https://github.com/DocDPhD/Puffble/releases/tag/v0.6.1", r.pageUrl)
    }

    @Test fun releaseWithoutApkFallsBackToPage() {
        val r = Updates.parse("""{"tag_name":"v0.7.0","assets":[]}""")
        assertNull(r.apkUrl)
        assertEquals(Updates.RELEASES_PAGE, r.pageUrl)
    }
}
