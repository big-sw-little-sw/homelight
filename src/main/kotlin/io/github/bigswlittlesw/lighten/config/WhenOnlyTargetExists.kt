package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The rule for when the source is absent and the target is a real directory. */
@Serializable
@SerialName("when-only-target-exists")
enum class WhenOnlyTargetExists {
    @SerialName("prompt") PROMPT,
    @SerialName("adopt-target") ADOPT_TARGET,
}
