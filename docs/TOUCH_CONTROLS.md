# Mobile touch controls

Settings → **Mobile touch controls (experimental)** is an opt-in alternative
to the controller layout. It is off by default. Close the game before changing
it, then launch again.

The WoW interface uses a 150% scale by default. **More → Touch interface and
size** offers 125%, 150% and 175%. Large stock windows fit the available
workspace using their actual effective scale. This includes frames such as
the world map that ignore their parent's scale.

On wider Classic Era screens, eight stock action buttons form a four-column,
two-row grid at the bottom right. Four more sit near the bottom middle.
Tablet shapes use a four-column, three-row grid. Square shapes use three
columns and four rows. Both put the page switcher at the lower left. This
keeps actions larger than shrinking the wide layout into a tablet or square
viewport. Class, pet and possession controls have a
separate footer. The XP bar stays below them.

The layout reserves space for native thumb controls and caps its width on
ultrawide screens. Camera gestures follow the actual guest viewport,
including letterboxing and stretching. Narrow layouts leave camera space
above and beside the action grid. The stock party frames form a row above
chat and movement, retaining their own targeting, pet and aura behavior.
Group chat and its utility buttons fit below that row, then regain their
normal size when solo.
Stock chat also retains its touch anchor during combat. Protected replacement
chat frames defer their layout until combat ends.
Action and default world tooltips appear over chat. The stock quest tracker
and an existing Questie tracker fit above the right actions, with the
duplicate stock tracker hidden while Questie is shown.

## Play, windows and inspection

New sessions start with a small **Controls** tab so login and character
selection have the screen available. Drag the tab aside if it covers
something. Tap it to expand the toolbar, then tap **Play** after entering the
world to show movement, Target, Use and Jump.

**Windows** clears those controls and changes camera swipes into left mouse
drags. Map, Bags and Keyboard enter this mode directly. Its toolbar provides
Esc, Keyboard, Inspect, More and Play. **More → Collapse to movable Controls
tab** reduces it again for an unfamiliar addon or a large menu.

**Inspect** highlights its key in blue. Touch or slide over an item, spell or
other game element to read its tooltip without clicking, using or dragging
it. Tap Inspect again to interact. Touch mode enables stock equipment
comparison tooltips and restores the prior preference on opt-out.

Stock windows and visible bags fit together below the toolbar. The inventory
strip offers **Pack**, **Bag 1–4**, **All** and Classic's **Keys**, plus bank
bag pages while the bank is open. All reopens the complete player inventory
after viewing one bag. A single bag gives its item controls more room. A picked-up
item stays on the cursor when changing pages, ready for a normal placement
click. Bag paging is available out of combat. Opening, using and moving items
still uses the game's stock container buttons and rules.

The panels, movement overlay and More selector use the app's navy shell,
blue keys and silver text.

## Editing actions

Hold a hotbar slot for about half a second. Releasing the hold does not cast
its spell. A picker opens with **Spells**, **Items** and **Macros**. Tap a choice
to place it in that slot. Passive and unlearned spells are excluded. Spells
retain their rank. Items come from the player's bags. Macros include account
and character macros.

**Clear slot** removes its action. **Undo** restores the last edited slot.
**Cancel** leaves the slot unchanged. The picker refuses to open while an item
is already on the cursor and closes when combat starts. All edit callbacks
check combat again before changing anything.

Only 12 actions are visible at once. The secure **< number >** switcher cycles
all six normal action pages in combat. It never copies or erases actions.
The primary buttons retain WoW's stance behavior and assignments. The two
extra bottom bars hide for touch mode and restore on opt-out.

## Inputs for WoW

| Input | Touch control |
| --- | --- |
| Left click, select a unit, cast, place a ground spell | Tap the game UI |
| Right click, interact, use or equip an item, open a context menu | Hold |
| Read a tooltip or compare equipment without using it | Windows → Inspect |
| Change a hotbar action | Hold the slot, then use the picker |
| Movement | Left stick, including diagonal movement and strafing |
| Camera turn | Drag in the empty area above or beside the actions |
| Camera zoom | Pinch with two fingers in the camera area |
| Drag a window, slider, spellbook entry or bag item | Windows, then drag |
| Scroll a list | Use two fingers in Windows mode |
| Jump, nearest enemy, interact with the selected target | Jump, Target and Use |
| Select a nearby corpse for looting | Face it, hold Target, then tap Use |
| Previous enemy, auto run, sit or stand, walk or run | More |
| Select yourself or a party member, cycle friends, assist | More, or tap the stock unit frame |
| Ascend in water | Hold Jump |
| Descend in water | More → Switch Jump / Dive, then hold Dive |
| Open chat, enter names, search or type text | Keyboard or More → Chat |
| Split stacks, insert links, preview or self-cast | Choose Shift, Ctrl or Alt in More, then tap or hold |
| Cancel a ground spell or close a window | Esc |
| Change the action page | Tap < or >, or use More |

More puts targeting and movement choices first while playing, and typing and
item modifiers first in Windows mode. A one-shot modifier highlights the
More key with Shift, Ctrl or Alt until consumed. It applies to one tap, hold
or drag, then releases. Held mouse buttons and modifiers also release on
cancellation or loss of window focus. Cancelling a menu touch cannot open a
popup afterward.

