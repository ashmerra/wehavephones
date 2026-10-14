# WeHavePhones website

A single-page, buildless site for GitHub Pages or another static host. All
assets and the Geist font are served locally. Project and download links point
to GitHub. The gameplay demo links to the video from the project README.

The public site is https://wehavephones.com/ (the GitHub Pages address
redirects there).
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

## Search and answer engines

- `index.html` carries the canonical URL, Open Graph and Twitter card tags, and
  JSON-LD (`SoftwareApplication`, `VideoObject`, `FAQPage`, `WebSite`). The
  `FAQPage` answers must match the visible FAQ text. Edit both together.
- The FAQ and `llms.txt` repeat facts from the project README. Update them when
  the requirements or the release signing certificate change.
- `robots.txt` points to `sitemap.xml`. Bump its `lastmod` when page content
  changes. `404.html` is GitHub Pages' not-found page and uses root-relative
  paths.
- `1016b5c9fd1ab99d8004bb7666047ff2.txt` is the public IndexNow key, which lets
  Bing and other IndexNow engines accept recrawl requests for this domain. After
  publishing changes, submit the changed URLs to `https://api.indexnow.org/indexnow`
  with that key.
- `assets/og-image.jpg` (1200x630) is the social preview. The hero image is
  served as AVIF and WebP at 640, 960, 1280 and 1920 px, with the JPEG as the
  fallback. Regenerate all of them if the demo frame changes.

The website follows the local taste skill with `DESIGN_VARIANCE: 6`,
`MOTION_INTENSITY: 4`, and `VISUAL_DENSITY: 3`. It uses native HTML and CSS for
simple static hosting, self-hosted Geist, the existing blue brand, real gameplay
imagery, and a single radius scale. Light and dark themes follow the system
preference until the visitor chooses a theme. Entry motion establishes the
reading order. Hover motion provides feedback. Reduced motion disables both.

`assets/gameplay-60s.jpg` is a frame at 1:00 from the project's own YouTube demo,
chosen to show the whole screen without a hand covering the gameplay.
The icon and logo come from `docs/brand/`. The font's license is included in
`assets/geist-LICENSE.txt`.
