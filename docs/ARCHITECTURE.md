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
┌───────▼────────────┐              │ Stats (TrafficCounters)   │
│ PacketProcessor    │              └───────────────────────────┘
│  ├ TcpForwarder    │
│  └ UdpForwarder    │
└────────────────────┘
```

## How per-app throttling works

Android decides which traffic enters a VPN before the VPN app sees it.
`ThrottleVpnService` builds the tunnel with `addAllowedApplication()` for each selected package,
so the kernel routes only those apps' packets into the TUN interface. Every other app keeps using
the normal network and never touches this app's code.

What arrives on the TUN descriptor is raw IPv4 packets with no indication of which app sent
them. That is why one session has one set of limits for all selected apps.

Only IPv4 is configured on the tunnel (`10.0.0.2/32`, route `0.0.0.0/0`). Android blocks address
families a VPN does not configure, so the selected apps cannot bypass the throttle over IPv6;
they fall back to IPv4.

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
  → PacketLossFilter      drop at random
  → upload TokenBucket    block until the packet fits the rate
  → LatencyQueue          hold for latency ± jitter
```

The `throttler-drain` thread takes packets out of the latency queue when they are due and passes
them to `TcpForwarder` or `UdpForwarder`.

Blocking the reader while the upload bucket is empty is deliberate: the TUN queue fills up and
the sending apps' own TCP stacks slow down, as they would on a slow link.

### Downstream: internet → app

Each forwarder has one selector thread watching its real sockets. Data read from them is wrapped
in freshly built IP packets (`IpPacket`) and written to the TUN device through `TunWriter`, which
serialises writes and counts bytes.

- **TCP** is throttled by backpressure. The forwarder reads from a server socket only when the
  download bucket has tokens, so the real connection slows down by itself.
- **UDP** has no backpressure, so replies that exceed the download rate are dropped, and the
  packet-loss setting is applied to them as well.

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
| `LatencyQueue` | Delays packets by latency ± jitter. Release times never go backwards, so jitter varies the delay without reordering packets. |
| `PacketLossFilter` | Drops a packet with the configured probability. |

## Service lifecycle and state

The ViewModel starts the service with an `ACTION_START` intent carrying the `ThrottleConfig`, and
stops it with `ACTION_STOP`. Starting while a tunnel is up replaces it, which is how
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
| Config being edited, and its profile name | SharedPreferences `current_config` |
| Last crash report | `files/last_crash.txt`, replayed into the Logs tab on next launch |

## Statistics

`TrafficCounters` holds two process-wide byte counters written by the packet threads: bytes
accepted from the apps and bytes delivered to them. `StatsRepository` samples them once per
second while the VPN is running and emits totals and rates. The counters reset when a tunnel is
(re)built.
