# WeHavePhones production brand

The blue flip-phone logo and landscape menus were approved on 2026-10-03.
[flip-phone.svg](flip-phone.svg) is the production vector of that direction.

The app uses a navy shell, silver LCD bezels, green display surfaces, raised
blue primary keys, and silver secondary keys. Pixel headings use Tiny5.
Body text and controls use Android sans-serif. Status is expressed with words
and icons as well as color. Long menus scroll, including with larger text.
The decorative mock battery, Wi-Fi, and clock indicators are omitted because
the app does not provide that device-status strip.

`scripts/render-app-icon.sh` generates the native VectorDrawable, five adaptive
icon densities, legacy square/round icons, a cutout monochrome icon, container
icons, and the Wine desktop wallpaper. `scripts/render-brand.sh` generates the
banner, transparent wordmark, social preview and icon preview in `docs/brand/`.
Both use the shared SVG through `scripts/render-phone-brand.py`, Python 3, and
system librsvg/Cairo libraries. Install `librsvg2-2`, `libcairo2`,
`libglib2.0-0` and DejaVu Sans on a Linux rendering host. Neither helper
launches a browser.

Tiny5 Regular was downloaded from the Google Fonts distribution:
https://raw.githubusercontent.com/google/fonts/main/ofl/tiny5/Tiny5-Regular.ttf

Bundled font SHA-256: `cb8168f80cfee2f47f6db59f2a7afbde31cdcdcdcf262e7a993e4d468a5bf4b0`.
Its OFL license and copyright are retained in `docs/licenses/Tiny5-OFL.txt`,
`THIRD_PARTY_NOTICES.md`, and the generated in-app notices.

Native validation uses `scripts/test-wehavephones-ui.sh --serial <device>`
after the deployment helper succeeds. Its runner is in the `wehavephonesDebug`
source set and is excluded from release builds. It renders actual launcher,
add-on and runtime screens and captures the launch-overlay resource without
launching Wine. It never marks first-time setup complete or populates game/
account fixtures. Screenshots are local, gitignored `out/phone-ui/` artifacts.
Review them for clipping and contrast. The host refuses to run over a live
Wine/game session.
