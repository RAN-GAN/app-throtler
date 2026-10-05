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

**Capture** on the Connections view asks where to save a `.pcap` file and records every packet
crossing the tunnel until you press **Stop capture**, the VPN stops, or the file reaches 100 MB.
With an app filter set, only that app's packets are captured. Encrypted traffic is recorded as
it is on the wire; nothing is decrypted.

### Settings

| Setting | Range | What it does |
|---|---|---|
| Download speed | 10 kbps – 50 Mbps, or Unlimited | Caps data delivered to the selected apps |
| Upload speed | 10 kbps – 50 Mbps, or Unlimited | Caps data sent by the selected apps |
| Latency | 0 – 2000 ms | Delay added to every round trip |
| Jitter | 0 – 500 ms | Random variation of the latency, per packet |
| Packet loss | 0 – 30 % | Share of packets dropped at random |

The speed sliders are logarithmic, and the far right end means Unlimited. All selected apps share
these values unless an app is given its own profile.

### Presets

| Preset | Download | Upload | Latency | Jitter | Loss |
|---|---|---|---|---|---|
| 2G | 40 kbps | 20 kbps | 300 ms | 50 ms | 2.0 % |
| 3G | 384 kbps | 128 kbps | 100 ms | 20 ms | 1.0 % |
| 4G | 10.2 Mbps | 5.1 Mbps | 20 ms | 5 ms | 0.1 % |
| Slow Wi-Fi | 1 Mbps | 512 kbps | 50 ms | 10 ms | 0.5 % |

### Profiles

**Settings → Save as profile** stores the current values and app selection under a name. Saving
under an existing name replaces that profile. On the **Profiles** tab, **Load** copies a profile
into the editor and **Start** loads it and starts the VPN. Swipe a saved profile, or use its
delete button, to remove it.

Presets carry no app list, so loading one keeps the apps you have selected.

## Limitations

- **IPv4 TCP and UDP only.** IPv6 is not tunnelled, so the selected apps fall back to IPv4.
  ICMP is dropped, so ping does not work inside a throttled app.
- **Latency is applied to upstream packets.** The value is the delay added per round trip, not
  per direction.
- **Packet loss does not apply to TCP downloads.** It applies to everything the apps send, and
  to UDP replies. The app terminates TCP locally, so there is no downstream TCP packet to drop.
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
