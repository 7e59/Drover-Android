# Architecture

The Android VpnService allowlist contains only com.discord. IPv4/IPv6 default routes feed a vendored hev-socks5-tunnel/lwIP engine, which speaks SOCKS5 to a loopback Java relay using random per-session RFC1929 credentials. The relay protects its outbound sockets and connects directly to destinations.

TCP sockets bind locally to create their Android descriptor before protect and connect. UDP associations are isolated by control connection and filter return packets by previously contacted peers. Actual Discord discovery headers and SSRC are matched without logging payloads or account information. The optional prelude sends bytes0 and1, then waits50ms before the first valid discovery for that peer.

Native lifecycle operations are serialized off the main thread. Stop explicitly joins the engine, closes relay sockets and TUN, then reports stopped. Network/DNS callback snapshots trigger serialized resets. Native source revisions and full notices are included. No external tunnel, TLS interception, generic STUN scanner or Discord API account login is implemented.
