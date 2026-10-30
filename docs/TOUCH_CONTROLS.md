# Mobile touch controls

Settings → **Mobile touch controls (experimental)** is an opt-in alternative
to the controller layout. It is off by default. Close the game before changing
it, then launch again.

The touch controls separate what you are doing into clear modes. **Play** is
for moving, looking and fighting. **Menus** is for windows, bags, vendors and
anything else you tap, drag and scroll. A small **Controls** tab keeps the
whole screen free at login and character selection. The native keys, the
touch menu and the in-game touch keys use the app's own look: navy raised
keys, blue for whatever is current, silver labels and the Phosphor icon set
used across WeHavePhones.

## Modes

New sessions start with the **Controls** tab, which shows the WeHavePhones
phone and an arrow. Drag it aside if it covers something, and tap it to open
the toolbar. Tap **Play** after entering the world.

The toolbar ends with one switch: **Play | Menus**. The current mode is the
blue half. Tapping the other half switches. Each mode hint appears only
once per game session.

| Mode | On screen | Touches |
| --- | --- | --- |
| Play | Esc, Map, Bags, Menu and the switch. The stick on the left. Target, Use and Jump on the right | Stick to move. Drag the world to look. Pinch to zoom. Tap the game to click. Hold to right-click |
| Menus | Esc, Keyboard, Inspect, Menu and the switch. Blue corner brackets around the game | Tap to click. Hold to right-click. Drag items and windows. Two fingers scroll |
| Inspect, part of Menus | The Inspect key turns blue and the brackets turn silver | Touch or slide over anything to read its tooltip without clicking |

Use keeps the current mode, including when looting a corpse. Tap Menus when
you need to drag or scroll an interaction window. Map, Bags, Keyboard and
every window opened from the touch menu switch to Menus by themselves.
The first use of each mode in a session shows a short hint: over the world
in Play, and at the bottom in Menus so it never covers a window.

The **touch guide** dims the game, outlines every control and the camera
area, and says what each one does. It appears the first time each mode is
used, and again from **Menu → Touch controls → Touch guide**. Any tap closes
it without reaching the game.

## Feedback

Instructional banners, including auto run and Dive, appear once per game
session. The touch guide remains available from the menu.

- Keys sink onto their lip and turn blue while pressed.
- Holding the game shows a ring that fills around the finger. When it
  completes, the hold becomes a right-click. A quick tap leaves a ripple
  instead. Both stay visible around the finger, which would hide WoW's own
  feedback.
- Target carries a small skull. Holding Target fills a ring around the key
  and then selects a nearby corpse.
- Jump turns blue and shows a down arrow while it is switched to Dive.
- An armed Shift, Ctrl or Alt turns the Menu key blue and shows its name.

## Moving and auto run

Push the stick past its ring toward the top and a runner appears above it.
Slide farther up onto the runner, hold for just under half a second until
it turns blue, then let go to lock auto run. A quick slide past it will not
lock running. The knob then shows the runner. Strafe with the stick or
drag the world to steer. Pushing forward or backward ends auto run, as it
does in WoW. **Menu → Movement → Auto run** toggles it as well.

Hold Jump to swim up. **Menu → Movement → Dive key** turns Jump into Dive for
swimming down.

## Touch menu

**Menu** opens a full-screen menu in the app's rail and LCD style. While it is
open the game keeps running behind it. Close it with Close, the back gesture,
or by choosing an entry. It opens on Windows while playing and on Typing and
items in Menus.

| Section | Entries |
| --- | --- |
| Windows | Character, Spellbook, Talents, Quest log, Map, Bags, Social, Skills, Reputation, Honor, Pet spells, Macros, Game menu, Help |
| Targeting | Self, Party 1–4, Next friend, Assist, Previous enemy, Nearby corpse, Enemy nameplates |
| Movement | Auto run, Follow target, Walk or run, Sit or stand, Dive key |
| Typing and items | Chat, Reply, Keyboard, Inspect, Shift, Ctrl, Alt, No modifier |
| Touch controls | Interface size 125%, 150% or 175%, Touch guide, Hide controls, Undo hotbar edit, Next action page |

Entries use WoW's own key bindings as session-only overrides. Windows with no
stock binding, such as Macros and Help, and the size and Undo entries use
hidden buttons in the touch addon. The chords use Ctrl or Shift with function
keys. None uses Alt, because Windows reads Alt with F4 as closing the game.

The interface size is one app setting for every character. It applies at
once in game and is written to the addon before each launch. A size chosen
during combat applies when combat ends.

## Screen layout

The native toolbar and the touch addon share fixed regions, measured in
fractions of the game's height, so neither covers the other on any screen.

