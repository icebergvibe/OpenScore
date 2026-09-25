package org.openscore.app

import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentTest {

    @Test
    fun theVersionIsTheInstalledOnesMajorAndMinor() {
        assertEquals("OpenScore-Android/0.3 (+https://github.com/icebergvibe/OpenScore)", OpenScoreApp.userAgent("0.3.5"))
        assertEquals("OpenScore-Android/0.3 (+https://github.com/icebergvibe/OpenScore)", OpenScoreApp.userAgent("0.3.12"), "a patch release is not a new client")
        assertEquals("OpenScore-Android/1.12 (+https://github.com/icebergvibe/OpenScore)", OpenScoreApp.userAgent("1.12.0-rc1"))
    }

    @Test
    fun aVersionThatCannotBeReadGivesTheNameAlone() {
        assertEquals("OpenScore-Android (+https://github.com/icebergvibe/OpenScore)", OpenScoreApp.userAgent(null))
        assertEquals("OpenScore-Android (+https://github.com/icebergvibe/OpenScore)", OpenScoreApp.userAgent("nightly"))
    }
}
