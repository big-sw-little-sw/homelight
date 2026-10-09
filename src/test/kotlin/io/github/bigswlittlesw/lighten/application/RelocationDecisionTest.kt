package io.github.bigswlittlesw.lighten.application

import io.github.bigswlittlesw.lighten.application.DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE
import io.github.bigswlittlesw.lighten.application.DecisionChoice.ADOPT_AND_DISCARD_SOURCE
import io.github.bigswlittlesw.lighten.application.DecisionChoice.ADOPT_TARGET
import io.github.bigswlittlesw.lighten.application.DecisionChoice.DISCARD_BOTH
import io.github.bigswlittlesw.lighten.application.DecisionChoice.LEAVE_UNCHANGED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * What decides a relocation, over (observed state × saved rules × one-time choice), read from a real evaluation as
 * Workspace reads it.
 */
class RelocationDecisionTest {
    @TempDir lateinit var temporary: Path

    enum class Observed { ONLY_TARGET, BOTH, ONLY_SOURCE }

    /** `inForce` is what the ● marks; `oneTime` says the Decision line names the one-time choice. */
    data class Expected(val offered: List<DecisionChoice>, val inForce: DecisionChoice?, val oneTime: Boolean = false)

    data class Row(val observed: Observed, val rules: Map<String, String>, val choice: DecisionChoice?, val expected: Expected) {
        override fun toString() = "$observed $rules choice=$choice"
    }

    @ParameterizedTest
    @MethodSource("rows")
    fun decidesFromTheObservedCaseTheSavedRuleAndTheOneTimeChoice(row: Row) {
        val root = temporary.toRealPath()
        if (row.observed != Observed.ONLY_SOURCE) Files.createDirectory(root.resolve("target"))
        if (row.observed != Observed.ONLY_TARGET) Files.createDirectory(root.resolve("source"))
        val fields = mapOf("source-path" to "$root/source", "target-path" to "$root/target") + row.rules
        val config = root.resolve("config.json")
        Files.writeString(
            config,
            "{\"lighten\": {\"target-root\": \"$root\", \"relocations\": [" +
                fields.entries.joinToString(", ", "{", "}") { (key, value) -> "\"$key\": \"$value\"" } + "]}}",
        )
        val evaluation = ConfigurationEvaluation()
        val loaded = assertInstanceOf(ConfigurationEvaluation.Loaded::class.java, evaluation.load(config))
        val chosen = row.choice?.let { evaluation.choose(loaded, root.resolve("source"), it) } ?: loaded

        val decision = chosen.items.single().decision
        assertEquals(
            row.expected,
            Expected(decision?.offered.orEmpty(), decision?.inForce, decision?.oneTimeChoice != null),
        )
    }

    companion object {
        private val BOTH_CHOICES = listOf(ADOPT_AND_DISCARD_SOURCE, ADOPT_AND_ARCHIVE_SOURCE, LEAVE_UNCHANGED, DISCARD_BOTH)
        private const val BOTH = "when-source-and-target-directories-exist"
        private const val ONLY = "when-only-target-exists"
        private const val ADOPTING = "when-adopting-target"

        @JvmStatic // JUnit's MethodSource needs a static factory.
        fun rows(): List<Row> = listOf(
            Row(Observed.ONLY_TARGET, mapOf(), null, Expected(listOf(ADOPT_TARGET), null)),
            Row(Observed.ONLY_TARGET, mapOf(ONLY to "adopt-target"), null, Expected(listOf(ADOPT_TARGET), ADOPT_TARGET)),
            // A saved Both exist rule must not hide the Only target rule that governs this case, or no ● marks a
            // choice.
            Row(
                Observed.ONLY_TARGET, mapOf(BOTH to "adopt", ADOPTING to "discard-source", ONLY to "adopt-target"), null,
                Expected(listOf(ADOPT_TARGET), ADOPT_TARGET),
            ),
            Row(Observed.ONLY_TARGET, mapOf(BOTH to "discard", ONLY to "adopt-target"), null, Expected(listOf(ADOPT_TARGET), ADOPT_TARGET)),
            Row(Observed.ONLY_TARGET, mapOf(), ADOPT_TARGET, Expected(listOf(ADOPT_TARGET), ADOPT_TARGET, oneTime = true)),
            // A one-time Keep target over a saved Both exist rule is the choice in force, so the ● and the Decision
            // line agree.
            Row(
                Observed.ONLY_TARGET, mapOf(BOTH to "leave-unchanged"), ADOPT_TARGET,
                Expected(listOf(ADOPT_TARGET), ADOPT_TARGET, oneTime = true),
            ),
            Row(
                Observed.ONLY_TARGET, mapOf(BOTH to "adopt", ADOPTING to "archive-source"), ADOPT_TARGET,
                Expected(listOf(ADOPT_TARGET), ADOPT_TARGET, oneTime = true),
            ),
            Row(Observed.BOTH, mapOf(), null, Expected(BOTH_CHOICES, null)),
            // The Only target rule does not govern this case.
            Row(Observed.BOTH, mapOf(ONLY to "adopt-target"), null, Expected(BOTH_CHOICES, null)),
            Row(Observed.BOTH, mapOf(BOTH to "adopt"), null, Expected(BOTH_CHOICES, null)),
            Row(Observed.BOTH, mapOf(BOTH to "adopt", ADOPTING to "discard-source"), null, Expected(BOTH_CHOICES, ADOPT_AND_DISCARD_SOURCE)),
            Row(Observed.BOTH, mapOf(BOTH to "adopt", ADOPTING to "archive-source"), null, Expected(BOTH_CHOICES, ADOPT_AND_ARCHIVE_SOURCE)),
            Row(Observed.BOTH, mapOf(BOTH to "leave-unchanged"), null, Expected(BOTH_CHOICES, LEAVE_UNCHANGED)),
            Row(Observed.BOTH, mapOf(BOTH to "discard"), null, Expected(BOTH_CHOICES, DISCARD_BOTH)),
            // A one-time choice wins over the saved rule, and a leftover source rule does not change it.
            Row(Observed.BOTH, mapOf(BOTH to "discard"), LEAVE_UNCHANGED, Expected(BOTH_CHOICES, LEAVE_UNCHANGED, oneTime = true)),
            Row(
                Observed.BOTH, mapOf(BOTH to "leave-unchanged", ADOPTING to "discard-source"), ADOPT_AND_ARCHIVE_SOURCE,
                Expected(BOTH_CHOICES, ADOPT_AND_ARCHIVE_SOURCE, oneTime = true),
            ),
            // No rule governs a move.
            Row(Observed.ONLY_SOURCE, mapOf(BOTH to "discard", ONLY to "adopt-target"), null, Expected(listOf(), null)),
        )
    }
}