A short Target tap cycles living enemies. Holding Target for half a second
selects the dead unit highlighted by WoW's nearby interact system. Walk within
interaction range and face the corpse, then hold Target and tap Use to loot.
More → Target nearby corpse provides the same selection. The hold never
cycles a living enemy first. Without an eligible corpse, the current target
is kept. Direct corpse taps and long presses remain available.

Touch mode enables the native interact key for mouse input, widens its front
cone and shows the interaction icon. It preserves the player's auto-loot
preference and never scans or automatically loots world objects.

Movement, camera and spell taps have independent pointer ownership. Tapping a
spell releases an active camera grab so WoW can receive an absolute click.
Moving the camera finger again resumes looking. A pinch or scroll never
produces a leftover click when its fingers lift.

The touch interface panel also opens Character, Spellbook, Quests, Talents,
Social, Macros, Skills, Reputation, Honor, Help and the game menu. The existing
session side menu provides Exit.

## Preservation and compatibility

Touch mode suppresses the standard virtual gamepad, including a manually
selected input profile. It temporarily disables ConsolePort and the
controller setup addon. Their saved settings and the player's bindings
remain intact.

Turning touch mode off restores only the controller folders it disabled.
Already disabled modules stay disabled. The companion addon remains so each
character can restore saved CVars, root scale, extra bars, chat, class and
party controls, shortcuts and trackers on its next login. Temporary binding
overrides are never saved. Intentional hotbar edits remain ordinary WoW
changes. Questie's profile is not rewritten.

Classic Era is the layout target. Other editions receive the native modes
and gestures but retain their stock hotbars. Third-party bar replacements,
raid-style unit frames, party roster changes in combat, simultaneous class
mechanics and extended combat need separate validation. Unknown addon panels
can use Windows and the movable Controls tab. Long trackers can become small
in narrow viewports. Open the full quest log for detailed reading.

The implementation follows Blizzard's
[1.15.9 UI source](https://github.com/Gethe/wow-ui-source/tree/1.15.9).
Run `scripts/test-wehavephones-touch.sh` for gesture ownership, inspection,
cancellation, paging, Undo, inventory navigation, restoration and combat
guards. It exports actual Lua frame rectangles and checks them against native
buttons, the stick and camera hit regions. Coverage includes eleven aspect
ratios, three UI sizes, four Android densities and letterboxing, plus 1,221
stock window and inventory combinations. The suite uses the host JDK, Python
and a Lua 5.2 or 5.4 shared library.

See the [interaction audit and mobile MMO research](TOUCH_CONTROL_RESEARCH_2026_10_27.md)
for activity coverage, design choices and validation boundaries.

## Device validation

### October 27–28 refinement

Classic Era 1.15.9.70576 was tested on the S23+ with 2340×1080, 1440×1080
and 1280×720 game viewports. The physical checks covered the wide and 4:3
action layouts, the movable Controls tab, Windows, keyboard access, map
fitting, character and bag windows, item inspection and inventory paging.
A real carried item remained on the cursor across bag pages and returned
to its original slot. All reopened both equipped bags after a single-bag
page and after Keys opened the stock keyring.

The friendly picker selected Self. A three-pointer movement, camera and
Mark of the Wild interaction refreshed the buff to 30 minutes. After release,
the game reported zero movement speed and no active mouselook.

Brief combat against level-one creatures confirmed Wrath casting and refusal
to open the action editor during combat. A stock combat layout update initially
moved chat and its tooltip toward the stick. The final source keeps unprotected
stock chat in place, and the repeated fight retained clear movement and tooltip
regions with zero captured Lua errors. Protected frame changes still defer.

The party row was visually previewed by showing the stock member frames
without joining a group. This checks geometry, not healing or roster changes
in a real party. Extended combat, raids and the class cases listed above
remain outside the physical coverage.

Disabling touch restored the controller interface, original loot and item
comparison preferences, chat and root-scale state. The saved HUD and CVar
restoration journals were empty afterward. Touch was then re-enabled and
the original 2340×1080 game resolution restored.

The touch suite passed 109,938 native assertions plus the addon fixtures,
including 1,221 stock-window and inventory combinations. The gamepad, launch,
runtime, addon, asset, memory and device checks passed. Both builds succeeded.
The final package was installed and launched. There were no new Android app
crash entries. The buffer retained a pre-existing system crash-dump helper
failure from before this session. The game exited normally and WeHavePhones
was stopped after testing.

### Earlier validation

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

Corpse controls were checked in a further Classic Era session. A short Target
tap selected a living enemy. Holding Target selected a nearby dead Nightsaber,
and Use successfully looted it. With no eligible corpse available, the living
target stayed selected and the range/facing hint appeared. The window-mode
label appeared only while window gestures were enabled. The touch suite
passed 53 checks, and the gamepad, launch, runtime and addon checks passed.
Both builds succeeded, with no new Android app crash entries.

The centered layout was then checked on the S23+ at its native 2340×1080
resolution. Chat stayed above the joystick, and the action grid stayed clear
of Jump and Use. Paging changed from 1 to 2 and back, and tapping Mark of the
Wild refreshed its buff to 30 minutes. The touch suite passed 69 checks plus
the addon layout fixtures across all three scale presets and wider aspect
ratios. The addon, launch, gamepad and asset checks and both builds passed.
There were no new Android crash entries.

Dragging, pinch/scroll separation, page-button attributes and combat guards
have automated host coverage. Extended play and the compatibility cases
above still need in-game validation.
