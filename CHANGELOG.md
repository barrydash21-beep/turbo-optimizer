# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
