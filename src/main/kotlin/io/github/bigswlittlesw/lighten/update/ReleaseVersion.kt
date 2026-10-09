package io.github.bigswlittlesw.lighten.update

/**
 * A release version, `<major>.<minor>.<patch>[-<pre-release>]`, as a release tag carries it after the `v`.
 * `.github/workflows/release.yml` checks tags with the same pattern, so every published version parses.
 *
 * Ordered by semver precedence: `1.0.0-rc.1` < `1.0.0` < `1.0.1`.
 */
internal data class ReleaseVersion(val text: String) : Comparable<ReleaseVersion> {
    init {
        require(PATTERN.matches(text)) { "not a release version: $text" }
    }

    override fun compareTo(other: ReleaseVersion): Int {
        val (core, preRelease) = parts()
        val (otherCore, otherPreRelease) = other.parts()
        core.zip(otherCore).map { (a, b) -> compareNumbers(a, b) }.firstOrNull { it != 0 }?.let { return it }
        // A pre-release sorts before its release.
        if (preRelease == null || otherPreRelease == null) {
            return compareValues(preRelease == null, otherPreRelease == null)
        }
        preRelease.zip(otherPreRelease).map { (a, b) -> compareIdentifiers(a, b) }
            .firstOrNull { it != 0 }?.let { return it }
        return preRelease.size.compareTo(otherPreRelease.size)
    }

    override fun toString(): String = text

    private fun parts(): Pair<List<String>, List<String>?> {
        val core = text.substringBefore('-').split('.')
        val preRelease = if ('-' in text) text.substringAfter('-').split('.') else null
        return core to preRelease
    }

    companion object {
        private val PATTERN = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?""")

        fun parseOrNull(text: String): ReleaseVersion? = text.takeIf(PATTERN::matches)?.let(::ReleaseVersion)
    }
}

private val DIGITS = Regex("[0-9]+")

// Digit strings of any length, compared as numbers without overflow; a version has no leading zeros.
private fun compareNumbers(a: String, b: String): Int =
    if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

// Semver: numeric identifiers compare as numbers and sort before alphanumeric ones, which compare as ASCII.
private fun compareIdentifiers(a: String, b: String): Int {
    val aNumeric = DIGITS.matches(a)
    val bNumeric = DIGITS.matches(b)
    return when {
        aNumeric && bNumeric -> compareNumbers(a.trimStart('0').ifEmpty { "0" }, b.trimStart('0').ifEmpty { "0" })
        aNumeric != bNumeric -> if (aNumeric) -1 else 1
        else -> a.compareTo(b)
    }
}

/** One binary that a release's `SHA256SUMS` lists: `lighten-<version>-<platform>` and its SHA-256 in hex. */
internal data class ReleaseAsset(val name: String, val version: ReleaseVersion, val sha256: String)

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

/**
 * The binary for [platform], such as `linux-x86_64-musl`, in the text of a `SHA256SUMS` file, or null when it lists
 * none. Its name carries its version, which is how "latest" is known without the GitHub API, as in `install.sh`.
 */
internal fun findAsset(sums: String, platform: String): ReleaseAsset? =
    sums.lineSequence().firstNotNullOfOrNull { assetLine(it, platform) }

private fun assetLine(line: String, platform: String): ReleaseAsset? {
    val fields = line.trim().split(Regex("\\s+"), limit = 2)
    if (fields.size != 2 || !SHA256_HEX.matches(fields[0])) return null
    // sha256sum marks a file it read in binary mode with a leading `*`.
    val name = fields[1].removePrefix("*")
    if (!name.startsWith("lighten-") || !name.endsWith("-$platform")) return null
    val version = ReleaseVersion.parseOrNull(name.removePrefix("lighten-").removeSuffix("-$platform")) ?: return null
    return ReleaseAsset(name, version, fields[0].lowercase())
}
