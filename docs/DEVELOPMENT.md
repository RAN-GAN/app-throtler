# Development

## Project layout

All code lives in the `app` module under `app/src/main/kotlin/com/rangandale/appthrottler/`.

| Package | Contents |
|---|---|
| `vpn` | `ThrottleVpnService` (tunnel lifecycle, notification), `PacketProcessor` (TUN read loop), `TrafficCounters` |
| `vpn.forward` | `TcpForwarder`, `UdpForwarder`, `IpPacket` (parsing and packet builders), `TunWriter` |
| `vpn.throttle` | `TokenBucket`, `LatencyQueue`, `PacketLossFilter`, `Shaper` and `ShaperSet` |
| `vpn.flow` | `FlowOwners` (which app owns a flow), `FlowPolicy` (block rules), `FlowRegistry` and `HostnameCache`, `HostSniffer` and `DnsMessage` |
| `vpn.capture` | `PacketCapture` and `PcapWriter` |
| `control` | `VpnController` (the only sender of service intents), `ThrottleTileService`, `ControlActivity` (shortcuts and adb) |
| `data.local` | Room database, DAO and entity for saved profiles |
| `data.repository` | Profiles, current config, installed apps, traffic statistics, UID → app identity |
| `domain.model` | `ThrottleConfig`, `ThrottleParams`, `AppRule`, `ThrottleProfile` and presets, `AppInfo`, `NetworkStats` |
| `presentation.viewmodel` | `VpnControlViewModel` (VPN, rule and profile state), `ConnectionsViewModel` (connections list, capture), `AppListViewModel`, `LogsViewModel` |
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
| `ForwarderTest` | TCP and UDP forwarders against real loopback sockets: handshake, data both ways, in-order acceptance, window handling and probes, pacing, resets, UDP fragmentation and loss; blocked apps, blocked domains over HTTP and DNS, refused encrypted DNS, flow records |
| `FlowHelpersTest` | TLS SNI and HTTP Host extraction, DNS reply parsing and NXDOMAIN replies, domain matching, owner caching |
| `PcapWriterTest` | pcap file layout, capture filtering by app |
| `IpPacketTest` | IPv4 and IPv6 packet builders, checksums, fragmentation, address handles, MSS option parsing |
| `TokenBucketTest` | Rate accuracy, burst cap, oversized requests, refunds, reservations and queue-full drops |
| `LatencyQueueTest` | Delay, ordering, independent lanes |
| `FormattersTest` | Speed slider scale and value formatting |

`ForwarderTest` plays the role of the client's kernel: it feeds hand-built IP packets into a
forwarder and inspects the packets written back. To make that possible, the forwarders take a
`protect` function instead of the `VpnService` and a `FlowOwners` built from a plain lambda, and `unitTests.isReturnDefaultValues` is enabled
so code that logs through `android.util.Log` runs on the JVM.

`ForwarderTest` also covers offline handling, simulated download loss, connection resets,
downstream latency and TCP over IPv6 (on the loopback address `::1`).

Not covered by automated tests: the scenario runner, the tile, `ControlActivity`, setup
import/export, tunnel setup in `ThrottleVpnService`, the TUN read loop, the
system's owner lookup, network and screen tracking, saving a capture through the file picker,
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
- [ ] Traffic → Connections lists flows under the right app, with host names
- [ ] An app set to **Block** cannot connect, and its attempts show as "Blocked"
- [ ] A block limited to mobile data lets the app through on Wi-Fi
- [ ] "Only when screen is off" cuts the app shortly after the screen turns off
- [ ] An app with its own profile is limited differently from the others
- [ ] A blocked domain fails to load in a selected app; other sites still load
- [ ] A capture opens in Wireshark and contains only the filtered app when a filter is set
- [ ] **Offline 10 s** stalls a page load, which then completes; **Cut all connections** resets it
- [ ] A scenario steps through its conditions with a countdown and can be cancelled
- [ ] The Quick Settings tile starts and stops the VPN; the launcher shortcuts do too
- [ ] The adb commands in the README start, stop and run events
- [ ] The log says "Tunnelling IPv4 and IPv6" on a dual-stack network, and IPv6 sites load
- [ ] Export, change something, import: the setup is back

To see which apps the system routes into the tunnel, list the per-UID routing rules while the
VPN is running:

```sh
adb shell ip rule | grep uidrange
adb shell cmd package list packages -U <package>     # UID of a package
```

## Debugging notes

- **Tile stops responding after reinstalling.** During development, replacing the APK can leave
  the system's binding to the tile stale: taps are registered but never reach the app. Remove
  the tile and add it again.

- **Logs.** Everything logged through `ThrottleLogger` appears on the Logs tab and under the
  `AppThrottler` logcat tag. The buffer keeps the latest 500 entries.
- **Per-app features need Android 10.** On older versions the owner lookup returns "unknown",
  rules are ignored and a warning is logged when the tunnel starts.
- **Reading a capture.** `adb pull` the saved file, or share it from the phone, and open it in
  Wireshark; the link type is raw IP, so there is no Ethernet header.
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
