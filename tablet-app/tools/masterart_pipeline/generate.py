#!/usr/bin/env python3
"""
Masterart content pipeline (one-time, offline utility -- NOT part of the Gradle build).

Turns a real public-domain painting scan (already downloaded to ./source/, all confirmed
PD/PD-Art on Wikimedia Commons) into two bundled assets per work:

  1. A disk-conscious full-color reference JPEG (for the Academy "study the real painting"
     block and general viewing).
  2. An accurate line-art PNG -- transparent background, opaque dark lines -- sized and
     shaped for the existing coloring-book pipeline: ColoringTemplate.draw(canvas, size)
     letterbox-fits it into the (square) project canvas, and CanvasEngine's paint-by-number
     region detector treats alpha >= 40 as a "wall", so lines need to form genuinely CLOSED
     loops or every enclosed shape merges into one giant background region.

Line-art technique: tone-quantize (k-means on smoothed grayscale intensity) into a handful of
bands, then extract each band's boundary via cv2.findContours on its binary mask. This is the
key choice, and not the obvious one -- plain Canny edge detection was tried first and rejected:
Canny finds high-gradient pixels with no topological guarantee they connect into closed loops,
and measured against the app's own paint-by-number region analyzer (RegionAnalyzer: alpha>=40
is a wall, flood-fill the rest), most works came back as ONE single background region no matter
how much the lines were dilated to bridge gaps -- the outlines simply weren't closed, so a
bucket-fill tap would leak through the first gap and flood the whole canvas. Contours traced
from a thresholded binary mask are closed by construction (they trace a connected blob's
boundary), which fixed this outright.

Optional `median_pre` + `double_bilateral`: some heavily-textured/impasto works (thick visible
brushstrokes) make k-means fragment on every stroke instead of the real composition -- an extra
median-blur pass before the bilateral filter, and running the bilateral filter twice, knocks
that texture down before quantization. Only a few works need it; see each entry below.

**Known limitation, not silently worked around**: The Kiss (Klimt), Wheatfield with Crows
(Van Gogh), Liberty Leading the People (Delacroix), and Impression Sunrise (Monet) were tried
here -- extensively, across 4 rounds of parameter tuning including median_pre, double bilateral,
downscale/upscale low-pass, and heavy Gaussian blur with as few as 3 tone bands -- and none of it
produced a result that was both topologically closed AND recognizable as the source painting.
Their combination of extreme texture (Kiss's gold-leaf pattern, Wheatfield's thick impasto),
very low internal contrast (Impression Sunrise's hazy dawn light), or dense crowded composition
(Liberty) defeats generic tone-segmentation. Rather than ship broken/unrecognizable line art,
these 4 are deliberately excluded from WORKS below. Revisiting them would need a fundamentally
different approach (e.g. semantic segmentation, or manual contour tracing) -- not a parameter
tweak on this pipeline.

Run manually: `python generate.py`. Outputs land in
app/src/main/assets/masterworks/ and get committed as static assets.
"""
import cv2
import numpy as np
from pathlib import Path
from PIL import Image

SOURCE_DIR = Path(__file__).parent / "source"
OUT_DIR = Path(__file__).parent.parent.parent / "app" / "src" / "main" / "assets" / "masterworks"

REFERENCE_LONG_EDGE = 1600
REFERENCE_QUALITY = 82
LINEART_LONG_EDGE = 2048

WORKS = [
    {
        # Bilateral-only smoothing left this recognizable at full size but reading as pure
        # noise at the small gallery-thumbnail scale actually used in the app -- caught only
        # by checking the thumbnail render, not just the full-size composite. Mean-shift color
        # segmentation as the smoothing step (still feeding into the same tone-quantize +
        # contour pipeline for closure) keeps the real swirl/cypress/moon shapes intact at
        # both sizes instead of texture noise.
        "id": "starry_night",
        "source": "starry_night_source.jpg",
        "meanshift_pre": {"median": 5, "sp": 14, "sr": 42},
        "tone_bands": 5,
        "min_arclength": 30,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "mona_lisa",
        "source": "mona_lisa_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 5,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "girl_pearl_earring",
        "source": "girl_pearl_earring_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 5,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "great_wave",
        "source": "great_wave_source.jpg",
        "bilateral": (5, 40, 40),
        "tone_bands": 6,
        "min_arclength": 30,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "the_milkmaid",
        "source": "the_milkmaid_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 5,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "cafe_terrace_at_night",
        "source": "cafe_terrace_at_night_source.jpg",
        "bilateral": (11, 90, 90),
        "median_pre": 7,
        "double_bilateral": True,
        "tone_bands": 5,
        "min_arclength": 35,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "wanderer_sea_of_fog",
        "source": "wanderer_sea_of_fog_source.jpg",
        "bilateral": (9, 70, 70),
        "tone_bands": 5,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "the_night_watch",
        "source": "the_night_watch_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 7,
        "min_arclength": 35,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "whistlers_mother",
        "source": "whistlers_mother_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 4,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "anatomy_lesson_dr_tulp",
        "source": "anatomy_lesson_dr_tulp_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 6,
        "min_arclength": 35,
        "close_kernel": 5,
        "open_kernel": 3,
    },
    {
        "id": "girl_with_red_hat",
        "source": "girl_with_red_hat_source.jpg",
        "bilateral": (9, 75, 75),
        "tone_bands": 5,
        "min_arclength": 40,
        "close_kernel": 5,
        "open_kernel": 3,
    },
]


