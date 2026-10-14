# WeHavePhones brand images

Images of the blue flip-phone mark and the WeHavePhones wordmark. They are
rendered from the shared vector
[`docs/design/brand/flip-phone.svg`](../design/brand/flip-phone.svg). The design
direction and the rendering requirements are in
[the production brand](../design/brand/README.md).

| File | Size | Use |
| --- | --- | --- |
| `banner.png` | 1280x400 | Wide banner: the flip phone and the wordmark on navy, with the tagline "World of Warcraft on Android and Wine." and "Unofficial; not affiliated with Blizzard." |
| `social-preview.png` | 1280x640 | GitHub social preview: the same design on a taller canvas |
| `logo.png` | 1200x300 | The flip phone and the wordmark in navy and blue on a transparent background, for light pages |
| `icon.png` | 512x512 | The flip phone on a rounded blue square, as a preview of the app icon |

Regenerate them after changing the SVG:

```bash
scripts/render-brand.sh      # banner, social preview, logo and icon in this directory
scripts/render-app-icon.sh   # the app's launcher icons, container icon and Wine wallpaper,
                             # plus docs/design/app-icon/preview.png
```

GitHub has no API for the social preview: upload `social-preview.png` under
the repository's Settings > General > Social preview.

World of Warcraft is a Blizzard Entertainment trademark. These images contain
no game art.
