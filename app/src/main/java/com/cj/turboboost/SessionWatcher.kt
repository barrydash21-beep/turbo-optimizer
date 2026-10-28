package com.cj.turboboost

import java.util.Base64

/**
 * The session's watcher: a shell loop run through Shizuku that sees the game exit and samples
 * it, because HiOS freezes this app's uid 5 s after it leaves the foreground, even with
 * [GameMonitorService] in the foreground (seen on a Pova 7, HiOS 16.3: 0 samples over a
 * 16-minute session). The Shizuku shell is not frozen, as [GameResolution.startWatcher] already
 * relies on.
 *
 * Every [TICK_MS] it checks `pidof`; every [FRAME_READ_MS] it appends the game's newly presented
 * frames, every [THERMAL_READ_S] the thermal status and `peak_refresh_rate`, all to [DATA], with
 * the battery temperature at the start and the end. The session starts once the game is the app
 * in front, not when a process of it is found in the background. When the game is gone for
 * [MISSES_TO_EXIT] checks in a row, its process changes, or it is not in front within
 * [LAUNCH_GRACE_S], it makes a synchronous `content call` into
 * [SessionProvider], which thaws this app (HiOS lets a sync binder call through; it holds a
 * broadcast back), and the app ends the session itself. The watcher never restores anything.
 *
 * A changed process is a restart only when the game is in front: on the Pova 7 the game comes
 * back as a new background process about 3 s after it is swiped away, which is an exit.
 *
 * It stops on its own after [MAX_LIFETIME_S], or at its next tick once [TOKEN] is gone, which
 * is how the app stops it. The token is the session's, and the provider refuses a call without
 * it.
 */
object SessionWatcher {

    const val BASE = "/data/local/tmp/turboboost_session"
    const val TOKEN = "$BASE.token"
    const val DATA = "$BASE.data"
    private const val PID = "$BASE.pid"
    private const val SCRIPT = "$BASE.sh"

    /**
     * The loop's tick: how often it checks that the game is still running, and the shortest
     * frame-read interval possible. Each tick sleeps only what is left of it after its work.
     */
    const val TICK_MS = 2_000

    /**
     * How often it reads the game's frames; no shorter than [TICK_MS]. SurfaceFlinger keeps
     * 128 frames per layer, about 125 filled: 2.08 s at 60 fps on the Pova 7. Reads there landed
     * 2.05-2.1 s apart (sleep overshoots by about 70 ms), so about half followed on from the
     * read before and the rest missed up to 3 frames. Each read says whether it missed any,
     * and a missed stretch is never counted as a frame. 0 reads none, for the observer-effect
     * test.
     */
    const val FRAME_READ_MS = 2_000

    const val THERMAL_READ_S = 30
    const val LAUNCH_GRACE_S = 90
    const val MISSES_TO_EXIT = 3
    const val MAX_LIFETIME_S = 3 * 60 * 60

    /** A provider call that did not end the session is tried again this often, this many times. */
    const val END_RETRY_S = 5
    const val END_RETRIES = 60

    /** A frame read with fewer rows than this re-resolves the layer, at most every [LAYER_RETRY_S]. */
    private const val MIN_ROWS = 10
    private const val LAYER_RETRY_S = 10

    /**
     * Layers tried per resolve, keeping the one with the most frames, as [SessionSampler] does:
     * a window's input sink also carries the package name and never holds a frame.
     */
    private const val MAX_CANDIDATES = 3

    private val AUTHORITY = Regex("""^[A-Za-z0-9._]+$""")
    private val HEX = Regex("""^[0-9a-f]{32}$""")

