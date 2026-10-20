# Tailnet Inbound Forwarding: Core Implementation Plan (AndroidLibClashLite)

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:test-driven-development for every
> task (red → green → refactor, and commit the red test output in the PR body).
> superpowers:systematic-debugging for Task C0 and any failure.
> superpowers:verification-before-completion before claiming done. Steps use `- [ ]`.

**Goal:** `inbound-forwards` on Mihomo `type: tailscale` proxies. TCP listeners on the
outbound's tsnet node forward to local `ip:port` targets. State is exposed through
`/asterisk/tailscale` and JNI.

**Repo/branch:** `williamlee1982/AndroidLibClashLite`, new branch `feat/tailscale-inbound` from
`main` (cbe1e6d, includes cb248d2). Release after merge: `v1.19.32-android.5-tailscale.4`
(prerelease, AAR with 4 ABIs, record SHA-256).

**Design:** `docs/tailnet/INBOUND_FORWARD_DESIGN.md` in AsteriskMETA (`feat/tailscale-inbound`).

**Constraints:** Mihomo stays at stable v1.19.32. Patches go through `prepare_sources.py`
`replace_once` only, with no edits to upstream sources. No login URL/auth key in logs, tests or
fixtures. Fixtures use `example.test`, `100.101.102.103` and ports ≥ 20000. Tags are
`foss,with_gvisor,cmfa`. Run tests with `python3 scripts/test_shared.py` and
`scripts/test_pinned_core.py`.

---

### Task C0: Spike: verify assumptions (systematic-debugging, no production code)

- [ ] Write a throwaway test in a temp dir (not committed) confirming:
  1. `executor.ParseWithBytes` with an unknown proxy key succeeds (decoder ignores it), so an
     old core silently ignores `inbound-forwards`.
  2. After two `ApplyConfig` calls with the same tailscale proxy, the first adapter is not
     closed until `runtime.GC()` and its finalizer run (log "Closing outdated proxy").
     Record whether a live `RefConn` delays it.
  3. `tsnet.Server.Listen("tcp", ":N")` returns before login (`Start` only, no control
     contact needed). Run with an unreachable `control-url` (e.g. `http://127.0.0.1:1`) and
     an empty temp state dir.
- [ ] Write the findings into the PR description. If 2 or 3 differ from the design, stop and
  update the design before Task C3.

### Task C1: Pure option parsing and validation: `native/tailscale/inbound`

**Files:** create `native/tailscale/inbound/config.go` and `config_test.go`.

- [ ] RED tests (`TestParseForwards*`):
  - valid `{listen: 20022, target: "127.0.0.1:22"}` → network `tcp`, name defaults to `"20022"`
  - `[::1]:22` accepted. `localhost:22`, `example.test:22` → error "target must be an IP literal"
  - listen 0, 65536, duplicate listen → error naming the entry index
  - `network: udp` → error "udp inbound forwards are not supported yet"
  - `192.168.1.10:22` → error unless `allowLanTarget`, accepted with it
  - `0.0.0.0:22`, multicast, port 0 target → error
  - empty list → no forwards, and `HasForwards() == false`
- [ ] GREEN: `type Forward struct{Name, Network string; Listen uint16; Target netip.AddrPort}`,
  `func Parse(raw []RawForward, allowLanTarget bool) ([]Forward, error)`.

### Task C2: Forwarder engine (no tsnet): `native/tailscale/inbound`

**Files:** `forwarder.go`, `forwarder_test.go`.

The engine takes injected `Listen func(network, addr string) (net.Listener, error)` and
`Dial func(ctx, network, addr string) (net.Conn, error)`. Tests use `net.Listen` on 127.0.0.1
and in-memory listeners.

- [ ] RED tests:
  - `TestForwardCopiesBothDirections`: echo target, client writes/reads 1 MiB, bytes equal.
  - `TestForwardHalfClose`: client `CloseWrite`; the target sees EOF and its reply still
    arrives.
  - `TestForwardTargetDown`: dial refused → client conn closed, `Error` counter, listener
    keeps accepting, state stays `Listening`.
  - `TestForwardConnLimit`: limit 2, third conn closed immediately, `rejected` == 1.
  - `TestForwardStopClosesActive`: `Stop()` closes the listener and active conns. No goroutine
    leak (count goroutines before/after with a deadline).
  - `TestForwardListenRetry`: Listen fails twice then succeeds, backoff via an injected clock,
    state `Error` → `Listening`.
  - `TestForwardSnapshot`: `Snapshot()` returns listen/target/state/activeConns/totalConns
    and never includes anything except the configured fields.
  - `TestForwardWaitingState`: `ready func() bool` false → `Waiting`, true → `Listening`.
