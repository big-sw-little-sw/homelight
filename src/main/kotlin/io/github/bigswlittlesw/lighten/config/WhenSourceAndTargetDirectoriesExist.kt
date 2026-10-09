package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The rule for when the source and the target are both real directories. */
@Serializable
@SerialName("when-source-and-target-directories-exist")
enum class WhenSourceAndTargetDirectoriesExist {
    @SerialName("prompt") PROMPT,
    @SerialName("adopt") ADOPT,
    @SerialName("leave-unchanged") LEAVE_UNCHANGED,
    @SerialName("discard") DISCARD,
}
