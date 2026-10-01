package io.github.bigswlittlesw.homelight.application

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan
import java.nio.file.Path
import java.util.Objects

/** State representation for the dry-run plan workflow. */
sealed interface PlanModel {
    // Java callers use the record-style accessor `configPath()`, which each record's component implements.
    @Suppress("INAPPLICABLE_JVM_NAME")
    @get:JvmName("configPath")
    val configPath: Path

    /** Indicates that no configuration exists at the default path. */
    @JvmRecord
    data class Unconfigured(override val configPath: Path) : PlanModel

    /**
     * Indicates active configuration with inspected relocations, dry-run actions, and resolutions.
     *
     * Not a `@JvmRecord data class`: the constructor sorts and copies `items`, which a Kotlin record cannot
     * do. Accessors keep the record names; equality and `toString` match the record this replaces.
     */
    class Configured(
        configPath: Path,
        targetRoot: Path,
        plan: ReconciliationPlan,
        items: List<PlanRelocationItem>,
        summary: PlanSummary,
    ) : PlanModel {
        @Suppress("INAPPLICABLE_JVM_NAME")
        @get:JvmName("configPath")
        override val configPath: Path = configPath

        @get:JvmName("targetRoot")
        val targetRoot: Path = targetRoot

        @get:JvmName("plan")
        val plan: ReconciliationPlan = plan

        @get:JvmName("items")
        val items: List<PlanRelocationItem>

        @get:JvmName("summary")
        val summary: PlanSummary = summary

        init {
            val sorted = ArrayList(items)
            sorted.sortWith(PlanRelocationItem.BY_URGENCY_AND_PATH)
            this.items = java.util.List.copyOf(sorted)
        }

        override fun equals(other: Any?): Boolean = other is Configured
                && configPath == other.configPath
                && targetRoot == other.targetRoot
                && plan == other.plan
                && items == other.items
                && summary == other.summary

        override fun hashCode(): Int = Objects.hash(configPath, targetRoot, plan, items, summary)

        override fun toString(): String =
            "Configured[configPath=$configPath, targetRoot=$targetRoot, plan=$plan, items=$items, summary=$summary]"
    }

    /** Indicates that configuration could not be loaded, parsed, or planned. */
    @JvmRecord
    data class Invalid(override val configPath: Path, val message: String) : PlanModel
}
