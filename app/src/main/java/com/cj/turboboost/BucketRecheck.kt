package com.cj.turboboost

/**
 * Puts back standby buckets that a phone restart undid. Seen on a Pova 7 (HiOS 16.3): Facebook
 * Lite was restored from 45 to 10 when a session ended, read 10, and read 45 again after a
 * reboot less than a minute later; the system had kept the restricted bucket, not the restore.
 *
 * After each successful [BackgroundRestrictor.restore], [remember] keeps each restored
 * package's bucket as read back, with the boot it was read in. At the next app start in a new
 * boot, [recheck] reads each one again, sets the saved bucket back where it reads 45 and was
 * not 45, reads it back, and clears the record. The restriction itself is not touched.
 */
object BucketRecheck {

    /** In the restrictions store, beside BackgroundRestrictor's own keys; no prefix shared with them. */
    private const val KEY_BOOT = "recheck_boot"
    private const val KEY_BUCKETS = "recheck_buckets"

    /** Buckets an app can be put back to: active, working set, frequent, rare. */
    private val SETTABLE = setOf(10, 20, 30, 40)

    /** What one package read in the new boot, and what it read after any set. */
    data class Outcome(val packageName: String, val saved: Int, val found: Int?, val after: Int?, val setAgain: Boolean)

    /** Keeps the buckets [report] restored, as read back now, with [bootId]. */
    fun remember(shell: ShellExecutor, store: KeyValueStore, report: RestrictionReport, bootId: String?) {
        if (bootId.isNullOrBlank()) return
        val buckets = report.packages
            .filter { it.skipped == null && it.failed.isEmpty() && GameRegistry.isValidPackageName(it.packageName) }
            .mapNotNull { outcome ->
                read(shell, outcome.packageName)?.takeIf { it in SETTABLE }?.let { outcome.packageName to it }
            }
        if (buckets.isEmpty()) return
        store.putStringNow(KEY_BUCKETS, buckets.joinToString(",") { (pkg, bucket) -> "$pkg:$bucket" })
        store.putStringNow(KEY_BOOT, bootId)
    }

    /**
     * Run at app start. Null when there is nothing to do: no record, the boot is unreadable, or
     * it is still the boot the record was made in. Otherwise the record is cleared.
     */
    fun recheck(shell: ShellExecutor, store: KeyValueStore, currentBoot: String?): List<Outcome>? {
        val boot = store.getString(KEY_BOOT) ?: return null
        if (currentBoot.isNullOrBlank() || currentBoot == boot) return null
        val outcomes = saved(store).map { (pkg, bucket) ->
            val found = read(shell, pkg)
            if (found == BackgroundRestrictor.BUCKET_RESTRICTED && bucket != BackgroundRestrictor.BUCKET_RESTRICTED) {
                shell.run("am set-standby-bucket $pkg $bucket")
                Outcome(pkg, bucket, found, read(shell, pkg), setAgain = true)
            } else {
                Outcome(pkg, bucket, found, found, setAgain = false)
            }
        }
        store.putStringNow(KEY_BUCKETS, null)
        store.putStringNow(KEY_BOOT, null)
        return outcomes
    }

    /** The record, keeping only valid package names and settable buckets: nothing else reaches the shell. */
    internal fun saved(store: KeyValueStore): List<Pair<String, Int>> =
        store.getString(KEY_BUCKETS).orEmpty().split(',').mapNotNull { entry ->
            val pkg = entry.substringBefore(':').trim()
            val bucket = entry.substringAfter(':', "").trim().toIntOrNull()
            if (GameRegistry.isValidPackageName(pkg) && bucket != null && bucket in SETTABLE) pkg to bucket else null
        }

    private fun read(shell: ShellExecutor, pkg: String): Int? =
        BackgroundRestrictor.parseBucket(shell.capture("am get-standby-bucket $pkg"))
}