def make_reference(img_bgr, out_path):
    h, w = img_bgr.shape[:2]
    scale = REFERENCE_LONG_EDGE / max(h, w)
    if scale < 1:
        img_bgr = cv2.resize(img_bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    Image.fromarray(rgb).save(out_path, "JPEG", quality=REFERENCE_QUALITY, optimize=True)


def make_lineart(img_bgr, out_path, cfg):
    h, w = img_bgr.shape[:2]
    scale = LINEART_LONG_EDGE / max(h, w)
    work = cv2.resize(img_bgr, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else img_bgr.copy()

    if cfg.get("meanshift_pre"):
        # Color-segment first (in BGR, before grayscale) -- preserves real shape boundaries
        # (a swirl, a silhouette) through heavy smoothing far better than bilateral-on-gray
        # does for thick, painterly brushwork. See starry_night's config comment.
        ms = cfg["meanshift_pre"]
        pre = cv2.medianBlur(work, ms["median"])
        seg = cv2.pyrMeanShiftFiltering(pre, sp=ms["sp"], sr=ms["sr"], maxLevel=1)
        smooth = cv2.cvtColor(seg, cv2.COLOR_BGR2GRAY)
    else:
        gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
        if cfg.get("median_pre"):
            gray = cv2.medianBlur(gray, cfg["median_pre"])
        d, sigma_color, sigma_space = cfg["bilateral"]
        smooth = cv2.bilateralFilter(gray, d=d, sigmaColor=sigma_color, sigmaSpace=sigma_space)
        if cfg.get("double_bilateral"):
            smooth = cv2.bilateralFilter(smooth, d=d, sigmaColor=sigma_color, sigmaSpace=sigma_space)

    # Tone-quantize into K bands via k-means on pixel intensity, then trace each band's
    # boundary as a closed contour -- see module doc for why this beats raw Canny edges here.
    k = cfg["tone_bands"]
    samples = smooth.reshape(-1, 1).astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 0.5)
    _, labels, centers = cv2.kmeans(samples, k, None, criteria, 5, cv2.KMEANS_PP_CENTERS)
    labels = labels.reshape(smooth.shape)
    lightest_to_darkest = np.argsort(centers.flatten())[::-1]

    lines = np.zeros_like(smooth)
    close_kernel = np.ones((cfg["close_kernel"], cfg["close_kernel"]), np.uint8)
    open_kernel = np.ones((cfg["open_kernel"], cfg["open_kernel"]), np.uint8)
    for band_idx in lightest_to_darkest[1:]:  # skip the lightest band -- that's paper/background
        mask = (labels == band_idx).astype(np.uint8) * 255
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, close_kernel)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, open_kernel)
        contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
        for c in contours:
            if cv2.arcLength(c, True) > cfg["min_arclength"]:
                cv2.drawContours(lines, [c], -1, 255, 2)

    rgba = np.zeros((*lines.shape, 4), dtype=np.uint8)
    rgba[..., 3] = lines
    Image.fromarray(rgba).save(out_path, "PNG", optimize=True)

    return float(np.count_nonzero(lines)) / lines.size


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for cfg in WORKS:
        src_path = SOURCE_DIR / cfg["source"]
        img_bgr = cv2.imread(str(src_path))
        if img_bgr is None:
            raise RuntimeError(f"Failed to read {src_path}")

        ref_path = OUT_DIR / f"{cfg['id']}_reference.jpg"
        line_path = OUT_DIR / f"{cfg['id']}_lineart.png"

        make_reference(img_bgr, ref_path)
        frac = make_lineart(img_bgr, line_path, cfg)

        print(
            f"{cfg['id']}: reference={ref_path.stat().st_size / 1024:.0f}KB  "
            f"lineart={line_path.stat().st_size / 1024:.0f}KB  edge_density={frac * 100:.1f}%"
        )


if __name__ == "__main__":
    main()
