# MiniChess

Play in the official chess.com app on a keypad phone with the D-pad. Games are played in chess.com itself, so they count on your account. MiniChess only draws a marker over the board and taps the square you choose.

## Install

1. Download `MiniChess.apk` from the latest release.
2. `adb install -r MiniChess.apk`
3. Turn on MiniChess under Settings → Accessibility.

## Keys (in chess.com)

| Key | Action |
| --- | --- |
| D-pad | Move the marker |
| OK / 5 | Tap the square (piece first, then target) |
| 0 | After picking: free movement. Otherwise: clear the yellow highlight |
| 7 | Smart moves on/off (jump only between legal squares) |
| # | Pause / resume (menus work as normal when paused) |
| * | Calibrate the board |

Calibrate once: open a game, press `*`, move the pink cross to the board's top-left corner and press OK, then the bottom-right corner and OK.

MiniChess only taps where you tell it. It never suggests moves.
