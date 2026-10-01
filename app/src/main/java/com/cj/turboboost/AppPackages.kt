package com.cj.turboboost

/**
 * Every non-game package name the booster knows about, in one place. Games are whatever the
 * user adds (see [BoosterPrefs.games]); the manifest `<queries>` block must list these.
 */
object AppPackages {

    const val SHIZUKU = "moe.shizuku.privileged.api"

    /**
     * Transsion bloat the RAM purge stops even though it is a system app. Edit here.
     *
     * Only add a package after seeing it in `pm list packages` on the device and confirming it
     * is not a core service. HiOS names differ from XOS, so never copy names from another
     * Transsion device. All confirmed installed with `pm path` on a TECNO LJ6 (POVA 7,
     * HiOS 16.3.0) on 2026-09-30, with no telephony, input or launcher components.
     *
     * Deliberately absent: com.transsion.tranradionet is NOT the FM radio. It is a persistent
     * telephony service (InCallService, CallScreeningService, the 5G/NR switch tile).
     */
    val TRANSSION_BLOAT = listOf(
        "com.transsion.globalsearch",
        "com.transsion.personalizedService.hios", // ZeroScreen: the launcher's -1 feed
        "com.transsion.letswitch",               // phone clone
        "com.transsion.smartrecognition",
        "com.transsion.spacesaversdk",
        "com.transsion.trancare"
    )

    /**
     * Static fallback for the RAM purge, used only when the running third-party apps cannot
     * be enumerated at runtime.
     */
    val BACKGROUND_HOGS = listOf(
        "com.facebook.katana",
        "com.instagram.android",
        "com.zhiliaoapp.musically" // TikTok
    ) + TRANSSION_BLOAT

    /**
     * Never force-stopped, whatever else matches. The selected game, the launcher and the
     * keyboard are added at kill time; the user's other games are killable like any app.
     */
    val PROTECTED = listOf(
        BuildConfig.APPLICATION_ID,
        SHIZUKU,
        "com.google.android.gms",
        "com.google.android.gsf"
    )

    /** A package whose name contains any of these is never force-stopped. */
    val PROTECTED_NAME_PARTS = listOf("systemui", "phone", "telephony", "bluetooth", "inputmethod")

    /**
     * Apps always exempted from the traffic blocker, on top of the user's games.
     *
     * Facebook is deliberately absent: the RAM purge force-stops it, so exempting it from
     * the blocker contradicted the stabilizer's whole purpose (M13).
     */
    val VPN_BYPASS_BASE = listOf(
        SHIZUKU,
        "com.google.android.gms",     // Google Play Services
        "com.google.android.gsf"      // Google Services Framework
    )

    /**
     * Every one of the user's games is exempt, not just the selected one: the tunnel is only
     * torn down on Restore, so it may outlive the session and would otherwise cut off the
     * next game the user opens.
     */
    fun vpnBypass(games: List<String>): List<String> = (games + VPN_BYPASS_BASE).distinct()
}
