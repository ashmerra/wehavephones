# WeHavePhones website

A single-page, buildless site for GitHub Pages or another static host. All
assets and the Geist font are served locally. Project and download links point
to GitHub; the gameplay demo links to the video from the project README.

Preview from the repository root:

```sh
python3 -m http.server 4173 --bind 0.0.0.0 --directory website
```

Open `http://localhost:4173`, or the host's Tailscale IP with port `4173` from
another device on the same tailnet. Only `website/` is served.

The website follows the local taste skill with `DESIGN_VARIANCE: 6`,
`MOTION_INTENSITY: 4`, and `VISUAL_DENSITY: 3`. It uses native HTML and CSS for
simple static hosting, self-hosted Geist, the existing blue brand, real gameplay
imagery, and a single radius scale. Light and dark themes follow the system
preference until the visitor chooses a theme. Entry motion establishes the
reading order; hover motion provides feedback. Reduced motion disables both.

`assets/gameplay.jpg` is the thumbnail from the project's own YouTube demo.
The icon and logo come from `docs/brand/`. The font's license is included in
`assets/geist-LICENSE.txt`.
