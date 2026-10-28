# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- MEASURE ONLY, under BOOST: launches the selected game and records a session without changing anything (no background restriction, RAM purge, profile, refresh rate, stabilizer or preset), as a baseline to compare boosts with. It refuses to start while a boost's changes, a resolution preset or FPS cap, the stabilizer or another session are still on.
- Each session records whether it was a baseline or which profile it ran under, the game's Game Manager mode and configs at its start and end, and how much of it was sampled.
- Each session checks whether another app changed the game's mode or configs since this app last saw them, at its start and its end. A mode reset by a phone restart is expected; a changed config is not. With nothing to compare with, it says unknown.
- The Session comparison groups sessions by profile and the game's mode and configs too, so baselines and boosts never share a group. It leaves out sessions another app changed, sessions whose game restarted, and baselines that ran with a Game Manager config, and says how many it left out.

### Changed
- Diagnostics and the lower-rate suggestion ignore baselines.
- The profile's game mode (battery, standard or performance) is now saved before a boost switches it and put back when the session ends, on RESTORE, or at the next app start. Before, it was never put back: a BALANCED boost took a game out of a custom mode another app had set, and left it there. It is put back only while the game still holds the mode the boost set; one changed since, by another app or a phone restart, is left as it is. A mode the game doesn't offer is no longer sent.
- A session is now watched by a small helper that runs through Shizuku. On phones that freeze apps in the background (seen on HiOS 16, which froze the app 5 s after the game opened even with its notification showing), the app measured nothing and only put your settings back when you next opened it. The helper keeps measuring while the app is frozen, sees the game close, and wakes the app to end the session and put your settings back.
- Frame timing is read every 2 s while the helper runs, instead of every 30 s. When the helper can't start, the app monitors the session itself as before.
- A session ends in two steps: it is marked as ending before anything is put back, and as ended only once every setting reads back. One left unfinished (the app was closed, or a setting didn't stick) is finished the next time any path ends it, including the next app start, without saving its record twice.
- Putting the refresh rate back is now checked by reading it back.
- A session in which the game's process restarted is marked "game restarted" in its summary and left out of the Session comparison.

## [1.2.4] - 2026-10-23

### Added
- THERMAL FPS CAP on each game's card, under the render resolution: OFF, 45, 40 or 30 FPS through Android's game mode (Android 14 and later). It asks first, like the resolution options, and takes effect the next time the game opens. It can only lower the frame rate, never raise it above the game's own setting, and a game may ignore it.
- The cap and the render resolution are written together, so changing one keeps the other. A cap is shown as set only when Android reads it back. The card then shows the average FPS of the last session played at that cap, or "Not measured yet", and notes that the read-back alone does not prove the game follows it.
- The cap is part of the snapshot and restore the resolution already uses: DEFAULT with the cap OFF puts back the mode and settings the game had before, including a frame-rate setting of its own. A cap changed outside the app is reported and only overwritten if you confirm. After a phone restart, Turbo sets the cap again with the resolution when it launches the game.
- Each session now records the cap it ran under. Sessions saved by earlier versions still load, with no cap.
- A Diagnostics section, read-only: Android version, device, Shizuku, whether `cmd game` works for the selected game, its current mode and config, the resolution and FPS cap levers, thermal status, and the apps whose standby bucket change was ignored. Each row says Supported, Unsupported, Unverified or Not checked, with one line of evidence. Supported needs a read-back. The FPS cap is Supported only when a session at the cap averaged 0.85 to 1.1 times the cap and a session of the same game at the same resolution without a cap averaged at least 1.2 times the cap; the row shows both averages. It checks only when opened or on RE-CHECK, and COPY REPORT copies it as plain text.
- A Session comparison section, read-only: pick two groups of the selected game's saved sessions, grouped by refresh rate, render resolution and FPS cap, and compare run count, median average FPS, median 1% low, median time to thermal status 1 (and how many runs never reached it) and the worst thermal status. A difference shows only when both groups have at least 3 runs. Only the newest 20 sessions are kept.

### Changed
- If the app is closed in the middle of a resolution or cap change, the next start finishes recording it when the game holds what was asked, or reports the game's mode as changed outside Turbo with RESTORE ANYWAY.

## [1.2.3] - 2026-10-18

### Added
- Restrict background apps during a session (on by default, under the profile options). While the game runs, the apps on the list are kept from running in the background, then put back exactly as they were when it closes, on RESTORE, or on the next app start if the app was closed mid-session. It saves each app's background setting and standby bucket first, then restricts and stops them.
- An editable list of those apps. It starts with the installed ones among Facebook, Facebook Lite, Messenger, Facebook Services, Instagram and Google Messages. This app, Shizuku, the game, the launcher, keyboard, SMS app and dialer, system UI and Play services are protected and can't be added. On HiOS, AIService is offered as an optional entry when it is installed.
- The session summary shows which restricted apps were still running 30 seconds after the session started and when the game closed.

### Notes
- Some phones ignore the standby bucket change for apps they exempt from battery optimization (seen on HiOS 16 for most of the default list). The list shows this per app; the background restriction and the stop still apply.

## [1.2.2] - 2026-10-14

### Changed
- Tapping a render resolution option on a game's card now asks first. APPLY sets the preset and RESTORE puts back DEFAULT; CANCEL leaves the game untouched. Tapping the option already selected does nothing.

## [1.2.1] - 2026-10-14

### Added
- Two stronger render resolution presets on each game's card: LOWER (0.55x) and LOWEST (0.5x), next to MEDIUM (0.65x) and LOW (0.6x). Android applies the factor relative to the display, so a game that already caps its own render size changed little at 0.65 and 0.6.

### Changed
- Every option on the card now shows its factor, and the card notes that lower factors cut GPU work but look softer and that the effect depends on the game's own render cap. The measured "Renders at ..." line after the next launch is unchanged and is still the only size shown.
- Presets chosen in 1.2.0 load as before, and apply, DEFAULT, the "changed outside Turbo" confirm and the restart handling work the same for the new ones.

## [1.2.0] - 2026-10-07

### Changed
- Render resolution is now set per game, on the game's card: DEFAULT, MEDIUM or LOW. It uses Android's game mode downscaling (Android 14 and later), so only the game renders smaller. The screen, system UI and gestures are no longer touched. Hidden on older Android versions.
- After the game's next launch the card shows what it actually renders at, compared with its own default ("Renders at 648x1472 (−19% pixels)"), or says the preset had no effect because the game already renders below it.
- A change made while the game is running applies the next time it opens. The card offers Restart now, which asks first because it closes the game.
- DEFAULT puts back the game mode and settings the game had before. If they were changed outside the app since, it says so and only overwrites them if you confirm.
- After the phone restarts, Android puts every game back in its default mode, which switches a preset off. Turbo sets it again when it launches the game (BOOST or Restart now), and the card says so in the meantime.
- A boost no longer switches the game mode of a game that has a resolution preset, which would have cancelled the preset.

### Removed
- The display-wide resolution presets (83% and 67%, using `wm size` and `wm density`), the 15-second "Keep this resolution?" check and its notification. On some phones (seen on HiOS 16) any screen size override broke swipe-up to Recents in portrait and scaled system UI unevenly.
- The app no longer undoes display overrides by itself on start. If one is active the first time this version opens, it asks once whether to reset it to the device default.

## [1.1.0] - 2026-10-05

### Added
- Render resolution: pick Native, about 83% or about 67% of the screen width before launching a game. Sizes are worked out from the display's own resolution, keep its shape, never go below half, and scale text and icon size to match.
- A 15-second "Keep this resolution?" check after applying. With no answer the screen switches back, even if the app is in the background.
- Restore default on the resolution card and in the notification. The resolution is also restored when the game closes, and on the next app start after a crash or force-close.
- The resolution can only be changed before launch: applying is blocked while the selected game is running, and the card says why whenever it can't be used.
- The session summary shows the render resolution the game ran at.

### Fixed
- Restore normal settings no longer deletes your refresh rate setting when no boost is active.

## [1.0.0] - 2026-10-01

First public release as TurboBoost (`com.cj.turboboost`).

### Added
- Add any installed app as a game from a picker, and remove it again. There is no built-in game list.
- Per-game refresh rate (60/90/120 Hz, limited to what the display supports), held until the game closes.
- Three profiles: Saver, Balanced and Turbo.
- Background app cleanup through Shizuku, without root.
- Optional local VPN that blocks background traffic from apps other than your games.
- Game monitor that restores your settings when the game closes, plus a session summary with frame timing and thermal status.
- Snapshot of your settings before the first change, and a Restore action that writes them back.
- Package names are validated before they reach any shell command.

### Removed
- Turning off thermal throttling. The device's thermal protection always stays on.
- Hard-coded game list.
