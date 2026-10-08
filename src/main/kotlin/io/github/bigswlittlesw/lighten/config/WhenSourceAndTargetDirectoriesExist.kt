package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The decision required when both relocation paths are real directories. */
@Serializable
@SerialName("when-source-and-target-directories-exist")
enum class WhenSourceAndTargetDirectoriesExist {
    @SerialName("prompt") PROMPT,
    @SerialName("adopt") ADOPT,
    @SerialName("leave-unchanged") LEAVE_UNCHANGED,
    @SerialName("discard") DISCARD,
}
