# DuoChess

Android app that shows Stockfish's verdict on each move while you step through a game review in
Duolingo Chess.

- Captures the screen, finds the board in the starting position and follows the moves as you
  step forward and back through the review.
- A small draggable panel over Duolingo shows the last move's judgement (best / good /
  inaccuracy / mistake / blunder), the better move and the evaluation with the best move now.
- Stockfish runs on the phone, no internet needed.
- Hints are only shown in the review: if a ticking clock is seen (a live game), the panel stays
  silent.

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
