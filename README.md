# App Throttler

An Android app that puts the network of the apps you pick — and only those apps — under your
control. It needs no root: it runs a local VPN, receives the selected apps' traffic, and can slow
it down, cut it off, or show you what it is doing before relaying it to the internet.

It is meant for developers and testers who want to see how an app behaves on 2G, 3G, a lossy
connection or no connection at all, on a real device, and which servers it talks to.

## Features

- **Per-app throttling.** Only the apps you select go through the throttle; everything else on
  the device uses the network normally.
- **Five controls.** Download speed, upload speed, latency, jitter and packet loss.
- **Presets.** 2G, 3G, 4G and Slow Wi-Fi.
- **Saved profiles.** Store a set of values (and the app selection) under a name and reuse it.
- **Live statistics.** Current download and upload rate plus session totals.
- **Apply while running.** Change the settings and apply them without stopping the VPN.
- **Per-app limits.** Give one app its own profile while the others share the session settings.
- **Firewall.** Block an app's network access — always, only on Wi-Fi or mobile data, or only
  while the screen is off — and block domains for all selected apps.
- **Connections list.** See every connection the selected apps make: app, host, port, bytes
  and whether it was blocked.
- **Packet capture.** Save the traffic to a `.pcap` file and open it in Wireshark.
- **Network events and scenarios.** Go offline for ten seconds, cut every connection, or run a
  scripted sequence such as "4G, then 2G, then offline, then 3G" while the app under test runs.
- **Control from outside the app.** A Quick Settings tile, launcher shortcuts, and adb commands
  for scripts and automated tests.
- **Rate chart and reports.** A live chart of the last minute, and CSV export of the
  connections list.
- **Share a setup.** Export settings, rules, profiles and scenarios to a file and import them
  on another device.
- **IPv4 and IPv6.** Both are tunnelled when the network has IPv6.
- **In-app log.** Tunnel events, connection errors and the previous session's crash report.

Per-app limits, the firewall and the app column of the connections list need Android 10 or
newer, because older versions cannot tell a VPN which app owns a connection. Throttling works
on every supported version.

## Requirements

| | |
|---|---|
| Android | 5.0 (API 21) or newer |
| JDK | 17 |
| Android SDK | Platform 34 |
| Gradle | 8.7, via the included wrapper |

## Build and install

```sh
./gradlew :app:assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # runs the JVM tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` gives the Gradle daemon a 4 GB heap. On a machine with little free memory,
override it for one build:

```sh
./gradlew :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx1536m --no-daemon
```

## Using the app

1. **Apps** — tick the apps to throttle. Only launchable apps are listed.
2. **Settings** — pick a preset or set the sliders.
3. **Home** — press **Start**. The first time, Android asks you to allow the VPN connection and
   (on Android 13+) notifications.
4. Use the selected apps. Home shows the traffic flowing through the throttle.
5. Press **Stop**, or use the **Stop** action on the notification.

While the VPN is running, changing a setting, a rule or the app selection shows an **Apply**
button; the running tunnel keeps its old values until you press it.

### Per-app rules

A selected app shows a row of options under its name on the **Apps** tab:

| Option | Effect |
|---|---|
| **Throttle: session settings** | The default: the app shares the values from the Settings tab. Tap to pick a preset or saved profile for this app alone. |
| **Block** | Refuses the app's connections. |
| **On Wi-Fi** / **On mobile data** | Limit the block to one kind of network. |
| **Only when screen is off** | Block only while the screen is off, to test background work losing its network. |

A per-app profile is copied when you pick it; later edits to the saved profile do not change the
rule. Rules are not part of saved profiles — they stay as set when you load one.

### Blocked domains

**Settings → Blocked domains** lists domains no selected app may reach, including their
subdomains. Name lookups for them are answered with "no such domain", and TLS or HTTP
connections that name them are reset.

### Traffic

The **Traffic** tab has two views:

