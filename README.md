# App Throttler

An Android app that simulates a slow or unreliable network for the apps you pick — and only
those apps. It needs no root: it runs a local VPN, receives the selected apps' traffic, applies
speed limits, latency, jitter and packet loss, and relays it to the internet.

It is meant for developers and testers who want to see how an app behaves on 2G, 3G or a lossy
connection on a real device.

## Features

- **Per-app throttling.** Only the apps you select go through the throttle; everything else on
  the device uses the network normally.
- **Five controls.** Download speed, upload speed, latency, jitter and packet loss.
- **Presets.** 2G, 3G, 4G and Slow Wi-Fi.
- **Saved profiles.** Store a set of values (and the app selection) under a name and reuse it.
- **Live statistics.** Current download and upload rate plus session totals.
- **Apply while running.** Change the settings and apply them without stopping the VPN.
- **In-app log.** Tunnel events, connection errors and the previous session's crash report.

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

While the VPN is running, changing a setting or the app selection shows an **Apply** button;
the running tunnel keeps its old values until you press it.

### Settings

| Setting | Range | What it does |
|---|---|---|
| Download speed | 10 kbps – 50 Mbps, or Unlimited | Caps data delivered to the selected apps |
| Upload speed | 10 kbps – 50 Mbps, or Unlimited | Caps data sent by the selected apps |
| Latency | 0 – 2000 ms | Delay added to every round trip |
| Jitter | 0 – 500 ms | Random variation of the latency, per packet |
| Packet loss | 0 – 30 % | Share of packets dropped at random |

The speed sliders are logarithmic, and the far right end means Unlimited. All selected apps share
one set of limits.

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
- **One profile per session.** Android does not reveal which app sent a packet, so apps cannot
  have different limits within one session.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Select at least one app to throttle" | No app is ticked on the Apps tab |
| "Could not start the VPN — see Logs" | None of the selected apps is installed any more, or Android refused the tunnel; the Logs tab has the reason |
| VPN stops by itself | Another VPN app took over, or the VPN was disconnected in system settings |
| A throttled app shows no connectivity | Check the Logs tab for connect errors; the app may rely on IPv6-only or ICMP |
| Changed settings have no effect | Press **Apply** — a running tunnel keeps the values it started with |

All log lines are also written to logcat:

```sh
adb logcat -s AppThrottler
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — how the tunnel, the forwarders and the throttle work.
- [Development](docs/DEVELOPMENT.md) — project layout, tests and on-device verification.
- [PLAN.md](PLAN.md) — the original design plan, kept for history.
