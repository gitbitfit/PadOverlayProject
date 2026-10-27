# Design notes

## Goal
Put the on-screen controls in black bars beside the game on ANY phone, not only
phones that have a built-in "free display" / aspect-ratio mode (e.g. RedMagic Game Space).

## Chosen method (implemented): forced freeform window
- Shizuku (shell) turns on freeform support and force-resizable activities:
  settings put global enable_freeform_support 1
  settings put global force_resizable_activities 1
- Launches the game: am start-activity --windowingMode 5 -n <component>
- Finds the task id in `dumpsys activity activities`, then: am task resize <id> L T R B
  (works only for freeform / multi-window tasks; newer builds take spaces, older ones commas,
  so both forms are tried).
- The overlay then puts the pods in the leftover bars (Window mode layout).
Top gap (current approach): the system does not let a task move above the screen, so shifting the
window up only pushed the bottom off-screen. Instead the window is shrunk so the picture fits
exactly under the blank strip; the launcher saves that content rect and the overlay covers the
strip (and the sides) in black.
(Earlier idea, did not work: move the task to a negative top.)
Top gap: a freeform window reserves a caption bar at the top (often empty on phones), so the
game's picture starts ~100px below the window top. launchWindowed() reads the caption inset
from `dumpsys window windows`, then moves the task up by that amount (negative top) so the
picture reaches the top edge. Details are written to the log file.
Stray taps: tapping the window behind the game can bring that app in front of it. The overlay
adds four black windows around the game window (Window mode, "Black surround") that swallow
touches there; overlay windows do not change the game's focus or stacking.
Limits: needs freeform support on the phone; game must already be closed; some games ignore
resizing or crash; the log is /sdcard/Download/padoverlay-log.txt.

## Alternative (kept for later): virtual display mirrored into the app
Use this if freeform turns out to be unreliable across phones.
1. Create a virtual display with the shape you want (e.g. 1920x1080):
   DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags)
   flags: VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY.
2. The Surface comes from a SurfaceView in our own full-screen activity, drawn letterboxed
   in the middle, with the controls in the empty sides.
3. Launch the game onto that display through Shizuku:
   am start-activity --display <displayId> -n <component>
   (As far as I know scrcpy's "new display" mode does the same thing.)
4. Touch: inject MotionEvents with the virtual display's id and coordinates in its own space.
   Keys / gamepad go to the focused display, so routing needs testing.
Pros: works without freeform; any shape and resolution; game is unmodified.
Cons: extra GPU copy (small); some games refuse secondary displays; focus and gamepad routing
are the risky parts; more code than the freeform method.

## Rejected
- Repackaging / patching games: breaks signatures (updates, licence checks, cloud saves),
  can get accounts banned by anti-cheat, needs a different patch per engine.
- Virtual machine: heavy and slow.
- `wm size` trick: gives bars, but they lie outside the logical display, so overlay windows
  cannot be placed in them. (Not tested.)
