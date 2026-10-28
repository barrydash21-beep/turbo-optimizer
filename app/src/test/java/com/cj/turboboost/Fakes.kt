package com.cj.turboboost

/** A neutral package name for tests: no real game. */
const val FAKE_GAME = "com.example.game"

/** Records every command and answers from a scripted map. No device, no Shizuku. */
class FakeShell(
    private val available: Boolean = true,
    /** Exact command -> stdout, for [capture]. */
    private val captures: Map<String, String> = emptyMap(),
    /** Commands with any of these prefixes exit non-zero. */
    private val failingPrefixes: Set<String> = emptySet(),
    private val apiUnsupported: Boolean = false
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable(): Boolean = available

    override fun run(command: String): ShellResult {
        commands += command
        return when {
            apiUnsupported -> ShellResult.apiUnsupported()
            failingPrefixes.any { command.startsWith(it) } -> ShellResult(exitCode = 1)
            else -> ShellResult.success()
        }
    }

    override fun capture(command: String): String? {
        commands += command
        return captures[command]
    }

    fun ran(prefix: String): Boolean = commands.any { it.startsWith(prefix) }
}

/**
 * [FakeShell] plus GameManager's `cmd game mode` / `list-modes`, so a mode switch can be read
 * back. [offered] are the modes the game has; a switch to any other is refused, as AOSP does.
 */
class GameModeShell(
    var mode: String = "standard",
    var offered: List<String> = listOf("standard", "performance", "battery", "custom"),
    /** `cmd game mode` exits 0 but the mode does not change. */
    var ignoresSwitch: Boolean = false,
    private val available: Boolean = true
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable(): Boolean = available

    override fun run(command: String): ShellResult {
        commands += command
        val p = command.split(" ")
        if (p.size == 5 && p[0] == "cmd" && p[1] == "game" && p[2] == "mode") {
            if (p[3] !in offered) return ShellResult(exitCode = 255)
            if (!ignoresSwitch) mode = p[3]
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        val p = command.split(" ")
        return if (p.size == 4 && p[1] == "game" && p[2] == "list-modes") {
            "${p[3]} current mode: $mode, available game modes: [${offered.joinToString(",")}]"
        } else {
            null
        }
    }

    fun ran(prefix: String): Boolean = commands.any { it.startsWith(prefix) }
}

/** In-memory [KeyValueStore]. */
class FakeStore : KeyValueStore {
    private val strings = mutableMapOf<String, String?>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        strings[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun clear() {
        strings.clear()
        booleans.clear()
    }

    val isEmpty: Boolean get() = strings.isEmpty() && booleans.isEmpty()
}

class FakeSyncSettings(
    var enabled: Boolean = true,
    private val writable: Boolean = true
) : SyncSettings {
    override fun isMasterSyncEnabled(): Boolean = enabled
    override fun setMasterSyncEnabled(enabled: Boolean): Boolean {
        if (!writable) return false
        this.enabled = enabled
        return true
    }
}
