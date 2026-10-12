# WeHavePhones website

A single-page, buildless site for GitHub Pages or another static host. All
assets and the Geist font are served locally. Project and download links point
to GitHub; the gameplay demo links to the video from the project README.

The public site is https://ashmerra.github.io/wehavephones/.
`.github/workflows/deploy-website.yml` publishes this directory with GitHub
Actions when website changes are pushed to `main`. It can also be run manually.
GitHub Pages must use GitHub Actions as its publishing source.

Preview from the repository root:

```sh
python3 -m http.server 4173 --bind 0.0.0.0 --directory website
```

Open `http://localhost:4173`, or the host's Tailscale IP with port `4173` from
another device on the same tailnet. Only `website/` is served.

The download button links directly to the APK. `site.js` checks GitHub's latest
release API on page load and updates it to the versioned APK asset. The HTML
contains a direct download fallback for visitors without JavaScript or when
GitHub's API is unavailable. Update that fallback when publishing a new release.

The website follows the local taste skill with `DESIGN_VARIANCE: 6`,
`MOTION_INTENSITY: 4`, and `VISUAL_DENSITY: 3`. It uses native HTML and CSS for
simple static hosting, self-hosted Geist, the existing blue brand, real gameplay
imagery, and a single radius scale. Light and dark themes follow the system
preference until the visitor chooses a theme. Entry motion establishes the
reading order; hover motion provides feedback. Reduced motion disables both.

`assets/gameplay-60s.jpg` is a frame at 1:00 from the project's own YouTube demo,
chosen to show the whole screen without a hand covering the gameplay.
The icon and logo come from `docs/brand/`. The font's license is included in
`assets/geist-LICENSE.txt`.
