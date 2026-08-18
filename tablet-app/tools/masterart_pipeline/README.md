# Masterart content pipeline

One-time, offline utility that turns real public-domain painting scans into the two bundled
assets used by the app's "Masterworks" coloring templates and Academy reference block. **Not**
part of the Gradle build — run manually, commit the outputs.

## Sources

All 4 works are confirmed public domain / PD-Art on Wikimedia Commons. `source/` holds the
downloaded scans (Wikimedia's server-generated 1920px-whitelisted thumbnails, not the
multi-hundred-MB originals, since the pipeline downscales further anyway):

| Work | Artist | Source file |
|---|---|---|
| The Starry Night (1889) | Vincent van Gogh | `starry_night_source.jpg` |
| Mona Lisa (c. 1503) | Leonardo da Vinci | `mona_lisa_source.jpg` |
| Girl with a Pearl Earring (1665) | Johannes Vermeer | `girl_pearl_earring_source.jpg` |
| The Great Wave off Kanagawa (c. 1831) | Katsushika Hokusai | `great_wave_source.jpg` |

Each is `https://upload.wikimedia.org/wikipedia/commons/thumb/<hash>/<filename>/1920px-<filename>`
(or the original for Great Wave, already small at 2.56MB) from the corresponding
`commons.wikimedia.org/wiki/File:...` page.

## Usage

```
pip install opencv-python-headless pillow numpy
python generate.py
```

Outputs land in `app/src/main/assets/masterworks/`: `<id>_reference.jpg` (full-color, ~1600px
long edge, q82) and `<id>_lineart.png` (transparent background, opaque dark lines, ~2048px
long edge).

## Technique: tone-quantize + closed contours (not Canny)

Plain Canny edge detection was tried first and rejected. Canny finds high-gradient pixels with
no guarantee they connect into closed loops — and closed loops are non-negotiable here, because
the app's paint-by-number region analyzer (`RegionAnalyzer`: any pixel with alpha >= 40 is a
"wall", flood-fill everything else) needs genuinely enclosed shapes, not just "looks like an
outline." Measuring Canny's output against that same alpha>=40 + flood-fill logic, 3 of the 4
works came back as **one single background region** no matter how much the lines were dilated to
bridge gaps — the outlines just weren't topologically closed, so a bucket-fill tap would leak
through the first gap and flood the whole canvas.

The fix: quantize the smoothed grayscale into a handful of tone bands via k-means, then trace
each band's boundary with `cv2.findContours` on its binary mask. Contours traced from a
thresholded mask are closed by construction — they trace a connected blob's boundary, full stop.
Same measurement afterward: all 4 works went from ~1 valid region to 80–460+.

Per-work parameters (`bilateral`, `tone_bands`, `min_arclength`, `close_kernel`/`open_kernel`)
live in the `WORKS` list in `generate.py`. If a future addition comes out too noisy or too
sparse, that's the first place to look — the debug loop that got these 4 right was: composite
the raw PNG onto white, eyeball it, *and* run the region-count check below before calling it
done (visual inspection alone missed the closed-loop problem on the first pass):

```python
from PIL import Image
from scipy import ndimage
import numpy as np
alpha = np.array(Image.open("app/src/main/assets/masterworks/<id>_lineart.png"))[..., 3]
background = ~(alpha >= 40)
labeled, n = ndimage.label(background, structure=np.ones((3, 3)))
sizes = ndimage.sum(background, labeled, range(1, n + 1))
print((sizes >= 400).sum(), "valid regions")  # 1 = broken (unclosed outline), more = good
```
