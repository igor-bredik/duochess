# DuoChess

Android app that shows Stockfish's verdict on each move in Duolingo Chess, in the game review
and during a game.

- Captures the screen, finds the board in the starting position and follows the moves as you
  step forward and back through the review (or as they are played).
- Can also start from the position now on the board ("Позиция" button, then choose who is to
  move). This needs the piece looks, which are remembered from the last starting position seen.
- A small draggable panel over Duolingo shows the last move's judgement (best / good /
  inaccuracy / mistake / blunder), the better move and the evaluation with the best move now.
  It collapses into a small bubble with the evaluation; a tap opens it again.
- Arrows on the board (best move, played move) can be switched off and stay off until switched
  back on.
- Stockfish runs on the phone, no internet needed.

## Install

Download `duochess.apk` from the latest release:
https://github.com/igor-bredik/duochess/releases/latest/download/duochess.apk

## Build

GitHub Actions builds Stockfish 11 with the Android NDK and the APK with Gradle on every push to
`main`, and publishes it as the `latest` release.

## Licence

Stockfish is free software under the GNU GPL v3 (https://github.com/official-stockfish/Stockfish).
This app bundles an unmodified Stockfish 11 build; its source is fetched from the official
repository during the build.
