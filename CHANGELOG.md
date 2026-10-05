# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
