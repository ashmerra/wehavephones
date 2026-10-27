# Mobile touch controls

Settings → **Mobile touch controls (experimental)** is an opt-in alternative
to the controller layout. It is off by default. Close the game before changing
it, then launch the game again.

- Move with the left stick. It sends forward, backward and strafe inputs.
- Drag in the right middle of the screen, above the action bars, to turn the
  camera. A tap there still clicks the game UI.
- Tap spells directly in WoW. Movement can stay held while another finger
  taps a spell. A spell tap releases an active camera drag. Move the camera
  finger again to resume looking.
- The edge buttons provide Jump, Target nearest enemy, Use the selected
  target, Bags, Map, Escape and the next action bar page. The existing session
  side menu still provides the keyboard and Exit.

The mode suppresses the standard virtual gamepad even when a manual input
profile was selected. It temporarily disables ConsolePort and the controller
setup addon, including the installed ConsolePort modules. It leaves their
saved settings and the player's saved bindings intact.

The bundled WeHavePhones Touch addon enlarges the UI and turns off the game's
controller input. On Classic Era it arranges the twelve stock main action
buttons in two larger rows of six. These remain WoW's secure action buttons,
with their existing spells, paging, cooldowns, macros and stance behaviour.
Other game versions receive the larger UI and direct touch input, with their
existing action bar layout. Additional action bars and third-party action bar
addons keep their own layouts. Those combinations need separate validation.

Turning the mode off restores only the controller addon folders the mode
disabled. A module that was already disabled stays disabled. The touch addon
stays installed to restore the saved UI and controller CVars when each
character next logs in. Stock action button positions return on a fresh load.
Temporary keyboard overrides are never saved to the player's bindings.

Launch preparation stops on conflicting enabled and disabled controller
folders rather than overwriting either copy. Addon folders are changed only
while WoW is closed. The restore journal is kept in the game's WTF directory.

Run `scripts/test-wehavephones-touch.sh` for gesture ownership, input release,
addon folder restoration and the Lua setup lifecycle. It uses the host JDK,
Python and a Lua 5.2 or 5.4 shared library. The existing gamepad, launch, runtime
and addon checks cover the default controller path.

## Phone validation

Validated on the Galaxy S23+ with Classic Era 1.15.9.70576 on 2026-10-26.
The character entered the world with ConsolePort disabled and the larger
two-row action bar. A direct touch cast Healing Touch. A three-finger input
sequence held movement, turned the camera and tapped the spell while moving.
The game correctly rejected that cast because Healing Touch requires standing
still. Repeating the three-finger sequence with Mark of the Wild cast the
instant buff successfully while moving and turning. Jump, Bags, Map, Escape
and action bar paging worked through the native utility buttons. Cycling
through all six pages returned to the original spells and kept the two-row
layout. The Android crash buffer remained empty.

Turning the mode off and entering the same character restored the standard
virtual gamepad and the saved ConsolePort action bar layout. Other game
versions, action bar addons, class-specific stance changes and extended combat
sessions still need device validation.