    /**
     * The script for [pkg], ending sessions through [authority]. Written with `@` for the
     * shell's `$`, which a Kotlin string would otherwise read as a template; nothing in it
     * uses a literal `@`. Values are only the validated package, the authority and constants.
     */
    fun script(pkg: String, authority: String): String {
        require(GameRegistry.isValidPackageName(pkg)) { "not a package name" }
        require(AUTHORITY.matches(authority)) { "not an authority" }
        return """
            B=$BASE; P=$pkg; A=$authority; D=@B.data
            echo @@ > @B.pid
            ms() { t=@(cut -d' ' -f1 /proc/uptime); echo @((@{t%.*} * 1000 + 1@{t#*.} * 10 - 1000)); }
            rows() { dumpsys SurfaceFlinger --latency "@1" 2>/dev/null | awk 'NR > 1 && @2 > 0 && @2 < 9e18 { n++ } END { print n + 0 }'; }
            layer() {
              dumpsys SurfaceFlinger --list 2>/dev/null | sed -e 's/^RequestedLayerState{//' -e 's/ parentId=.*@//' -e 's/}@//' |
                grep -F "@P/" | grep -vE 'ActivityRecord|InputSink|Surface[(]name=|leash|Bounds for|Background for|Task=|Splash Screen' > @B.layers
              { grep -F 'SurfaceView[' @B.layers | grep -F '(BLAST)'; grep -F 'SurfaceView[' @B.layers | grep -vF '(BLAST)'; grep -vF 'SurfaceView[' @B.layers; } |
                head -$MAX_CANDIDATES > @B.cand
              best=; most=0
              while IFS= read -r c; do n=@(rows "@c"); [ @n -gt @most ] && { most=@n; best=@c; }; done < @B.cand
              rm -f @B.layers @B.cand
              echo "@best"
            }
            thermal() {
              dumpsys thermalservice 2>/dev/null | awk '/IsStatusOverride: true/ { o = 1 } /Thermal Status:/ && s == "" { s = @3 }
                END { if (o) print "o"; else if (s == "") print "-"; else print s }'
            }
            front() { dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity=' | grep -qF " @P/"; }
            battery() { dumpsys battery 2>/dev/null | awk '/temperature:/ { print @2; exit }'; }
            T0=@(ms); G=; M=0; L=; LR=-999999; LAST=0; LF=-999999; LT=-999999; R=
            while :; do
              [ -f @B.token ] || { rm -f @B.pid; exit 0; }
              T=@(ms)
              if [ @((T - T0)) -ge ${MAX_LIFETIME_S * 1000L} ]; then echo "X @T expired" >> @D; rm -f @B.pid; exit 0; fi
              p=" @(pidof @P) "
              if [ "@p" != "  " ]; then
                if [ -z "@G" ]; then
                  # A process in the background is not a session: it starts once the game is in front.
                  if front; then G=@(echo @p); G=@{G%% *}; echo "S @T @G" >> @D; echo "B @T @(battery)" >> @D; fi
                else
                  # A new process: relaunched in front is a restart; one in the background is the
                  # system starting the game again after it closed.
                  case "@p" in *" @G "*) ;; *) if front; then R=restarted; else R=exited; fi; break ;; esac
                fi
              elif [ -n "@G" ]; then
                M=@((M + 1)); [ @M -ge $MISSES_TO_EXIT ] && { R=exited; break; }
              fi
              if [ -z "@G" ]; then
                [ @((T - T0)) -ge ${LAUNCH_GRACE_S * 1000} ] && { R=never_started; break; }
              elif [ "@p" != "  " ]; then
                M=0
                if [ $FRAME_READ_MS -gt 0 ] && [ @((T - LF)) -ge $FRAME_READ_MS ]; then
                  LF=@T
                  if [ -z "@L" ] && [ @((T - LR)) -ge ${LAYER_RETRY_S * 1000} ]; then LR=@T; L=@(layer); fi
                  if [ -n "@L" ]; then
                    line=@(dumpsys SurfaceFlinger --latency "@L" 2>/dev/null | awk -v last="@LAST" -v ms="@T" '
                      NR > 1 && @2 > 0 && @2 < 9e18 {
                        n++; if (o == "" || @2 < o) o = @2
                        if (@2 > last) { if (b == "") { b = @2; s = " " @2 } else s = s " " (@2 - q); q = @2 }
                      }
                      END { c = (last > 0 && o != "" && o <= last) ? 1 : 0; printf "F %s %d %d%s", ms, c, n + 0, s }')
                    echo "@line" >> @D
                    set -- @line
                    [ "@4" -lt $MIN_ROWS ] && L=
                    [ @# -ge 5 ] && LAST=@(echo "@line" | awk '{ s = @5; for (i = 6; i <= NF; i++) s += @i; printf "%d", s }')
                  fi
                fi
                if [ @((T - LT)) -ge ${THERMAL_READ_S * 1000} ]; then
                  LT=@T; echo "T @T @(thermal)" >> @D; echo "P @T @(settings get system peak_refresh_rate 2>/dev/null)" >> @D
                fi
              fi
              # Only what is left of the tick: a plain sleep adds the work above to every tick.
              w=@((T + $TICK_MS - @(ms)))
              [ @w -gt 0 ] && sleep @((w / 1000)).@(printf %03d @((w % 1000)))
            done
            E=@(ms)
            [ -n "@G" ] && echo "B @E @(battery)" >> @D
            echo "E @E @R" >> @D
            i=0
            while [ -f @B.token ] && [ @i -lt $END_RETRIES ]; do
              out=@(content call --uri content://@A --method ${SessionProvider.METHOD_END} --arg "@(cat @B.token)" --extra reason:s:@R 2>&1)
              case "@out" in *ok=true*) break ;; esac
              i=@((i + 1)); sleep $END_RETRY_S
            done
            rm -f @B.pid
        """.trimIndent().replace('@', '$') + "\n"
    }

