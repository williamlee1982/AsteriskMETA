# Tailnet Inbound Port Forwarding: Design

Status: approved 2026-10-20 with all recommended defaults (D1-D7) plus the app toggle (D8).
Depends on: core release `v1.19.32-android.5-tailscale.3` (app PR #5, `chore/core-tailscale-3`).
This branch is based on it. Inbound forwarding needs a new core release (`tailscale.4`), see
`INBOUND_FORWARD_CORE_PLAN.md`.

## Goal

Other devices in the tailnet connect to `<tsnet IP or MagicDNS name>:<listen port>` of the
AsteriskMETA tsnet node (the Mihomo `type: tailscale` outbound). The core accepts the
connection on that node and forwards it to a local target on the phone, for example
`127.0.0.1:8022` (sshd) or `127.0.0.1:5555` (adb over TCP).

The app is generic. Hostnames, IPs, ports and targets come from the profile, override script
or app settings. Nothing is specific to one device.

Non-goals (v1): UDP forwarding, Tailscale Serve/Funnel/TLS termination, exposing the phone's
standalone Tailscale app identity, routing inbound traffic through Mihomo rules.

## Findings (verified in source, 2026-10-20)

- The app does **not** generate the tailscale outbound. It comes from the user's profile or
  override script. The app only reads it (`MihomoTailscaleOutbound.kt`,
  `MihomoTailnetProfile.kt`) and rewrites a few runtime keys (`putTailnetDnsOverrides`, called
  last in `putAsteriskRuntimeOverrides`, `MihomoProfile.kt`).
- Upstream `TailscaleOption` (mihomo v1.19.32 `adapter/outbound/tailscale.go`) has no inbound
  option. The proxy decoder (`structure.Decoder`, tag `proxy`) **silently ignores unknown
  keys**. A core without this feature therefore accepts `inbound-forwards` and does nothing.
- tsnet (`metacubex/tailscale` ff0ecd818181) supports `Server.Listen("tcp", ":port")` with a
  wildcard address, before login, and keeps the listener across logins. `ListenPacket` needs
  an explicit Tailscale IP, which only exists after login and can change. UDP is deferred for
  that reason.
- tsnet starts lazily (`Tailscale.start()` on first dial/DNS). Listeners need an eager start.
- On a profile reload, replaced outbounds are **not** closed explicitly.
  `autoCloseProxyAdapter` closes them from a GC finalizer, and only after every live
  connection that references them is gone (`adapter/outbound/base.go`). The old and the new
  outbound can therefore run tsnet on the **same state dir** for a while. That's harmless
  today because both start lazily, but with eager start it'd mean two live tsnet nodes with
  one identity.
- `executor.ApplyConfig` already has an overlay hook after `tunnel.OnRunning()`
  (`autoswitch.Apply`, in `scripts/prepare_sources.py`). Provider members are loaded by then.
- The app also runs a **standby** in-process core that only serves the UI
  (`exposePorts = false`, `VpnMihomoConfig.standbyProfileContent`). That core must never
  start forwards, or the identity would run twice.

## Recommended design

### 1. Config: a new option on the tailscale proxy (core-native)

```yaml
proxies:
  - name: TS
    type: tailscale
    hostname: my-phone-asterisk
    state-dir: ./tailscale-asterisk
    inbound-forwards:
      - listen: 8022            # port on the tsnet node, 1-65535, unique per outbound
        target: 127.0.0.1:8022  # host:port on the phone; IP literal (v1)
        # network: tcp          # optional, only "tcp" in v1
        # name: ssh             # optional label for the UI and logs
      - {listen: 5555, target: "127.0.0.1:5555", name: adb}
```

Why on the proxy:
- The forward belongs to one tsnet identity, and its lifetime equals the outbound's lifetime.
- It works the same in profiles, override scripts (William's current setup) and providers.
- Mihomo listener types (`listeners:`) were considered and rejected. The app replaces
  `listeners` in TPROXY/TUN modes, and upstream keeps an unchanged listener across reloads
  while the outbound instance is new, which would bind it to a stale tsnet server.

Validation (core, at parse time; config error = profile fails to load with a clear message):
- `listen` 1-65535, no duplicates within the outbound.
- `target` is `ip:port` with an IP literal. Default policy: **loopback only** (`127.0.0.0/8`,
  `::1`). Non-loopback targets require `allow-lan-target: true` on the outbound (open
  decision D3). Hostnames rejected in v1 (DNS through Mihomo may route back into the tunnel).
- `network` absent or `tcp`, otherwise an error ("udp not supported yet").

### 2. Core runtime: eager start + forwarder per outbound

New overlay file `overlay/mihomo/adapter/outbound/tailscale_inbound.go`, with pure logic in
`native/tailscale/inbound/` (no Mihomo/tsnet imports, unit-testable like `native/tailscale`).

- `TailscaleOption` gets `InboundForwards []TailscaleInboundForward \`proxy:"inbound-forwards,omitempty"\``
  (and `AllowLanTarget bool` if D3 = yes) via a `prepare_sources.py` patch.
- **No start at parse time.** `ParseWithBytes` is also used for validation and by the standby
  core, so nothing listens until the config is applied.
- `executor.ApplyConfig` hook after `tunnel.OnRunning()` (next to `autoswitch.Apply`):
  `tailscaleinbound.Reconcile()`, as follows:
  1. Enumerate running top-level outbounds (same source as `/asterisk/tailscale`; provider
     members are reported but not started, see D7) that implement a new `InboundController()`, forwarded through
     `autoCloseProxyAdapter` and `SingMux` exactly like `TailscaleController`.
  2. Any **previously active** forwarding outbound that is not in the running config gets
     its listeners stopped. If its resolved state dir equals a new outbound's, the old
     outbound is closed (`Close()`), so one identity never runs twice (decision D4).
  3. For each running outbound with forwards: `go start()` (eager), then
     `server.Listen("tcp", ":<listen>")` per forward. Start failures go into per-forward state
     and are retried with backoff (1s to 60s) while the outbound is alive.
- Accept loop per listener, `io.Copy` both ways. Half-close is supported, and both sides
  close when either side ends. The target dial uses Mihomo's `component/dialer` (direct, no
  proxy), so the VPN build's socket protect and the ROOT build's routing mark apply and the
  connection cannot loop back into the tunnel. A dial timeout of 10s applies.
- Limits: at most 64 active connections per forward (open decision D6). Over the limit, the
  connection is closed immediately and counted.
- Logging is info-level, one line per connection open/close:
  `[Tailscale](TS) inbound ssh 100.x.y.z:port -> 127.0.0.1:8022`. Peer tailnet IPs are not
  secrets. Payloads are never logged. All logs go through the existing redaction.
- `Tailscale.Close()` stops listeners and closes active forwarded connections (patched
  through the same `tailscaleClosed` hook).
- An outbound without `inbound-forwards` keeps today's lazy behaviour unchanged.

### 3. Status API

`/asterisk/tailscale` and the JNI `QueryTailscaleStatus` add one field per outbound
(additive, so older apps ignore it):

```json
"inboundForwards": [
  {"name":"ssh","network":"tcp","listen":8022,"target":"127.0.0.1:8022",
   "state":"Listening|Waiting|Error","error":"...","activeConns":1,"totalConns":12}
]
```

`Waiting` means the listener is registered but the node is not Running yet (needs login or is
starting). The field is absent (not `[]`) on cores without the feature. The app uses that to
show "this core doesn't support inbound forwards" when the profile declares some.

### 4. App

Phase A (ships with the core release):
- **Toggle (D8):** the Tailnet card gets a switch "启用转发（一直在线）". It's a persisted app
  setting, default **OFF**. When OFF, the runtime pipeline strips `inbound-forwards` from every
  tailscale proxy, the same as for the standby core, so TS stays lazy and nothing listens.
  When ON, forwards reach the active core, which starts TS eagerly and listens. Toggling
  reloads the running config. The switch is enabled only when the effective profile
  (after override scripts) has a top-level tailscale outbound with `inbound-forwards`.
  Otherwise it's disabled with a hint on how to declare forwards.
- Parse `inbound-forwards` into `MihomoTailscaleOutbound.inboundForwards` (non-secret) for the
  profile-only fallback.
- In the runtime pipeline, a new `putTailnetInboundOverrides(exposePorts)` strips
  `inbound-forwards` from every tailscale proxy when `exposePorts == false` (standby core) or
  the toggle is OFF.
  It's pure map logic in `engine.mihomo.tailscale`, with no `engine.root` import.
- Tailnet card: a "入站转发" section per outbound listing `listen → target`, state, active
  connection count, and the reachable address (`<dnsName or tailnet IP>:<listen>`, copyable).
  It shows a notice when forwards are configured but the core lacks support.
- `TailnetStatusPresentation`: with forwards configured, the outbound is started eagerly, so
  "Idle" no longer looks like "needs login" for these outbounds.

Phase B (decision D1): an in-app editor (add/remove `listen`, `target`, `name`) stored in
app settings per outbound key `(name, provider)` and merged into the runtime profile by the
same override function. Profile/script entries win on a `listen` port conflict, and the
conflict is shown on the card.

## Security

- Exposure is to the **whole tailnet**, subject to the tailnet ACL policy. The docs and UI tell
  users to restrict access in the ACLs (example `grants`/`acls` snippet limiting
  `tag:phone:8022` to the owner) and that the default "allow all" policy exposes the ports to
  every device and shared user.
- Targets are loopback only by default. Forwarded services see the source as `127.0.0.1` and
  must keep their own authentication (sshd keys, adb RSA confirmation).
- No auth URLs, keys or state-dir contents are logged, persisted or included in the status.
  The status `target` is a local address the user configured, which is fine.
- The standalone Tailscale app identity and its state dir are never touched. The existing
  `TailscaleStateDirPolicy` warnings (ForeignAppData, SharedWithAnotherOutbound) still apply.

## Risks

1. **Duplicate identity on reload** (GC-finalizer close). Mitigated by Reconcile closing the
   superseded same-state-dir outbound. Side effect: in-flight connections through the old
   TS outbound drop on reload. Needs a spike test (Plan Task C0) before relying on it.
2. **Battery/data**: eager start keeps the tsnet node online (DERP/keepalives) whenever forwards
   are configured, even without outbound traffic. Documented, and only applies to outbounds
   with forwards.
3. **Upstream drift**: the patch touches `TailscaleOption` and `executor.go`. `replace_once`
   fails loudly when mihomo changes, and `check_upstream.py` covers it.
4. **ROOT run modes**: a core dial to loopback is never redirected. A LAN target (if allowed)
   relies on the core's routing mark / uid bypass, so it gets a device test in each run mode.
5. **Android Doze / app killed**: forwards live only while the core runs. They aren't a
   persistent system service. Documented.
6. **adb over TCP** needs `adbd` listening on 127.0.0.1:5555 (`setprop service.adb.tcp.port`),
   which is outside the app's scope. The docs only give an example.

## Decisions (approved by William 2026-10-20: all defaults below, plus D8)

- D1 Configuration UI: **Phase A YAML/override script + read-only card. Phase B in-app editor
  as a follow-up PR.** Alternative: editor in the first PR (larger diff).
- D2 UDP: **TCP only in v1.** UDP needs per-peer session mapping and an IP-bound
  ListenPacket that's rebound on login.
- D3 Non-loopback targets (LAN devices behind the phone): **reject by default, allow with
  `allow-lan-target: true`.** Alternative: loopback only, no flag.
- D4 On reload, close the superseded outbound that shares the state dir: **yes** (avoids
  running one identity twice, at the cost of dropping in-flight TS connections on reload).
- D5 Eager start only when forwards exist: **yes**. Outbounds without forwards stay lazy.
- D6 Connection limit per forward: **64**, not configurable in v1.
- D7 Proxy providers: a remote provider refresh replaces its outbounds without
  `ApplyConfig`, so forwards would silently vanish until the GC closes the old instance.
  **v1 honours `inbound-forwards` only on top-level `proxies` (where override scripts put
  them). On provider members the option is ignored, and the core logs a warning and reports
  `state: "Error"` with "unsupported in proxy providers".** A follow-up can reconcile on
  provider updates.
- D8 App toggle "启用转发（一直在线）" on the Tailnet card: persisted, **default OFF**. OFF strips
  forwards from the runtime config. ON keeps them. A change triggers a config reload.
