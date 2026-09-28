# PC Connection — what's built, what's scaffolded, what's deliberately not built

Vellum Studio's "connect to my PC" feature has three tiers of ambition. Only the first is real and
finished; the second is a real, working (if crude) proof of concept; the third is intentionally
**not implemented** and explains why.

## Tier 1 — LAN project sync (done, both sides)

- **Tablet side**: `tablet-app/app/.../network/SyncServer.kt` runs an embedded NanoHTTPD server
  (default port `8642`). Routes:
  - `GET /projects` — JSON list of `{id, name, updatedAt, width, height, thumbnailUrl}`
  - `GET /projects/{id}/export.zip` — the project's metadata.json + layer PNGs, zipped
  - `GET /projects/{id}/thumbnail.png` — cached preview
  - Started/stopped from the in-app Connect screen, which shows the tablet's Wi-Fi address and a
    fresh 6-digit **pairing PIN** every time sync is started.
- **Access control** (added because "anyone on the same Wi-Fi" is not a safe trust model on a café,
  hotel or campus network):
  - **PIN on every request.** The companion sends it as an `X-Vellum-Pin` header (a browser polling
    the mirror can use `?pin=123456` instead). No PIN or a wrong PIN is `401`; the check runs before
    routing, so an unauthenticated peer can't even tell which paths exist. The PIN is compared in
    constant time.
  - **Five wrong PINs lock the session** (`429` for everything, even the right PIN). Only Stop then
    Start on the tablet, which makes a new PIN, clears it. Requests with *no* PIN don't count.
  - **Bound to the Wi-Fi/Ethernet address only**, not every interface, so it is never reachable
    over mobile data, a VPN adapter or a hotspot interface. With no Wi-Fi address the tablet
    refuses to start sync rather than guess.
  - **Stops itself** after 10 minutes without an authenticated request (a visible countdown on the
    Connect screen; a running download counts as use) and when the app leaves the screen.
  - **Not encrypted.** This is plain HTTP; TLS is deliberately out of scope for now. The PIN keeps
    other people on the network from browsing your canvases, but someone who can sniff that network
    can read the traffic, PIN included. Use it on a network you trust.
- **PC side**: `pc-companion/VellumCompanion` (WPF, .NET) — enter the tablet's `IP:port`, hit
  Connect, browse the list, download a project's zip via a save dialog. See
  `pc-companion/README.md`. Enter the address *and* the PIN the tablet shows.

## Tier 2 — Live-ish mirror (working proof of concept, not push-based)

`GET /mirror/frame.jpg` on the tablet returns a JPEG snapshot (downscaled to ≤1024px) of whatever
canvas is currently open, sourced from `network/LiveCanvasBridge`. Point a browser or the PC app's
(currently placeholder) Live Mirror tab at it and poll on an interval for a rough live view. The mirror route needs the same PIN as everything else. This is
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