    /**
     * Starts the watcher for [pkg] under [token], replacing any watcher still running. True
     * only when it is seen running; a false start leaves none behind, so the caller can fall
     * back to sampling in-process without two samplers.
     */
    fun start(shell: ShellExecutor, pkg: String, token: String, authority: String): Boolean {
        if (!HEX.matches(token)) return false
        val encoded = Base64.getEncoder().encodeToString(script(pkg, authority).toByteArray())
        val started = shell.run(
            "${stopCommand()}; rm -f $DATA; umask 077; echo $token > $TOKEN; echo $encoded | base64 -d > $SCRIPT; " +
                "setsid sh $SCRIPT </dev/null >/dev/null 2>&1 & " +
                "i=0; while [ ! -f $PID ] && [ \$i -lt 20 ]; do sleep 0.1; i=\$((i+1)); done; " +
                "[ -f $PID ] && kill -0 \$(cat $PID)"
        ).ok
        if (!started) stop(shell)
        return started
    }

    /** Whether the watcher is running: ok, a definite no, or Shizuku could not be asked. */
    fun isAlive(shell: ShellExecutor): ShellResult = shell.run("[ -f $PID ] && kill -0 \$(cat $PID) 2>/dev/null")

    /** Removes the token, so a watcher that outlives this stops at its next tick, and stops it now. */
    fun stop(shell: ShellExecutor): Boolean = shell.run(stopCommand()).ok

    private fun stopCommand() = "rm -f $TOKEN; [ -f $PID ] && kill \$(cat $PID) 2>/dev/null; rm -f $PID $SCRIPT"

    /** The watcher's data file, or null when there is none or it cannot be read. */
    fun collect(shell: ShellExecutor): WatcherData? = shell.capture("cat $DATA")?.let(::parse)

    fun clearData(shell: ShellExecutor): Boolean = shell.run("rm -f $DATA").ok