- **Wide games** (aspect ratio 1.59 or more, which includes 16:10 and every
  phone): the toolbar is a row in the upper right, just left of a minimap
  square of 0.30 game heights. It stays within the top 0.14 of the game.
  The minimap fits its square. Buffs, debuffs and top-center widgets such as
  battleground scores start below the toolbar. Player and target frames keep
  their own anchors and shrink toward them only if the target frame would
  reach the toolbar.
- **Narrower games** (tablets and foldables): the toolbar is a column in the
  right rail above Target, Use and Jump, using two columns when the rail is
  wide enough. The minimap moves out of the rail. Buffs and debuffs sit under
  it, clear of Target, Use and Jump. Player and target frames fit left of the
  minimap.
- If keys would be smaller than 40 dp, Map and Bags, or Keyboard and Inspect,
  move into the touch menu. Esc, Menu and the switch always remain.

On wide Retail, Classic Era, BC Anniversary and MoP Classic screens, eight
stock action buttons form a four-column, two-row grid at the bottom right.
Four more sit near the bottom middle.
Tablet shapes use a four-column, three-row grid. Square shapes use three
columns and four rows. Both put the page switcher at the lower left. Class,
pet and possession controls have a separate footer. The XP bar stays below
them.

The layout reserves space for native thumb controls and caps its width on
ultrawide screens. Camera gestures follow the actual guest viewport,
including letterboxing and stretching. The stock party frames form a row
above chat and movement, retaining their own targeting, pet and aura
behavior. Group chat and its utility buttons fit below that row, then regain
their normal size when solo. Stock chat retains its touch anchor during
combat. Protected replacement chat frames defer their layout until combat
ends. Hotbar tooltips appear only in Inspect, so casting leaves the screen
clear. Inspect and default world tooltips appear over chat.

The stock quest tracker fits above the right actions and below the minimap
and buffs. Addon trackers keep their own position, size and visibility.
A new row of buffs moves the debuffs down, in combat as well. The stock
tracker follows after combat.

Typing in chat, from **Menu → Typing and items → Chat** or **Reply** or from
WoW itself, raises the chat box to just below the unit frames. The renderer
pans the game to keep the pointer above the Android keyboard, so Chat and
Reply also park the pointer at the raised box. The game then stays still and
the box stays visible. The chat box returns to the chat frame afterwards.
**Keyboard** leaves the pointer where you last tapped, which is normally the
text field you want to type in.

Enemy nameplates are on in touch mode. They are steadier tap targets than
moving creature models. **Menu → Targeting → Enemy nameplates** toggles them.

## Windows and inspection

Stock windows and visible bags fit together below the toolbar. Large stock
windows fit the available workspace using their actual effective scale. This
includes frames such as the world map that ignore their parent's scale.

The inventory strip offers **Pack**, **Bag 1–4**, **All** and Classic's
**Keys**, plus bank bag pages while the bank is open. The open page is
highlighted in blue. All reopens the complete player inventory after viewing
one bag. A single bag gives its item controls more room. A picked-up item
stays on the cursor when changing pages, ready for a normal placement click.
Bag paging is available out of combat. Opening, using and moving items still
uses the game's stock container buttons and rules.

Retail's separate bags also have a **Reagents** page. Combined inventory keeps
its stock combined view, fitted alongside any other open window. Retail's
bank uses its own tabs. **Skills** in the touch menu opens Retail's profession
book. The spellbook and profession windows fit below the native toolbar.

Touch mode enables stock equipment comparison tooltips and restores the
prior preference on opt-out. Inspect never uses, equips, casts or drags.

## Editing actions

Hold a hotbar slot for about half a second. Releasing the hold does not cast
its spell. A picker opens with **Spells**, **Items** and **Macros**. Tap a choice
to place it in that slot. Passive and unlearned spells are excluded. Spells
retain their rank. Items come from the player's bags. Macros include account
and character macros.

Retail uses its current spellbook API. The picker excludes future spells,
inactive specializations and passive spells, and includes the reagent bag
in Items. Classic keeps its ranked spellbook behavior. MoP also excludes
hidden and inactive specialization tabs.

**Clear slot** removes its action. **Undo** restores the last edited slot.
**Cancel** leaves the slot unchanged. The picker refuses to open while an item
is already on the cursor and closes when combat starts. All edit callbacks
check combat again before changing anything.

Only 12 actions are visible at once. The secure switcher cycles all six
normal action pages in combat. Six dots under the page number show which page
is in use. It never copies or erases actions.
The primary buttons retain WoW's stance behavior and assignments. The two
extra bottom bars hide for touch mode and restore on opt-out.

## Inputs for WoW

