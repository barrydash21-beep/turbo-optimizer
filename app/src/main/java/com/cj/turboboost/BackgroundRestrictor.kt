package com.cj.turboboost

import android.os.Build

/** What one package went through in a restrict or a restore. */
data class PackageOutcome(
    val packageName: String,
    /** Steps that ran and read back as asked. */
    val applied: List<String> = emptyList(),
    /** Commands that exited 0 but the system did not keep, as "bucket ignored (stays 5)". */
    val ignored: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    /** Informational: a step left out on purpose, and why. */
    val notes: List<String> = emptyList(),
    /** The package was not touched at all, and why. */
    val skipped: String? = null
) {
    val ok: Boolean get() = failed.isEmpty() && skipped == null

    /** One line for the list row: "background ignore, stopped, bucket ignored (stays 5)". */
    fun detail(): String = skipped ?: (applied + ignored + failed.map { "$it failed" } + notes)
        .joinToString(", ")
        .ifEmpty { "nothing to do" }
}

/** Outcome of one restrict or restore, package by package. */
data class RestrictionReport(
    val packages: List<PackageOutcome> = emptyList(),
    /** The Shizuku binder was not alive, so nothing was attempted. */
    val unavailable: Boolean = false,
    /** The whole lever was left out, and why. */
    val skipped: String? = null
) {
    val failedPackages: List<String> get() = packages.filter { it.failed.isNotEmpty() }.map { it.packageName }

    /** Restore only: every held package is back, so nothing is left to retry. */
    val ok: Boolean get() = !unavailable && skipped == null && failedPackages.isEmpty()

    /** A one-line summary fit for a toast, after a restrict. */
    fun restrictSummary(): String {
        if (unavailable) return "Background apps: skipped, Shizuku not connected"
        if (skipped != null) return "Background apps: skipped, $skipped"
        val restricted = packages.count { it.skipped == null }
        if (restricted == 0) return "Background apps: nothing to restrict"
        val ignored = packages.count { it.ignored.isNotEmpty() }
        return buildString {
            append("Background apps: $restricted restricted")
            if (ignored > 0) append(", $ignored partly ignored by the system")
            if (failedPackages.isNotEmpty()) append(", failed: ${failedPackages.joinToString(", ")}")
        }
    }
}

/**
 * Keeps a list of background apps from running while a game session is active, and puts each
 * one back exactly as it was afterwards.
 *
 * Start, in this order, all packages per phase: persist every package's current
 * RUN_ANY_IN_BACKGROUND mode and standby bucket; set the op to `ignore`; set the bucket to
 * `restricted`; then `am force-stop`. Restricting before stopping keeps one app from bringing
 * another straight back. End: write back each saved mode and bucket. Nothing is ever started.
 *
 * Like [PerformanceTuner], the saved state and an `active` flag outlive the process, so a
 * session that dies with the app is restored on the next app start. A package is forgotten
 * only once both values read back as saved; anything else is retried.
 *
 * Measured on a TECNO LJ6 (POVA 7, HiOS 16.3.0, SDK 36) on 2026-10-18: the op sticks. The
 * bucket sticks for a package in an ordinary bucket, but `set-standby-bucket` exits 0 and
 * changes nothing for one HiOS exempts (bucket 5, on the deviceidle allowlist), which was
 * most of the defaults. That is reported per package, not worked around.
 */
object BackgroundRestrictor {

    const val OP = "RUN_ANY_IN_BACKGROUND"

    /** The op and `am set-standby-bucket` arrived in Android 9 (AOSP; only checked on 16). */
    const val MIN_SDK = Build.VERSION_CODES.P

    /** The restricted bucket arrived in Android 11; below that the bucket is left alone. */
    const val RESTRICTED_BUCKET_MIN_SDK = Build.VERSION_CODES.R

    const val BUCKET_RESTRICTED = 45

    /** `am get-standby-bucket` for an exempted app. It cannot be set, so it is never restored. */
    const val BUCKET_EXEMPTED = 5

    /** How long after session start the monitor records which restricted packages are alive. */
    const val ALIVE_CHECK_DELAY_MS = 30_000L

    private const val KEY_ACTIVE = "active"
    private const val KEY_PACKAGES = "packages"
    private const val PREFIX_OP = "op_"
    private const val PREFIX_BUCKET = "bucket_"

    private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    fun isActive(store: KeyValueStore): Boolean = store.getBoolean(KEY_ACTIVE, false)