    /** Lines that are not exactly one of the watcher's own are skipped: the file is evidence, never trusted. */
    fun parse(text: String): WatcherData {
        var seenAt: Long? = null
        var pid: String? = null
        var endAt: Long? = null
        var reason: String? = null
        var lastAt: Long? = null
        val batteries = mutableListOf<Float>()
        val events = mutableListOf<WatcherEvent>()
        for (line in text.lineSequence()) {
            val f = line.trim().split(' ').filter { it.isNotEmpty() }
            val at = f.getOrNull(1)?.toLongOrNull() ?: continue
            when (f[0]) {
                "S" -> if (seenAt == null) {
                    seenAt = at
                    pid = f.getOrNull(2)?.takeIf { p -> p.all { it.isDigit() } }
                }
                "F" -> parseFrames(at, f)?.let { events += it }
                "T" -> events += WatcherEvent.Thermal(at, f.getOrNull(2)?.toIntOrNull(), f.getOrNull(2) == "o", null)
                "P" -> {
                    // Read in the same tick as the thermal status: one sample, as in-process sampling takes it.
                    val peak = f.getOrNull(2)?.toFloatOrNull()
                    val previous = events.lastOrNull()
                    if (previous is WatcherEvent.Thermal && previous.atMs == at) {
                        events[events.lastIndex] = previous.copy(peak = peak)
                    } else {
                        events += WatcherEvent.Thermal(at, null, false, peak)
                    }
                }
                // Tenths of a degree, as `dumpsys battery` prints it.
                "B" -> f.getOrNull(2)?.toIntOrNull()?.let { batteries += it / 10f }
                "E" -> if (endAt == null) {
                    endAt = at
                    reason = f.getOrNull(2)?.takeIf { EndReason.fromCode(it) != null }
                }
                else -> continue
            }
            lastAt = maxOf(lastAt ?: at, at)
        }
        return WatcherData(
            seenAt, pid, endAt, reason, lastAt, events,
            batteryStart = batteries.firstOrNull(),
            batteryEnd = batteries.drop(1).lastOrNull()
        )
    }

    /** `F <ms> <contiguous> <rows> [<first> <delta>...]`, the deltas in ns from the frame before. */
    private fun parseFrames(at: Long, f: List<String>): WatcherEvent.Frames? {
        val contiguous = f.getOrNull(2)?.let { it == "1" } ?: return null
        val rows = f.getOrNull(3)?.toIntOrNull() ?: return null
        val values = f.drop(4).map { it.toLongOrNull() ?: return null }
        val presents = mutableListOf<Long>()
        values.forEachIndexed { i, v -> presents += if (i == 0) v else presents.last() + v }
        if (presents.any { it <= 0 }) return null
        return WatcherEvent.Frames(at, contiguous, rows, presents)
    }
}

sealed interface WatcherEvent {
    val atMs: Long

    /** One frame read: the frames presented since the last one, and whether none were missed in between. */
    data class Frames(override val atMs: Long, val contiguous: Boolean, val rows: Int, val presentsNs: List<Long>) : WatcherEvent

    /** One thermal read: the status, or null with [overridden] when it was forced; the peak rate held. */
    data class Thermal(override val atMs: Long, val status: Int?, val overridden: Boolean, val peak: Float?) : WatcherEvent
}

/** What the watcher wrote. Times are `/proc/uptime` in ms, the same clock as `elapsedRealtime`. */
data class WatcherData(
    /** When the game was first seen running, or null if it never was. */
    val seenAtMs: Long?,
    val gamePid: String?,
    val endedAtMs: Long?,
    /** [EndReason.code] the watcher ended on, or null if it has not ended. */
    val endReason: String?,
    /** The newest time on any line. */
    val lastAtMs: Long?,
    val events: List<WatcherEvent>,
    /** Battery temperature in degrees C when the game came to the front, and when the watcher ended. */
    val batteryStart: Float? = null,
    val batteryEnd: Float? = null
) {
    val frameReads: Int get() = events.count { it is WatcherEvent.Frames }

    /**
     * Feeds every read into [session], oldest first, as in-process sampling would. A read with
     * no frames missed since the last is chained to the last frame, so the gap between reads
     * is measured too; after a gap it starts over, so the gap is never counted as one frame.
     */
    fun foldInto(session: SessionAccumulator) {
        var newest = 0L
        for (event in events.sortedBy { it.atMs }) {
            when (event) {
                is WatcherEvent.Frames -> {
                    val fresh = event.presentsNs.filter { it > newest }.sorted()
                    val chained = event.contiguous && newest > 0L && fresh.isNotEmpty()
                    session.addSample(event.atMs, null, if (chained) listOf(newest) + fresh else fresh, chained = chained)
                    fresh.lastOrNull()?.let { newest = it }
                }
                is WatcherEvent.Thermal -> session.addSample(event.atMs, event.status, emptyList(), event.peak)
            }
        }
    }
}
