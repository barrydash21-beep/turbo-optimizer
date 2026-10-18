# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
