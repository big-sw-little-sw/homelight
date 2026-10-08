package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The disposition required for a source directory when its target is adopted. */
@Serializable
@SerialName("when-adopting-target")
enum class WhenAdoptingTarget {
    @SerialName("prompt") PROMPT,
    @SerialName("discard-source") DISCARD_SOURCE,

    /** Moves the source under [Relocation.archiveRoot]. */
    @SerialName("archive-source") ARCHIVE_SOURCE,
}
