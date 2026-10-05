# Development

## Project layout

All code lives in the `app` module under `app/src/main/kotlin/com/rangandale/appthrottler/`.

| Package | Contents |
|---|---|
| `vpn` | `ThrottleVpnService` (tunnel lifecycle, notification), `PacketProcessor` (TUN read loop), `TrafficCounters` |
| `vpn.forward` | `TcpForwarder`, `UdpForwarder`, `IpPacket` (parsing and packet builders), `TunWriter` |
| `vpn.throttle` | `TokenBucket`, `LatencyQueue`, `PacketLossFilter` |
| `data.local` | Room database, DAO and entity for saved profiles |
| `data.repository` | Profiles, current config, installed apps, traffic statistics |
| `domain.model` | `ThrottleConfig`, `ThrottleProfile` and presets, `AppInfo`, `NetworkStats` |
| `presentation.viewmodel` | `VpnControlViewModel` (all VPN and profile state), `AppListViewModel`, `LogsViewModel` |
| `presentation.ui` | Screens, shared components, value formatting and the speed slider scale |
| `ui.navigation`, `ui.theme` | Bottom-bar navigation, permission handling, theme |
| `logging` | `ThrottleLogger` (in-app log, mirrored to logcat), `CrashHandler` |
| `di` | Hilt module |

Dependency versions are in `gradle/libs.versions.toml`.

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

The tests run on the JVM; no device is needed.

| Test class | Covers |
|---|---|
| `ForwarderTest` | TCP and UDP forwarders against real loopback sockets: handshake, data both ways, in-order acceptance, window handling and probes, pacing, resets, UDP fragmentation and loss |
| `IpPacketTest` | Packet builders, checksums, fragmentation, MSS option parsing |
| `TokenBucketTest` | Rate accuracy, burst cap, oversized requests, refunds |
| `LatencyQueueTest` | Delay and ordering |
| `FormattersTest` | Speed slider scale and value formatting |

`ForwarderTest` plays the role of the client's kernel: it feeds hand-built IP packets into a
forwarder and inspects the packets written back. To make that possible, the forwarders take a
`protect` function instead of the `VpnService`, and `unitTests.isReturnDefaultValues` is enabled
so code that logs through `android.util.Log` runs on the JVM.

Not covered by automated tests: tunnel setup in `ThrottleVpnService`, the TUN read loop,
permissions, the notification and the Compose screens. Verify those on a device.

## Verifying on a device

Use a physical phone with USB debugging; a VPN cannot be exercised meaningfully in unit tests.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s AppThrottler
```

Checklist:

- [ ] First **Start** shows the VPN consent dialog, and the notification prompt on Android 13+
- [ ] The key icon appears in the status bar while running
- [ ] A selected app is clearly slower under the 2G preset
- [ ] A non-selected app is unaffected and the Home counters do not move while it loads
- [ ] Home shows live rates and growing totals
- [ ] Changing a setting while running shows **Apply**, and applying takes effect
- [ ] **Stop** in the app and the **Stop** notification action both remove the key icon
- [ ] A saved profile is still there after force-stopping and reopening the app
- [ ] Loading a profile sets every slider to its values

To see which apps the system routes into the tunnel, list the per-UID routing rules while the
VPN is running:

```sh
adb shell ip rule | grep uidrange
adb shell cmd package list packages -U <package>     # UID of a package
```

## Debugging notes

- **Logs.** Everything logged through `ThrottleLogger` appears on the Logs tab and under the
  `AppThrottler` logcat tag. The buffer keeps the latest 500 entries.
- **Unsupported packets.** A periodic "Dropped N unsupported packet(s)" line is expected. It
  reports ICMP and IPv6 link-local traffic on the tunnel interface, which is not forwarded.
- **Crashes.** An uncaught exception is written to `files/last_crash.txt` and shown at the top
  of the Logs tab on the next launch.
- **Measuring the throttle.** The Home counters include IP and TCP headers and count every
  selected app, so compare them against the limit over a window of 30 seconds or more, with
  only one selected app generating traffic.

## Release builds

`release` has no signing config and minification is off. Publishing to Google Play would also
require a privacy-policy declaration for `QUERY_ALL_PACKAGES` and a justification for the
`specialUse` foreground service type.
