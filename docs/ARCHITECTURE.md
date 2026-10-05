# Architecture

App Throttler is a single-module Android app: Jetpack Compose UI, MVVM with `StateFlow`, Hilt
for dependency injection, Room for saved profiles, and a `VpnService` that does the actual work.

```
┌───────────────────────────────────────────────────────────────┐
│ Compose UI    Home · Apps · Settings · Profiles · Logs        │
└──────────────────────────────┬────────────────────────────────┘
                               │ StateFlow
┌──────────────────────────────▼────────────────────────────────┐
│ ViewModels    VpnControlViewModel · AppListViewModel · Logs   │
└───────┬───────────────────────────────────────────┬───────────┘
        │ start / stop intents                      │
        │ activeConfig (StateFlow)                  │
┌───────▼────────────┐              ┌───────────────▼───────────┐
│ ThrottleVpnService │              │ Repositories              │
│ (foreground)       │              │ Profile (Room)            │
└───────┬────────────┘              │ CurrentConfig (prefs)     │
        │                           │ App (PackageManager)      │
┌───────▼────────────┐              │ Stats (TrafficCounters,   │
│ PacketProcessor    │              │        FlowRegistry)      │
│  ├ TcpForwarder    │              │ AppIdentity (UID → app)   │
│  └ UdpForwarder    │              └───────────────────────────┘
└───────┬────────────┘
        │ uses
┌───────▼────────────────────────────────────────────┐
│ FlowOwners · FlowPolicy · ShaperSet · FlowRegistry │
│ PacketCapture                                      │
└────────────────────────────────────────────────────┘
```

## How per-app throttling works

Android decides which traffic enters a VPN before the VPN app sees it.
`ThrottleVpnService` builds the tunnel with `addAllowedApplication()` for each selected package,
so the kernel routes only those apps' packets into the TUN interface. Every other app keeps using
the normal network and never touches this app's code.

