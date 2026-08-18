# Vellum Studio — PC Companion

A Windows desktop companion app for **Vellum Studio**, the Android tablet
drawing app. This is a scaffold: a small, real, buildable WPF app that
covers the first useful slice of PC-side functionality, with clearly
marked placeholders for what comes next.

## What this scaffold does today

- Lets you type in the tablet's LAN address (`IP:port`, default port
  `8642`) and press **Connect**.
- On connect, it calls `GET /projects` on the tablet's sync server and
  lists the returned projects (name + last-updated date) in a `ListView`.
- Lets you select a project and press **Download Project…**, which calls
  `GET /projects/{id}/export.zip` and saves the resulting zip (flattened
  PNG + layer PNGs + `metadata.json`) wherever you choose via a standard
  Windows `SaveFileDialog`. Nothing is ever written to disk without you
  picking the location.
- Shows a **Live Mirror** tab that is an intentionally inert placeholder
  (disabled button, "Coming soon" label) for a future real-time canvas
  mirror — see below.
- Handles connection failures (unreachable host, timeout, bad JSON, etc.)
  by showing a status message instead of crashing.

### Running it

```powershell
cd pc-companion
dotnet run --project VellumCompanion
```

Built and tested against the **.NET 10 SDK** (`10.0.302`), targeting
`net10.0-windows` (WPF), since that's what was installed on the
development machine at scaffold time. There is nothing net10-specific in
the code — retargeting the `.csproj`'s `<TargetFramework>` to
`net8.0-windows` and rebuilding should work unchanged if you need to
match an older SDK elsewhere.

## Project layout

```
pc-companion/
  VellumCompanion.sln
  README.md
  VellumCompanion/
    VellumCompanion.csproj
    App.xaml / App.xaml.cs
    MainWindow.xaml / MainWindow.xaml.cs   # UI: address bar, project list, download, mirror tab
    Models/
      ProjectSummary.cs                    # {id, name, updatedAt, thumbnailUrl}
    Services/
      TabletClient.cs                      # GetProjectsAsync / DownloadProjectZipAsync
```

## How it talks to the tablet app's sync server

The Android side (a separate project, being built in parallel) runs a
small HTTP server on the tablet, bound to its LAN IP, default port
`8642`, exposing:

- `GET /projects` → JSON array of
  `{ "id": string, "name": string, "updatedAt": ISO-8601 string, "thumbnailUrl": string }`
- `GET /projects/{id}/export.zip` → a zip containing the flattened PNG,
  the individual layer PNGs, and a `metadata.json` describing the project.

**Discovery today is manual.** You type the tablet's IP:port into the
text box yourself (find it in the tablet app's settings, or via your
router's client list). There is no NSD/mDNS/Bonjour discovery wired up
on the PC side yet — that's a nice-to-have, not a blocker, since both
devices are assumed to be on the same LAN and the tablet's address
rarely changes on a typical home network. A future iteration could add
`Zeroconf`/`Makaretu.Dns.Multicast`-style mDNS browsing on the PC to
auto-populate the address field.

## Future: full tablet-as-display mode

The long-term aspiration for this companion app is to let the Android
tablet act as a genuine second display/input device for the PC — draw
on the tablet, see and edit strokes as if it were a Wacom-style pen
display plugged directly into Windows. **That is explicitly out of scope
for this scaffold**, and it's worth being honest about why it's a much
bigger project than everything above:

1. **A virtual display driver.** Windows needs a kernel-mode indirect
   display driver (IDD) to present a virtual monitor that the tablet's
   mirrored frames can be pushed to, and that the OS treats as a real
   display for window placement, DPI, etc. Prior art to build on:
   [`IddSampleDriver`](https://github.com/roshkins/IddSampleDriver) (based
   on Microsoft's IddCx sample). This requires writing a proper WDF/UMDF
   or KMDF driver, testing it, and getting it signed (or running the
   target machine with test-signing enabled) before Windows will load it.

2. **A virtual HID / pen-injection driver.** To make tablet touches and
   pen strokes show up as real Windows pen/pointer input (pressure,
   tilt, hover — not just mouse clicks), you need a virtual HID device
   or an input-injection driver. Prior art: projects like
   [Interception](https://github.com/oblitum/Interception) (low-level
   keyboard/mouse driver) or [ViGEm](https://github.com/ViGEm) (virtual
   gamepad bus driver) show the pattern, though a pen/digitizer device
   would need its own HID report descriptor work; Windows' built-in
   `Windows.Devices.HumanInterfaceDevice` / `IddCx` pen injection APIs
   would also need to be investigated as an alternative to a bespoke
   driver.

Both of these are **kernel-mode, driver-signed, administrator-install**
pieces of software. Getting them onto a machine safely is a deliberate,
supervised process — driver signing certificates, test-signing mode or
EV code-signing, `pnputil`/`devcon` installation, and real hardware
testing. None of that is something a coding agent should do
automatically as part of scaffolding a companion app, which is why this
repository stops well short of it.

**The Live Mirror tab in this scaffold is the lightweight, low-risk step
toward that goal.** It's strictly *view-only*: the tablet would push
downscaled JPEG frames of its canvas over a WebSocket endpoint
(`ws://{tablet}:8642/mirror`), and the PC would decode and display each
frame in an `Image` control. No input is sent back to the tablet, and no
driver of any kind is required — it's just a receiving client for a
stream of images. That's implemented today only as UI scaffolding (a
disabled button and a code comment describing the protocol) and is a
reasonable next milestone before anything resembling the full
tablet-as-display mode above is attempted.
