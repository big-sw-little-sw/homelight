package io.github.bigswlittlesw.lighten.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseVersionTest {

    @Test
    fun ordersBySemverPrecedence() {
        val ordered = listOf(
            "0.0.9", "0.1.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.9.0", "1.10.0", "10.0.0", "99999999999999999999.0.0",
        ).map(::ReleaseVersion)
        assertEquals(ordered, ordered.shuffled(java.util.Random(1)).sorted())
    }

    @Test
    fun parsesOnlyReleaseVersions() {
        assertEquals(ReleaseVersion("1.2.3-rc.1"), ReleaseVersion.parseOrNull("1.2.3-rc.1"))
        for (text in listOf("1.0-SNAPSHOT", "v1.2.3", "1.2", "01.2.3", "1.2.3-", "unknown", "", "1.2.3/../x")) {
            assertNull(ReleaseVersion.parseOrNull(text), text)
        }
    }

    @Test
    fun findsThisPlatformsBinaryInSha256sums() {
        val x86 = "a".repeat(64)
        val arm = "B".repeat(64)
        val sums = """
            $x86  lighten-1.2.3-linux-x86_64-musl
            $arm *lighten-1.2.3-linux-aarch64-gnu
        """.trimIndent()
        assertEquals(ReleaseAsset("lighten-1.2.3-linux-x86_64-musl", ReleaseVersion("1.2.3"), x86),
            findAsset(sums, "linux-x86_64-musl"))
        assertEquals(ReleaseAsset("lighten-1.2.3-linux-aarch64-gnu", ReleaseVersion("1.2.3"), "b".repeat(64)),
            findAsset(sums, "linux-aarch64-gnu"))
        assertNull(findAsset(sums, "linux-riscv64-gnu"))
        assertNull(findAsset("short  lighten-1.2.3-linux-x86_64-musl", "linux-x86_64-musl"))
        assertNull(findAsset("$x86  lighten-1.0-SNAPSHOT-linux-x86_64-musl", "linux-x86_64-musl"))
        assertNull(findAsset("$x86  install.sh", "linux-x86_64-musl"))
    }

    @Test
    fun picksTheAssetFromTheJvmArchitecture() {
        assertEquals("linux-x86_64-musl", releasePlatform("Linux", "amd64"))
        assertEquals("linux-aarch64-gnu", releasePlatform("Linux", "aarch64"))
        assertNull(releasePlatform("Linux", "riscv64"))
        assertNull(releasePlatform("Mac OS X", "aarch64"))
    }
}