| Input | Touch control |
| --- | --- |
| Left click, select a unit, cast, place a ground spell | Tap the game UI |
| Right click, interact, use or equip an item, open a context menu | Hold until the ring fills |
| Read a tooltip or compare equipment without using it | Menus → Inspect |
| Change a hotbar action | Hold the slot, then use the picker |
| Movement | Left stick, including diagonal movement and strafing |
| Auto run | Hold the stick on the runner until blue and release, or Menu → Movement |
| Camera turn | Drag in the empty area above or beside the actions |
| Camera zoom | Pinch with two fingers in the camera area |
| Drag a window, slider, spellbook entry or bag item | Menus, then drag |
| Scroll a list | Two fingers in Menus |
| Jump, nearest enemy, interact with the selected target | Jump, Target and Use |
| Select a nearby corpse for looting | Face it, hold Target, then tap Use |
| Previous enemy, follow, sit or stand, walk or run | Menu → Targeting or Movement |
| Select yourself or a party member, cycle friends, assist | Menu → Targeting, or tap the stock unit frame |
| Ascend in water | Hold Jump |
| Descend in water | Menu → Movement → Dive key, then hold Dive |
| Open chat, reply, enter names, search or type text | Menu → Chat or Reply, or Keyboard |
| Split stacks, insert links, preview or self-cast | Menu → Shift, Ctrl or Alt, then tap or hold |
| Cancel a ground spell, clear the target or close a window | Esc |
| Change the action page | Tap the chevrons, or Menu → Next action page |
| Open any game window | Menu → Windows |

A one-shot modifier turns the Menu key blue with Shift, Ctrl or Alt until
consumed. It applies to one tap, hold or drag, then releases. Held mouse
buttons and modifiers also release on cancellation or loss of window focus.
Cancelling a menu touch cannot open a popup afterward.

A short Target tap cycles living enemies. Holding Target for half a second
selects the dead unit highlighted by WoW's nearby interact system. Walk within
interaction range and face the corpse, then hold Target and tap Use to loot.
Menu → Targeting → Nearby corpse provides the same selection. The hold never
cycles a living enemy first. Without an eligible corpse, the current target
is kept. Direct corpse taps and long presses remain available.

Touch mode enables the native interact key for mouse input, widens its front
cone and shows the interaction icon. It preserves the player's auto-loot
preference and never scans or automatically loots world objects.

Movement, camera and spell taps have independent pointer ownership. Tapping a
spell releases an active camera grab so WoW can receive an absolute click.
Moving the camera finger again resumes looking. A pinch or scroll never
produces a leftover click when its fingers lift.

The existing session side menu provides Exit.

## Preservation and compatibility

Touch mode suppresses the standard virtual gamepad, including a manually
selected input profile. It temporarily disables ConsolePort and the
controller setup addon. Their saved settings and the player's bindings
remain intact.

Turning touch mode off restores only the controller folders it disabled.
Already disabled modules stay disabled. The companion addon remains so each
character can restore saved CVars (including nameplates), root scale, extra
bars, chat, class and party controls, the minimap, unit frames, auras,
top-center widgets, shortcuts and trackers on its next login. Temporary binding
overrides are never saved. Intentional hotbar edits remain ordinary WoW
changes.

Retail, Classic Era, BC Anniversary and MoP Classic receive the touch layout.
Other editions receive the native modes and gestures but retain their stock
hotbars. Third-party bar replacements, raid-style unit frames, party roster changes in combat, simultaneous class
mechanics and extended combat need separate validation. Unknown addon panels
can use Menus and the movable Controls tab. Addons that move the minimap,
buffs or unit frames themselves can be moved again by the touch layout. Long trackers can become small
in narrow viewports. Open the full quest log for detailed reading.

The implementation follows Blizzard's
[1.15.9 UI source](https://github.com/Gethe/wow-ui-source/tree/1.15.9).
Run `scripts/test-wehavephones-touch.sh` for gesture ownership, inspection,
cancellation, paging, Undo, inventory navigation, restoration and combat
guards, auto run, hold feedback and the menu bindings. It exports actual Lua
frame rectangles (actions, pager, class and party controls, the minimap, unit
frames, auras and top-center widgets) and checks them against the native
keys, the stick and camera hit regions. It also checks that the toolbar stays
inside the regions the addon keeps free. Coverage includes eleven aspect
ratios, three UI sizes, four Android densities, safe-area insets and
letterboxing, plus 1,353 stock window and inventory combinations per Classic
client fixture.
The fixtures cover Era, BC Anniversary and MoP Classic, with Retail regression
checks in each run. The suite uses the host JDK, Python
and a Lua 5.2 or 5.4 shared library.
