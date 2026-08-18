# PC Connection — what's built, what's scaffolded, what's deliberately not built

Vellum Studio's "connect to my PC" feature has three tiers of ambition. Only the first is real and
finished; the second is a real, working (if crude) proof of concept; the third is intentionally
**not implemented** and explains why.

## Tier 1 — LAN project sync (done, both sides)

- **Tablet side**: `tablet-app/app/.../network/SyncServer.kt` runs an embedded NanoHTTPD server
  (default port `8642`) with no pairing/auth (same-network trust model, matching what you'd expect
  from a personal LAN tool). Routes:
  - `GET /projects` — JSON list of `{id, name, updatedAt, width, height, thumbnailUrl}`
  - `GET /projects/{id}/export.zip` — the project's metadata.json + layer PNGs, zipped
  - `GET /projects/{id}/thumbnail.png` — cached preview
  - Started/stopped from the in-app Connect screen; shows the tablet's Wi-Fi IP so you can point
    the PC app at it.
- **PC side**: `pc-companion/VellumCompanion` (WPF, .NET) — enter the tablet's `IP:port`, hit
  Connect, browse the list, download a project's zip via a save dialog. See
  `pc-companion/README.md`.

## Tier 2 — Live-ish mirror (working proof of concept, not push-based)

`GET /mirror/frame.jpg` on the tablet returns a JPEG snapshot (downscaled to ≤1024px) of whatever
canvas is currently open, sourced from `network/LiveCanvasBridge`. Point a browser or the PC app's
(currently placeholder) Live Mirror tab at it and poll on an interval for a rough live view. This is
deliberately **poll-based HTTP, not a WebSocket push** — it's the lowest-risk way to get something
real working in this pass. A true push-based mirror (tablet streams frames over a `/mirror`
WebSocket as they're drawn) is a natural next step and doesn't need any of the machinery in Tier 3.

## Tier 3 — Full tablet-as-second-display, S Pen drives the PC cursor (not built)

This is the "use it like a wireless Cintiq / Astropad / Duet Display" mode: the PC's screen (or a
virtual display) streams to the tablet, and S Pen position/pressure/tilt drive the actual cursor and
pressure-sensitivity inside PC apps like Photoshop or Clip Studio.

**Why it's not here:** doing this properly needs two Windows kernel-mode components:

1. **A virtual display driver** (prior art: [IddSampleDriver](https://github.com/roshkins/IddSampleDriver),
   or products like spacedesk/ParsecVDisplay) so Windows has an extra "monitor" to render into and
   send to the tablet.
2. **A virtual HID/pen-injection driver** (prior art: [Interception](https://github.com/oblitum/Interception),
   or the approach [ViGEm](https://github.com/ViGEm/ViGEmBus) uses for controllers) so pen events from
   the tablet can be injected as real pressure-sensitive stylus input system-wide, not just mouse
   clicks (`SendInput` alone gets you a cursor, not Wacom-grade pressure/tilt into apps that check for it).

Both require **kernel-mode driver signing and an administrator-level install** on the PC. That's
explicitly out of scope for an automated coding session — it's a "modify system/security settings"
class of action that needs a human at the keyboard making an informed call, not a background build
step. If you want to pursue this later, the two links above are the standard building blocks the
community uses; Tier 2's live mirror is the low-risk stepping stone already in place.
