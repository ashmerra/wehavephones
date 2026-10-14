# Reuse existing WoW game files

You can copy an existing Windows installation of World of Warcraft to your phone
and let Battle.net check it. This can avoid downloading the bulk of the game
again. You still need internet access to sign in, check the installation, download
missing files or updates, and play. A copy from an older build or a different
game version may need a larger download.

## 1. Prepare the installation

Finish WeHavePhones' initial setup: **Install container**, then **Install
Battle.net**. You do not need to start downloading WoW. In **Setup**, note the
active container. Use that same container throughout these steps. Close any
running Battle.net or WoW session before copying files: swipe in from the left
edge of its screen and tap **Exit**.

On the PC, close WoW and Battle.net, then find the game's **World of Warcraft**
folder. Copy the whole folder, including hidden files such as `.build.info` and
`.product.db`, the shared `Data` folder, and the game version folders you use
(for example, `_retail_`, `_classic_`, or `_classic_era_`). Copying only a version
folder or a `Wow*.exe` leaves out the shared game data.

Check free space on the phone first. The copy method below temporarily keeps
two copies: one in Downloads and one inside the container. Allow room for both,
plus updates. Keep the original PC installation until the phone copy works.

## 2. Transfer the folder to the phone

Connect the phone to the PC by USB and select **File transfer** on the phone.
Copy **World of Warcraft** into the phone's internal-storage **Download** folder
(often displayed as **Downloads**). A local network transfer or USB storage
device also works if you put the files in the same folder.

The resulting Android path should be:

```text
/storage/emulated/0/Download/World of Warcraft
```

The default container maps Downloads to the Windows drive `D:`, so this appears
inside the container as `D:\World of Warcraft`. Grant WeHavePhones its requested
file/storage permission. If you changed the drive mapping, open **Containers**,
use the active container's three-dot menu → **Edit** → **Drives**, and check
which drive points to the transfer folder.

## 3. Import into the active container

Open **Setup → Import WoW files**. The app finds
`Download/World of Warcraft` and copies it into the active container at
`C:\Program Files (x86)\World of Warcraft`. It checks the folder and free space
before copying, shows progress, and continues if you switch apps or turn off
the screen. Tap **Cancel** in Setup or the import notification to stop it.

Import preserves the Downloads copy and refuses to replace an existing WoW
folder in the container, including one under `C:\Program Files`. Use another
container for a separate installation, or manage an existing installation
manually. Close any running session before importing. Game launches and
container switching are blocked until the import finishes or is cancelled.

After a failed or cancelled copy, try **Import WoW files** again. An interrupted
import is kept out of the game list, and the next attempt starts a fresh copy.

### Manual alternative

In WeHavePhones, open **Containers**, then the active container's three-dot menu
→ **Run**. In the container's file manager:

1. Open `D:` (or the drive checked above) and copy the **World of Warcraft** folder.
2. Open `C:\Program Files (x86)` and paste it there. Wait for the copy to finish.
   If you already have a WoW installation at that destination, back it up before
   merging or replacing files.
3. Check that the final folder is `C:\Program Files (x86)\World of Warcraft`,
   with `Data`, `.build.info`, and your version folders directly inside it.
   Avoid an extra nested `World of Warcraft\World of Warcraft` folder.
4. Swipe in from the left edge and tap **Exit** to return to WeHavePhones.

Use this `C:` location for the final installation. WeHavePhones' **Play** and
**Add-ons** screens scan it. Leaving the game only on `D:` or choosing a custom
Battle.net install path will not make those screens detect it. The app also
recognizes `C:\Program Files\World of Warcraft` when the `(x86)` location is
absent. `C:` belongs to the container's private app storage. It is not a folder
you can browse through the phone's ordinary USB file-transfer view.

## 4. Locate the installation in Battle.net

1. On WeHavePhones' **Play** screen, tap **Open Battle.net** and sign in.
2. Select **World of Warcraft**, then the **Game Version** matching the files you
   copied (Retail, Classic, Classic Era, etc.).
3. If Battle.net shows **Install**, click **Locate the game** below it and select
   `C:\Program Files (x86)\World of Warcraft`, the parent folder containing
   `Data` and the version folders. If it already shows **Play** or **Update**,
   it has found the installation.
4. Let Battle.net check the files and finish any required downloads. It may
   need Windows ARM64 client files or other files missing from the PC copy.
   Copying the data does not guarantee a download-free setup.
5. When Battle.net is ready, swipe in from the left edge and tap **Exit**. Back
   in WeHavePhones, tap the game title to choose the copied version, then **Play**.

Blizzard documents the **Locate the game** option in
[How to Install World of Warcraft](https://us.support.blizzard.com/en/article/195680).

Once the game works from `C:`, you can delete the staging copy in the phone's
Downloads folder to recover space. WeHavePhones and WeHavePhones Dev have
separate containers and game files. A copy into one app is not visible in the other.

## If the game is not detected

- **The transfer folder is missing from `D:`:** check the active container's
  **Drives** mapping and WeHavePhones' file/storage permission in Android settings.
- **Import cannot find the source:** put the entire folder at
  `Download/World of Warcraft`, with `Data`, `.build.info`, and the version
  folders inside it. Automatic import always checks Downloads, even if you
  changed the container's `D:` mapping.
- **Import reports an existing installation:** it will not overwrite either
  supported `C:` location. Use another container or the manual alternative.
- **Battle.net says the folder contains the wrong version:** select the matching
  **Game Version**, choose the parent **World of Warcraft** folder, and check
  that shared `Data` and hidden metadata files survived the transfer.
- **Battle.net finds it, but WeHavePhones does not:** check that you used the
  active container and the final `C:` path above, without an extra nested folder.
  Exit the session and return to the **Play** screen to refresh the game list.
- **Battle.net proposes a large download:** check the folder layout and selected
  version first. Missing data, a partial PC installation, or a different build
  can still require substantial downloads.