- [ ] GREEN: `Manager` with `Start`, `Stop`, `Snapshot`, and per-connection logs through an
  injected logger. Run the race detector: `test_shared.py -race -run Forward`.

### Task C3: Overlay wiring: option, eager start, reconcile

**Files:** create `overlay/mihomo/adapter/outbound/tailscale_inbound.go` and
`native/tailscale/inbound/registry.go` (+tests). Modify `scripts/prepare_sources.py`.

- [ ] Patch `TailscaleOption` (`replace_once` on `ExitNodeAllowLANAccess *bool ...` line) to add
  `InboundForwards []TailscaleInboundForward \`proxy:"inbound-forwards,omitempty"\`` and
  `AllowLanTarget bool \`proxy:"allow-lan-target,omitempty"\``. Validate in `NewTailscale`
  through a hook (patch the first line of `NewTailscale` to call `tailscaleValidateInbound(option)`).
- [ ] Patch `Close()` (`tailscaleClosed` already hooks it) to stop the outbound's forward manager.
- [ ] `InboundController()` on `*Tailscale`, forwarded on `*autoCloseProxyAdapter` and `*SingMux`
  like `TailscaleController` (cb248d2 lesson: wrappers hide extension methods).
- [ ] Patch `hub/executor/executor.go` after `tunnel.OnRunning()` (same anchor as
  `autoswitch.Apply`; extend that replacement rather than adding a second one on the same
  anchor) to call `tailscaleinbound.Reconcile()`.
- [ ] Registry: `Reconcile(running []Entry)`. It stops managers of outbounds no longer
  running, and closes a superseded outbound whose resolved state dir equals a running
  one's (design D4). It starts managers for running top-level outbounds with forwards, with
  `ready` = backend state Running and `Listen` = `server.Listen`. Provider members with
  forwards report `Error: unsupported in proxy providers` (D7).
- [ ] Target dial: `dialer.DialContext` from `component/dialer` with the outbound's
  `Interface`/`RoutingMark` options not applied (the target is local). Document why in a
  comment.
- [ ] RED tests (`native/tailscale/api/config_integration_test.go` style, real parser, no tsnet
  start, plain + smux):
  - `TestRealConfigInboundForwardsParsed`: the YAML with `inbound-forwards` parses, and
    `InboundController()` through the wrappers returns 2 forwards.
  - `TestRealConfigInboundForwardsInvalid`: `network: udp` → `ParseWithBytes` error text.
  - `TestReconcileClosesSupersededSameStateDir`: fake entries (registry unit test) → old
    closed once, the different-state-dir one untouched.
  - `TestReconcileProviderMemberNotStarted`.
  - `TestParseDoesNotStart`: parsing alone never calls Start (lifecycle stays Idle).
- [ ] GREEN, then the full `test_shared.py`, `test_pinned_core.py`, `go vet` with tags.

### Task C4: Status API

**Files:** `native/tailscale/status.go`, `native/tailscale/api/api.go` and their tests.

- [ ] RED: `TestStatusIncludesInboundForwards` (JSON field `inboundForwards`, omitted when the
  outbound has none), and a redaction test that a target or name containing
  `tskey-`/a login URL is redacted (defence in depth).
- [ ] GREEN. Update `README.md` (API section) and `libclash_tailscale.go` doc comment.

### Task C5: Build, device check, release

- [ ] `python scripts/build_shared.py`: 4 ABIs, symbols include
  `autoCloseProxyAdapter).InboundController` and `SingMux).InboundController`.
- [ ] Device check (ROOT and VPN modes, generic profile with forwards to a local `nc -l`
  listener on a high port). From another tailnet device, `nc <tsnet-ip> <port>` round-trips.
  Reload the profile: no duplicate node appears in the admin console, and the old listener
  is gone. Never paste login URLs into logs/PR.
- [ ] Requesting-code-review, PR, William merges, then tag
  `v1.19.32-android.5-tailscale.4`. Verify the release asset by download + SHA-256.