- **Connections** — live and recent connections, newest first, with search and filters by app
  and "blocked only". Tap one for details. Repeated attempts at a blocked destination are
  shown as one entry with a count.
- **Logs** — tunnel events and errors.

**CSV** saves the connections currently listed (after search and filters) as a spreadsheet-ready
file. In a connection's details, **Block app** and **Block host** turn it into a rule.

**Capture** on the Connections view asks where to save a `.pcap` file and records every packet
crossing the tunnel until you press **Stop capture**, the VPN stops, or the file reaches 100 MB.
With an app filter set, only that app's packets are captured. Encrypted traffic is recorded as
it is on the wire; nothing is decrypted.

### Settings

| Setting | Range | What it does |
|---|---|---|
| Download speed | 10 kbps – 50 Mbps, or Unlimited | Caps data delivered to the selected apps |
| Upload speed | 10 kbps – 50 Mbps, or Unlimited | Caps data sent by the selected apps |
| Latency | 0 – 2000 ms | Delay added to every round trip, half in each direction |
| Jitter | 0 – 500 ms | Random variation of the latency, per packet |
| Packet loss | 0 – 30 % | Share of packets dropped at random |

The speed sliders are logarithmic, and the far right end means Unlimited. All selected apps share
these values unless an app is given its own profile.

### Presets

| Preset | Download | Upload | Latency | Jitter | Loss |
|---|---|---|---|---|---|
| 2G | 40 kbps | 20 kbps | 300 ms | 50 ms | 2.0 % |
| EDGE | 240 kbps | 200 kbps | 400 ms | 80 ms | 1.5 % |
| 3G | 384 kbps | 128 kbps | 100 ms | 20 ms | 1.0 % |
| 4G | 10.2 Mbps | 5.1 Mbps | 20 ms | 5 ms | 0.1 % |
| Slow Wi-Fi | 1 Mbps | 512 kbps | 50 ms | 10 ms | 0.5 % |
| Congested Wi-Fi | 2 Mbps | 1 Mbps | 150 ms | 100 ms | 5.0 % |
| Satellite | 5 Mbps | 1 Mbps | 600 ms | 30 ms | 0.5 % |
| Offline | — | — | — | — | 100 % |

Offline drops everything, so requests time out instead of failing at once — what an app sees
with a connected but dead network.

### Network events and scenarios

While the VPN is running, Home shows a **Network events** card:

| Button | Effect |
|---|---|
| **Offline 10 s** | No traffic gets through for ten seconds; connections stay open. |
| **2G for 60 s** | Every selected app is put on the 2G preset for a minute. |
| **Cut all connections** | Resets every open connection at once, as when a device changes network. |

A timed event shows a countdown and a **Cancel** button; when it ends, your settings (including
per-app profiles) apply again.

A **scenario** is a sequence of such steps. The **Profiles** tab lists three built-in ones —
*Train through a tunnel*, *Elevator ride*, *Flaky Wi-Fi* — and **New scenario** lets you build
your own from presets and saved profiles, each with a duration. **Run** needs the VPN to be on.

### Tile and shortcuts

- **Quick Settings tile.** Add the *Throttle* tile from the Quick Settings editor. It starts
  the VPN with the saved settings, or stops it. If VPN permission has not been given yet, or no
  app is selected, it opens the app instead.
- **Launcher shortcuts.** Long-press the app icon for *Start throttling* and *Stop throttling*.

### Control from adb

Scripts and automated tests can drive the app through an invisible activity. Each command is
`adb shell am start -n com.rangandale.appthrottler/.control.ControlActivity` plus:

| Purpose | Arguments |
|---|---|
| Start with the saved settings | `-a com.rangandale.appthrottler.action.START` |
| Start with a preset or saved profile | `-a com.rangandale.appthrottler.action.START --es profile 3G` |
| Stop | `-a com.rangandale.appthrottler.action.STOP` |
| Put all apps on a profile for a while | `-a com.rangandale.appthrottler.action.SCENARIO --es profile Offline --ei seconds 10` |
| Run a scenario by name | `-a com.rangandale.appthrottler.action.SCENARIO --es scenario 'Elevator ride'` |
| Cancel the running event or scenario | `-a com.rangandale.appthrottler.action.CANCEL_SCENARIO` |
| Cut all connections | `-a com.rangandale.appthrottler.action.RESET_CONNECTIONS` |

Names are matched without regard to case. A name containing spaces must be quoted for the
device's shell as well, so wrap the whole command: `adb shell "am start … --es scenario 'Elevator ride'"`.
Starting this way requires that VPN permission was granted once in the app. Results are written
to the log (`adb logcat -s AppThrottler`). Other apps on the device cannot use these commands.

### Share a setup

**Settings → Share this setup → Export** writes the settings, per-app rules, blocked domains,
saved profiles and scenarios to a JSON file. **Import** replaces the current settings and rules
with the file's, and adds its profiles and scenarios (replacing ones with the same name).

### Profiles

**Settings → Save as profile** stores the current values and app selection under a name. Saving
under an existing name replaces that profile. On the **Profiles** tab, **Load** copies a profile
into the editor and **Start** loads it and starts the VPN. Swipe a saved profile, or use its
delete button, to remove it.

Presets carry no app list, so loading one keeps the apps you have selected.

## Limitations

- **TCP and UDP only.** ICMP is dropped, so ping does not work inside a throttled app. IPv6
  packets with extension headers are dropped too.
- **IPv6 follows the network at start.** IPv6 is tunnelled if the network has it when the VPN
  starts; if the device then moves to a network without IPv6, restart the VPN.
- **TCP download loss is simulated, not literal.** The app terminates TCP locally, so instead of
  dropping a downloaded segment it stalls the connection for about as long as a retransmission
  would take. Uploads and UDP in both directions are really dropped.
- **One VPN at a time.** Android allows a single active VPN, so App Throttler cannot run
  alongside another VPN app, and it cannot be used as an always-on VPN.
- **Android 10+ for per-app features.** On Android 5–9 there is one profile per session and
  no firewall, because the system does not reveal which app owns a connection.
- **Domain rules depend on visible host names.** They see plain DNS, TLS server names and HTTP
  Host headers. While any domain is blocked, DNS-over-TLS is refused so lookups fall back to
  plain DNS; with Private DNS set to a fixed provider, name lookups in selected apps then fail.
  DNS-over-HTTPS inside an app, and QUIC connections to an address the app already knows, are
  not caught.
- **Blocking covers the selected apps only.** This is not a device-wide firewall.
- **System lookups are listed separately.** Name lookups are made by Android on an app's
  behalf, so they appear under "System (DNS and services)" rather than under the app.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Select at least one app to throttle" | No app is ticked on the Apps tab |
| "Could not start the VPN — see Logs" | None of the selected apps is installed any more, or Android refused the tunnel; the Logs tab has the reason |
| VPN stops by itself | Another VPN app took over, or the VPN was disconnected in system settings |
| A throttled app shows no connectivity | Check the Logs tab for connect errors; the app may rely on IPv6-only or ICMP |
| Changed settings have no effect | Press **Apply** — a running tunnel keeps the values it started with |
| A blocked app still connects | Its rule may be limited to the other network type or to screen-off; on Android 9 and older, rules are ignored |
| Names stop resolving after adding a blocked domain | Private DNS is set to a fixed provider; switch it to Automatic or Off |
| Connections show an address instead of a name | The name was resolved before the VPN started, or through encrypted DNS |

All log lines are also written to logcat:

```sh
adb logcat -s AppThrottler
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — how the tunnel, the forwarders and the throttle work.
- [Development](docs/DEVELOPMENT.md) — project layout, tests and on-device verification.
- [PLAN.md](PLAN.md) — the original design plan and the firewall/inspection roadmap, kept for
  history.