    /** The packages whose pre-session state is held, in the order they were saved. */
    fun heldPackages(store: KeyValueStore): List<String> =
        store.getString(KEY_PACKAGES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** The user's list until edited: the installed defaults that are not protected. */
    fun defaultsFor(installed: Set<String>, protected: Set<String>): List<String> =
        AppPackages.RESTRICT_DEFAULTS.filter { it in installed && !AppPackages.isProtected(it, protected) }

    /**
     * Restricts every installed, unprotected package in [candidates] for a session of
     * [gamePackage]. Each command runs on its own: one failing never stops the rest.
     */
    fun restrict(
        shell: ShellExecutor,
        store: KeyValueStore,
        candidates: List<String>,
        gamePackage: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): RestrictionReport {
        if (!shell.isAvailable()) return RestrictionReport(unavailable = true)
        if (sdkInt < MIN_SDK) return RestrictionReport(skipped = "needs Android 9 or later")
        val installed = RamCleaner.parsePackageList(shell.capture("pm list packages"))
            ?: return RestrictionReport(skipped = "could not list installed apps")
        val protected = RamCleaner.protectedPackages(shell, gamePackage)
            ?: return RestrictionReport(skipped = "could not identify the launcher or keyboard")

        val outcomes = linkedMapOf<String, PackageOutcome>()
        val targets = mutableListOf<String>()
        for (pkg in candidates.distinct()) {
            when {
                !GameRegistry.isValidPackageName(pkg) || pkg !in installed -> Unit
                AppPackages.isProtected(pkg, protected) ->
                    outcomes[pkg] = PackageOutcome(pkg, skipped = "protected, left alone")
                else -> targets += pkg
            }
        }

        // 1. Every package's state is saved before anything changes. One still held from a
        //    session that was never restored keeps its original values.
        val held = heldPackages(store).toMutableList()
        val restricting = mutableListOf<String>()
        for (pkg in targets) {
            if (pkg !in held) {
                val mode = parseOpMode(shell.capture("cmd appops get $pkg $OP"))
                if (mode == null) {
                    outcomes[pkg] = PackageOutcome(pkg, skipped = "state unreadable, left alone")
                    continue
                }
                store.putString(PREFIX_OP + pkg, mode)
                store.putString(PREFIX_BUCKET + pkg, parseBucket(shell.capture("am get-standby-bucket $pkg"))?.toString())
                held += pkg
                store.putString(KEY_PACKAGES, held.joinToString(","))
                store.putBoolean(KEY_ACTIVE, true)
            }
            restricting += pkg
        }

        val applied = restricting.associateWith { mutableListOf<String>() }
        val ignored = restricting.associateWith { mutableListOf<String>() }
        val failed = restricting.associateWith { mutableListOf<String>() }
        val notes = restricting.associateWith { mutableListOf<String>() }

        // 2. The op, read back: exit 0 is not the same as the value sticking.
        for (pkg in restricting) {
            val set = shell.run("cmd appops set $pkg $OP ignore").ok
            val back = parseOpMode(shell.capture("cmd appops get $pkg $OP"))
            when {
                !set -> failed.getValue(pkg) += "background"
                back != "ignore" -> ignored.getValue(pkg) += "background ignored (reads ${back ?: "unreadable"})"
                else -> applied.getValue(pkg) += "background ignore"
            }
        }

        // 3. The bucket, read back. Never changed when the saved value is unknown, since it
        //    could not be put back.
        for (pkg in restricting) {
            when {
                sdkInt < RESTRICTED_BUCKET_MIN_SDK -> notes.getValue(pkg) += "bucket needs Android 11"
                store.getString(PREFIX_BUCKET + pkg) == null -> notes.getValue(pkg) += "bucket unreadable, left alone"
                else -> {
                    val set = shell.run("am set-standby-bucket $pkg restricted").ok
                    val back = parseBucket(shell.capture("am get-standby-bucket $pkg"))
                    when {
                        !set -> failed.getValue(pkg) += "bucket"
                        back != BUCKET_RESTRICTED -> ignored.getValue(pkg) += "bucket ignored (stays ${back ?: "unreadable"})"
                        else -> applied.getValue(pkg) += "bucket restricted"
                    }
                }
            }
        }

        // 4. Only now, with nothing left able to start them, stop them.
        for (pkg in restricting) {
            if (shell.run("am force-stop $pkg").ok) applied.getValue(pkg) += "stopped" else failed.getValue(pkg) += "force-stop"
        }

        for (pkg in restricting) {
            outcomes[pkg] = PackageOutcome(pkg, applied.getValue(pkg), ignored.getValue(pkg), failed.getValue(pkg), notes.getValue(pkg))
        }
        return RestrictionReport(packages = candidates.distinct().mapNotNull { outcomes[it] })
    }

    /**
     * Writes back every held package's saved op mode and bucket, skipping a value that already
     * reads as saved. Clears the active flag only once nothing is left to restore.
     *
     * `am set-standby-bucket` records the bucket as set by the user, so a restored bucket has
     * the right number but no longer ages out on its own until the app is next used.
     */
    fun restore(shell: ShellExecutor, store: KeyValueStore, sdkInt: Int = Build.VERSION.SDK_INT): RestrictionReport {
        if (!isActive(store)) return RestrictionReport()
        if (!shell.isAvailable()) return RestrictionReport(unavailable = true)

        // Null when unlistable: then every package is tried as if installed.
        val installed = RamCleaner.parsePackageList(shell.capture("pm list packages"))
        val outcomes = mutableListOf<PackageOutcome>()
        val remaining = mutableListOf<String>()
        for (pkg in heldPackages(store)) {
            if (installed != null && pkg !in installed) {
                forget(store, pkg)
                outcomes += PackageOutcome(pkg, notes = listOf("uninstalled, nothing to restore"))
                continue
            }
            val applied = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val notes = mutableListOf<String>()

            store.getString(PREFIX_OP + pkg)?.let { mode ->
                val current = parseOpMode(shell.capture("cmd appops get $pkg $OP"))
                val back = if (current == mode) {
                    current
                } else {
                    shell.run("cmd appops set $pkg $OP $mode")
                    parseOpMode(shell.capture("cmd appops get $pkg $OP"))
                }
                if (back == mode) applied += "background $mode" else failed += "background (wanted $mode, reads ${back ?: "unreadable"})"
            }

            store.getString(PREFIX_BUCKET + pkg)?.toIntOrNull()?.let { bucket ->
                val current = parseBucket(shell.capture("am get-standby-bucket $pkg"))
                when {
                    current == bucket -> applied += "bucket $bucket"
                    bucket == BUCKET_EXEMPTED -> notes += "bucket is system-managed (now ${current ?: "unreadable"})"
                    sdkInt < MIN_SDK -> notes += "bucket needs Android 9"
                    else -> {
                        shell.run("am set-standby-bucket $pkg $bucket")
                        val back = parseBucket(shell.capture("am get-standby-bucket $pkg"))
                        if (back == bucket) applied += "bucket $bucket" else failed += "bucket (wanted $bucket, reads ${back ?: "unreadable"})"
                    }
                }
            }

            if (failed.isEmpty()) forget(store, pkg) else remaining += pkg
            outcomes += PackageOutcome(pkg, applied = applied, failed = failed, notes = notes)
        }

        if (remaining.isEmpty()) {
            store.putString(KEY_PACKAGES, null)
            store.putBoolean(KEY_ACTIVE, false)
        } else {
            store.putString(KEY_PACKAGES, remaining.joinToString(","))
        }
        return RestrictionReport(packages = outcomes)
    }

    /**
     * Which held packages have a live process now (any of their processes, `pkg:svc` too),
     * or null if the process list could not be read.
     */
    fun alive(shell: ShellExecutor, store: KeyValueStore): List<String>? {
        val held = heldPackages(store)
        if (held.isEmpty()) return emptyList()
        val running = RamCleaner.parseProcessNames(shell.capture("ps -A -o NAME")) ?: return null
        return held.filter { it in running }.sorted()
    }

    private fun forget(store: KeyValueStore, pkg: String) {
        store.putString(PREFIX_OP + pkg, null)
        store.putString(PREFIX_BUCKET + pkg, null)
        val left = heldPackages(store) - pkg
        store.putString(KEY_PACKAGES, left.joinToString(",").ifEmpty { null })
    }

    /**
     * The package's RUN_ANY_IN_BACKGROUND mode from `cmd appops get <pkg> <op>`:
     * `RUN_ANY_IN_BACKGROUND: ignore` (sometimes followed by `; time=...`), or, when nothing is
     * set, `No operations.` and `Default mode: allow`, read as that default. `Uid mode:` lines
     * belong to the uid, which this never sets. Null for anything else.
     */
    internal fun parseOpMode(raw: String?): String? {
        val lines = raw?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList() ?: return null
        lines.firstOrNull { it.startsWith("$OP:") }?.let { line ->
            return line.substringAfter(':').substringBefore(';').trim().substringBefore(' ').takeIf { it in MODES }
        }
        if (lines.none { it.startsWith("No operations") }) return null
        val default = lines.firstOrNull { it.startsWith("Default mode:") } ?: return "allow" // the op's AOSP default
        return default.substringAfter(':').trim().takeIf { it in MODES }
    }

    /** `am get-standby-bucket <pkg>` prints only the number (seen: 5, 10, 30, 40, 45). */
    internal fun parseBucket(raw: String?): Int? =
        raw?.lineSequence()?.map { it.trim() }?.firstNotNullOfOrNull { it.toIntOrNull() }
}
