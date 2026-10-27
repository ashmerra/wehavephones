# Mobile touch controls

Settings → **Mobile touch controls (experimental)** is an opt-in alternative
to the controller layout. It is off by default. Close the game before changing
it, then launch again.

The WoW interface uses one scale, 150% by default. Bars, bags, portraits, quest
windows, tooltips and text inherit that scale. **More → Touch interface and
size** offers 125%, 150% and 175% for the whole interface.

On Classic Era, 24 stock action buttons form a six-column, four-row grid at the
bottom right. Another 12 buttons occupy two rows in the bottom middle. The
movement stick stays on the left. Chat sits above it, and the bars leave room
for the XP bar below. The primary 12 buttons keep WoW's action page and stance
behaviour. The extra groups use WoW's existing additional action slots. They
do not copy or erase the player's action assignments.

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
| Camera turn | Drag in the right area above the hotbars |
| Camera zoom | Pinch with two fingers in the camera area |
| Drag a window, slider, spellbook entry or bag item | Switch to window gestures through More, then drag |
| Scroll a list | Use two fingers in window mode |
| Jump, nearest enemy, interact with the selected target | Jump, Target and Use buttons |
| Previous target, auto run, sit or stand | More |
| Open chat, enter names, search, type text | More → Chat or Keyboard |
| Shift-click links or split stacks, Ctrl-click previews, Alt-click self-casts | Choose the modifier in More, then tap or hold |
| Cancel a ground-targeted cast or close a window | Escape |
| Change the main action page | More → Next action bar page |

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

Run `scripts/test-wehavephones-touch.sh` for gesture ownership, long-press
suppression, dragging, pinch/scroll separation, addon folder preservation,
uniform scaling, action selection, Undo and combat guards. It uses the host
JDK, Python and a Lua 5.2 or 5.4 shared library. The existing gamepad, launch,
runtime, addon, asset and memory checks cover the controller and launch paths.

## Device validation

Validated on a Samsung S23+ with Classic Era 1.15.9.70576 on October 26, 2026.
A fresh launch confirmed the right and middle grids, chat above the joystick,
the touch interface menu and all three scale presets. Long presses opened the
picker on slots 49 and 61. Selecting Mark of the Wild placed it without casting,
and a subsequent tap cast it. Undo restored both test slots to their original
empty state. Bags, window mode, the chat keyboard and one-shot Shift stack
splitting were checked. The stack dialog was cancelled without changing items.
The final Android crash buffer was empty.

The touch, gamepad, launch, runtime, addon, asset, memory and device host checks
passed. Dragging, pinch/scroll separation and combat guards have automated host
coverage. Extended play and the compatibility cases above remain unverified.