What arrives on the TUN descriptor is raw IPv4 packets with no indication of which app sent
them. On Android 10 and newer the app asks the system instead — see
[Knowing which app owns a flow](#knowing-which-app-owns-a-flow). On older versions all selected
apps share one set of limits and there are no per-app rules.

The tunnel always carries IPv4 (`10.0.0.2/32`, route `0.0.0.0/0`). It carries IPv6 as well
(a unique local address, route `::/0`) when the active network has a routable IPv6 address at
start. Android blocks address families a VPN does not configure, so on an IPv4-only network the
selected apps cannot bypass the throttle over IPv6; they simply use IPv4.

If none of the selected packages is installed, the service refuses to start: a tunnel with no
allowed application would capture every app on the device.

## Packet flow

A TUN device hands over IP packets, but an unprivileged app can only send data through ordinary
sockets. So the app does not route packets — it **terminates** each connection on the TUN side
and opens a matching real socket on the other side. Every real socket is passed to
`VpnService.protect()`, which keeps it outside the tunnel; without that, its traffic would loop
back in.

### Upstream: app → internet

`PacketProcessor` runs this on the `throttler-outbound` thread:

```
TUN read
  → drop if not unfragmented IPv4 TCP/UDP
  → FlowOwners            which app sent it → that app's Shaper
  → PacketCapture         copy to the capture file, if one is running
  → PacketLossFilter      drop at random
  → upload TokenBucket    reserve bandwidth; yields a wait, or a drop if the queue is full
  → LatencyQueue          hold for the wait + half the latency ± half the jitter
```

The `throttler-drain` thread takes packets out of the latency queue when they are due and passes
them to `TcpForwarder` or `UdpForwarder`.

Rate limiting is expressed as a delay rather than by blocking the reader: `TokenBucket.reserve`
books the packet and says how long it must wait. That keeps one app's slow profile from holding
up another app's packets. A packet that would wait more than five seconds is dropped instead,
which is how a full queue on a real link behaves, and the sender's TCP stack slows down.

### Downstream: internet → app

Each forwarder has one selector thread watching its real sockets. Data read from them is wrapped
in freshly built IP packets (`IpPacket`) and written to the TUN device through `TunWriter`, which
serialises writes and counts bytes.

Packets towards the apps go through `TunWriter.deliver`, which holds them for the other half
of the latency (in a queue with its own thread) before writing them, so server-initiated data
arrives late too and a round trip gains the configured latency in total.

- **TCP** is throttled by backpressure. The forwarder reads from a server socket only when the
  owning app's download bucket has tokens, so the real connection slows down by itself.
- **UDP** has no backpressure, so replies that exceed the download rate are dropped, and the
  packet-loss setting is applied to them as well.

**TCP download loss.** Nothing sent towards the client is ever retransmitted, so a segment
cannot simply be dropped. Each segment instead has the configured chance of triggering a stall:
the connection stops reading from the server for `max(200 ms, 2 × latency)`, roughly what a
retransmission costs. With 100 % loss ("Offline") nothing is read at all until conditions change.

## IPv6 and address handles

Flow keys hold addresses as `Long` handles so they stay cheap to hash: an IPv4 address is its
32-bit value, an IPv6 address is an id above that range under which `IpPacket` remembers its 16
bytes. `IpPacket` hides the family behind `headerLength`, `totalLength`, `sourceOf` and
`destinationOf`, and its builders emit IPv4 or IPv6 according to the handles they are given
(IPv6 has no header checksum, a different pseudo-header, and fragments through an extension
header). The forwarders are otherwise family-agnostic; only the TCP MSS differs, because the
IPv6 header is 20 bytes longer.

## Scenarios and network events

`ShaperSet.overrideAll(params)` puts every app on one set of conditions until it is cleared.
Shapers are therefore looked up per packet and never cached by a flow, which is what lets
conditions change under open connections. `ThrottleVpnService` runs a scenario as a chain of
delayed steps on the main thread, each calling `overrideAll` with the step's values, and
publishes the current step through `ThrottleVpnService.scenario`. A network event is a one-step
scenario. "Cut all connections" resets every TCP flow and closes every UDP flow in the forwarders.

## Control surfaces

`VpnController` is the only class that sends intents to the service. The UI's view model, the
Quick Settings tile (`ThrottleTileService`) and `ControlActivity` all go through it.
`ControlActivity` is an invisible activity rather than a broadcast receiver because only a
foreground component may start the VPN's foreground service. It is exported so adb can reach it
and guarded by the `DUMP` permission, which the shell holds and ordinary apps do not; launcher
shortcuts reach it as the app itself.

## Knowing which app owns a flow

`ConnectivityManager.getConnectionOwnerUid()` (Android 10+) tells a VPN app the UID behind a TCP
or UDP flow. `FlowOwners` wraps it with a cache: the lookup is a call into the system, so it is
made once per flow — refreshed on a TCP SYN and forgotten when a UDP flow expires, because a
port can be reused by another app. A failed lookup yields "unknown app", which is never blocked
and uses the session settings.

Three things hang off the UID:

| Class | Decides |
|---|---|
| `ShaperSet` | Which `Shaper` (upload bucket, download bucket, latency lane, loss) applies: the app's own if it has a per-app profile, otherwise the shared session one. |
| `FlowPolicy` | Whether the flow is refused: the app's block rule, evaluated against the current network type and screen state, plus the blocked-domain list. |
| `FlowRegistry` | Where the flow is recorded for the connections list and per-app totals. |

Name lookups are made by Android's resolver on an app's behalf, so DNS flows carry a system
UID, not the app's. Blocking an app therefore refuses its connections but not its lookups.

## Firewall

`ThrottleVpnService` turns the config's `AppRule`s into a UID-keyed `FlowPolicy` when the tunnel
starts, and keeps it informed of the default network's transport (a network callback) and the
screen state (a broadcast receiver). The packet threads only read it.

- **Blocked app, TCP** — the SYN is answered with a reset and no real connection is opened.
  Existing connections are checked once a second, so a rule that starts to apply (screen turns
  off, Wi-Fi drops to mobile) also cuts connections that are already open.
- **Blocked app, UDP** — datagrams are dropped.
- **Blocked domain, DNS** — a query to port 53 for a listed name or a subdomain is answered
  locally with NXDOMAIN and never forwarded.
- **Blocked domain, TCP** — the first client bytes are inspected for a TLS server name or HTTP
  Host header; a match resets the connection before anything reaches the server.
- **Encrypted DNS** — while any domain is blocked, connections to port 853 are refused so the
  resolver falls back to plain DNS that the rules can see.

Every refusal is recorded in the `FlowRegistry`; repeated attempts at one destination share an
entry with a counter.

## Flow registry and host names

`FlowRegistry` is a process-wide record of the current session's flows: owner, destination,
bytes each way, start and end, and outcome (`OPEN`, `CLOSED`, `FAILED`, `BLOCKED`). Open flows
live in a map; finished ones in a ring of the latest 500. It resets when a tunnel is built.

Host names are learned passively, never by resolving anything:

- `UdpForwarder` parses DNS replies (`DnsMessage`) and stores address → queried name in
  `HostnameCache`; a new flow to that address is labelled with the name.
- `TcpForwarder` reads the TLS SNI or HTTP Host from the first client segment (`HostSniffer`),
  which overrides the DNS-derived label.

## Packet capture

`PacketCapture` writes classic pcap with link type `LINKTYPE_RAW` (101), so every record is one
IP packet exactly as it crossed the tunnel. Packets are offered at two points — after the TUN
read in `PacketProcessor`, and in `TunWriter` — together with the owning UID for the optional
per-app filter. Offering never blocks: packets are copied into a bounded queue and written by a
dedicated thread. If the queue is full the packet is left out of the file and counted, so a
slow disk costs capture completeness, never forwarding speed. The capture ends on request, when
the VPN stops, or at the size cap.

## TCP forwarder

`TcpForwarder` is a minimal TCP endpoint facing the client app's kernel. Per connection it tracks
the next sequence number expected from the client, its own send sequence, the client's last
acknowledgement and the client's advertised window.

| State | Meaning |
|---|---|
| `CONNECTING` | Client sent SYN; the real connect is in progress and the SYN is not answered yet |
| `ESTABLISHED` | Real connect succeeded and the SYN-ACK was sent |
| `CLIENT_CLOSED` | Client sent FIN; the server may still be sending |
| `SERVER_CLOSED` | Server closed and our FIN was sent; waiting for the client's FIN |

Behaviour worth knowing before changing this code:

- **The handshake waits for the real server.** A SYN is answered only once the real connect
  succeeds; a refused connect is answered with a reset. The app therefore sees connection
  failures as it would without the VPN.
- **Only in-order client data is accepted.** A segment that skips ahead is answered with a
  duplicate ACK and discarded; the client retransmits. This is what makes the packet-loss
  setting safe for TCP — dropped upstream segments are recovered by the client's own stack.
- **No retransmission towards the client.** Writes to the TUN device are not lost, and the
  forwarder never sends more than the client's window allows, so the client always accepts
  what it is sent.
- **Flow control in both directions.** Reads from the server pause when the client's window is
  full. Client data the server socket cannot take yet is queued (up to 64 KiB) and the
  advertised window shrinks accordingly.
- **Window probes.** The ACK that reopens a full window can be dropped by the loss filter.
  Once a second, a stalled connection sends a probe that makes the client repeat its ACK.
- **Whole segments under throttling.** Server data is released only when the bucket covers a
  full segment. Without this, a tight limit produces a stream of tiny segments whose headers
  outweigh their payload.
- **No window scaling or SACK.** The SYN-ACK carries only an MSS option, so each side's window
  is at most 64 KiB.
- **Timeouts.** Established connections are dropped after 60 minutes idle, half-closed ones
  after 2 minutes.

Client packets are handled on the drain thread and server sockets on the selector thread;
per-connection state is guarded by the connection object's monitor. Selection keys are touched
only by the selector thread — other threads queue the connection and wake the selector.

## UDP forwarder

`UdpForwarder` keeps one connected `DatagramChannel` per flow (source address and port,
destination address and port) and closes flows that have been idle for 60 seconds. Replies larger
than the tunnel MTU are split into IP fragments, because the TUN device rejects oversized writes.

## Throttle components

| Class | Role |
|---|---|
| `TokenBucket` | Byte-based rate limiter. Holds 250 ms of traffic (at least 4096 bytes). A rate of 0 means unlimited. |
| `LatencyQueue` | Delays packets by latency ± jitter plus any rate-limit wait. Within a lane (one `Shaper`), release times never go backwards, so jitter varies the delay without reordering packets. |
| `PacketLossFilter` | Drops a packet with the configured probability. |
| `Shaper`, `ShaperSet` | One `Shaper` bundles both buckets and a latency lane for a set of values; the set maps UIDs with their own profile to their own `Shaper`. |

## Service lifecycle and state

The ViewModel starts the service with an `ACTION_START` intent carrying the `ThrottleConfig` as
JSON (app list, session values, per-app rules, blocked domains), and stops it with `ACTION_STOP`. Starting while a tunnel is up replaces it, which is how
**Apply** works.

`ThrottleVpnService.activeConfig` is the source of truth for VPN state: it holds the config the
tunnel is running with, or `null` while stopped, and is set only after the tunnel is established.
The UI derives "running" and "unapplied changes" from it and never assumes a start succeeded.

The service is not sticky. It needs a config chosen in the UI, so it is not restarted after
process death and declares that it does not support always-on VPN.

DNS servers are taken from the active network (IPv4 only), followed by `8.8.8.8` and `1.1.1.1` as
fallbacks.

## Persistence

| Data | Where |
|---|---|
| Saved profiles | Room database `throttle.db`, table `throttle_profiles` |
| Config being edited: values, app list, per-app rules, blocked domains, profile name | SharedPreferences `current_config` |
| Custom scenarios | SharedPreferences `scenarios` |
| Last crash report | `files/last_crash.txt`, replayed into the Logs tab on next launch |

## Statistics

`TrafficCounters` holds two process-wide byte counters written by the packet threads: bytes
accepted from the apps and bytes delivered to them. `StatsRepository` samples them once per
second while the VPN is running and emits totals and rates, together with per-app totals and the
number of blocked attempts from the `FlowRegistry`. Everything resets when a tunnel is (re)built.
