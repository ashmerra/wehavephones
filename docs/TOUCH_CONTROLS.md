# Mobile touch controls

Settings → **Mobile touch controls (experimental)** is an opt-in alternative
to the controller layout. It is off by default. Close the game before changing
it, then launch again.

The WoW interface uses one scale, 150% by default. Bags, portraits, quest
windows, tooltips and text inherit that scale. Action buttons have a larger
base size, 35% bigger than the earlier touch layout, and follow the same scale
presets. **More → Touch interface and size** offers 125%, 150% and 175%.

On Classic Era, eight large stock action buttons form a four-column, two-row
grid at the bottom right. Four more sit in one low row near the bottom middle.
The movement stick stays on the left, with chat above it. The camera area
extends down toward these groups and farther toward the center, leaving a
broad space for natural thumb swipes. The XP bar stays below the actions.
Action tooltips appear over the chat window, keeping the bars and camera area
clear.

The compact **< number >** switcher beside the middle row cycles through all
six normal WoW action pages, including the slots previously shown in the
extra grids.
Only 12 actions are visible at once. Both groups change together. The arrows
are secure action buttons and stay usable in combat. The primary buttons keep
WoW's stance behaviour and existing action assignments. Paging never copies
or erases actions. The two extra bottom bars are hidden for this mode and
their prior visibility is restored on opt-out.

Touch panels, the movement overlay and the blue **More** selector use the
app's navy shell, blue keys and silver text, matching the controller overlay.

## Editing actions

Hold a hotbar slot for about half a second. Releasing the hold does not cast
its spell. A picker opens with **Spells**, **Items** and **Macros**. Tap a choice
to place it in that slot. Passive and unlearned spells are excluded. Spells
retain their rank. Items come from the player's bags, and macros include both
account and character macros.

**Clear slot** removes its action. **Undo** restores the last edited slot.
**Cancel** leaves the slot unchanged. The picker refuses to open while an item
is already on the cursor, and closes when combat starts. All edit callbacks
check combat again before changing anything. These are the game's own secure
action buttons, so normal casts, cooldowns, action counts and targeting remain
under WoW's control.

## Inputs for WoW

| Input | Touch control |
| --- | --- |
| Left click, select a unit, cast, place a ground-targeted spell | Tap the game UI |
| Right click, interact with a unit or corpse, use or equip an item, open a context menu | Hold, then release |
| Change a hotbar action | Hold the slot, then use the picker |
| Movement | Left stick, including diagonal movement and strafing |
| Camera turn | Drag in the broad center-right area above the hotbars |
| Camera zoom | Pinch with two fingers in the camera area |
| Drag a window, slider, spellbook entry or bag item | Switch to window gestures through More, then drag |
| Scroll a list | Use two fingers in window mode |
| Jump, nearest enemy, interact with the selected target | Jump, Target and Use buttons |
| Previous target, auto run, sit or stand | More |
| Open chat, enter names, search, type text | More → Chat or Keyboard |
| Shift-click links or split stacks, Ctrl-click previews, Alt-click self-casts | Choose the modifier in More, then tap or hold |
| Cancel a ground-targeted cast or close a window | Escape |
| Change the action page | Tap the visible < or > arrows, or More → Next action bar page |

Window mode replaces camera drags with left mouse drags, including over the
right side of the screen. The hint changes to **Window gestures**. Use More
again to return to camera mode. A modifier applies to one tap, hold or drag,
then releases. Held mouse buttons and modifiers also release on cancellation
or loss of window focus.

Movement, camera and spell taps have independent pointer ownership. Tapping a
spell releases an active camera grab so WoW can receive an absolute click.
Moving the camera finger again resumes looking. A pinch or scroll never
produces a leftover click when its fingers lift.

The touch interface panel also opens Character, Spellbook, Quests, Talents,
Social and the game menu. The existing session side menu provides Exit.

## Preservation and compatibility

The mode suppresses the standard virtual gamepad, including a manually
selected input profile. It temporarily disables the ConsolePort suite and the
controller setup addon. Their saved settings and the player's saved bindings
remain intact.

Turning the mode off restores only the controller folders it disabled.
Already disabled modules stay disabled. The companion addon remains installed
so each character can restore its saved UI CVars, root scale, extra-bar
visibility and chat layout on its next login. Temporary binding overrides are
never saved. Deliberate hotbar edits remain the player's ordinary WoW actions.

Classic Era is the validation target. Other WoW versions receive the global
scale and native gestures but retain their existing bar layout. Third-party
bar replacements, pets, class-specific stance changes and extended combat
sessions need separate validation. Third-party quest trackers may need manual
repositioning above the action grid. The implementation follows Blizzard's
[1.15.9 UI source](https://github.com/Gethe/wow-ui-source/tree/1.15.9), including
the stock action buttons, spellbook and Edit Mode anchoring.

Run `scripts/test-wehavephones-touch.sh` for camera-area boundaries, gesture
ownership, long-press suppression, dragging, pinch/scroll separation, addon
folder preservation, uniform scaling, paged action selection, Undo, secure
paging and combat guards. It uses the host JDK, Python and a Lua 5.2 or 5.4
shared library. The existing gamepad, launch, runtime, addon, asset and memory
checks cover the controller and launch paths.

## Device validation

The previous layout was validated on a Samsung S23+ with Classic Era
1.15.9.70576 on October 26, 2026, including action editing, casting, Undo,
the scale presets, chat keyboard and one-shot Shift stack splitting.
On October 27, 2026, the updated build passed the touch, gamepad, launch,
runtime, addon, asset, memory and device host checks and was installed on the
same S23+. The Android crash buffer was empty. The phone was locked, which
prevented a fresh in-game check of the 8+4 layout, larger buttons, page arrows
and blue menus. WeHavePhones was closed after installation as requested.

A later Classic Era session on the same day confirmed the 8+4 layout, spell
tooltips over the chat window and the smaller number-only page selector.
Mark of the Wild cast normally, and paging changed the displayed number from
1 to 2 and back to 1. The touch and addon host checks and both builds passed.
There were no new Android app crash entries. The game exited normally and
WeHavePhones was closed after testing.

Dragging, pinch/scroll separation, page-button attributes and combat guards
have automated host coverage. Extended play and the compatibility cases
above still need in-game validation.
