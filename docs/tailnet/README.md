# Tailnet integration (Mihomo `type: tailscale`)

This document describes how AsteriskMETA runs Mihomo's embedded Tailscale (`tsnet`) outbound,
what the P0 change set verifies, the core API gap, and the plan for P1/P2. It is written for any
rooted or non-rooted Android device; addresses below are examples only.

## How it works today

A profile declares a Tailscale outbound, for example:

```yaml
proxies:
  - name: TS
    type: tailscale
    hostname: <tailnet-device-name>      # name shown in the Tailnet admin console
    state-dir: ./tailscale-<device-name> # keep one directory per identity
    udp: true
rules:
  - IP-CIDR,100.64.0.0/10,TS,no-resolve
  - MATCH,DIRECT
```

Facts established from the pinned sources (AsteriskMETA `main`, AndroidLibClashLite
`v1.19.32-android.4`, Mihomo `v1.19.32`):

| Area | Behavior | Where |
| --- | --- | --- |
| Local proxy port | The profile's `mixed-port` is always replaced by the app's *local proxy port* setting (default `10809`, `VpnDefaults.LOCAL_PROXY_PORT`). `port`/`socks-port` are forced to `0` unless a ROOT mode needs them; bind address is `127.0.0.1` unless LAN access is enabled. | `engine/mihomo/MihomoProfile.kt` |
| ROOT private ranges | ROOT rules bypass reserved ranges including `100.64.0.0/10` and `fc00::/7`. Entries in **Private addresses** become proxy-private CIDRs that asteriskd evaluates *before* the bypass list. A YAML `IP-CIDR` rule alone therefore does **not** capture transparent ROOT traffic to Tailnet peers. | `engine/root/config/RootNetworkPolicy.kt`, `AsteriskdConfigCompiler.kt`, `asteriskd_runtime.c` |
| ROOT TUN | TUN and eBPF modes do not use the private-address lists; Mihomo's TUN routes instead. | `AsteriskdConfigCompiler.kt` |
| Mihomo home | Both the embedded VPN core (`Libclash.init(<filesDir>/clash)`) and the ROOT core (`mihomo -d <filesDir>/clash`) use the same home, now shared through `mihomoHomeDirectory()`. Relative `state-dir` values resolve identically in both modes. | `engine/mihomo/MihomoHome.kt` |
| `state-dir` | Empty means `tailscale`; relative paths are joined to the home; absolute paths are kept. The embedded core is a CMFA build and accepts any path; the ROOT core (CLI build, CMFA disabled) rejects paths outside the home unless `SAFE_PATHS` is set. | Mihomo `adapter/outbound/tailscale.go`, `constant/path.go`; AndroidLibClashLite `scripts/prepare_sources.py` |
| ROOT core identity | asteriskd starts the ROOT core as uid `0`, gid `3005`. Files created in ROOT mode are owned by root. | `asteriskd_core.c` |
| Core API | AndroidLibClashLite exports no Tailscale state (no backend state, login URL, Tailnet IPs). `queryProxies` only lists the outbound type. | `libclash*.go` |

## What P0 adds

* `engine/mihomo/MihomoHome.kt` - single source of the Mihomo home for VPN and ROOT.
* `engine/root/config/RootNetworkPolicy.kt` - `rootPrivateAddressPolicy()` extracted as a pure
  function (behavior unchanged) so routing is unit-testable.
* `engine/network/TailnetAddressing.kt` - Tailnet ranges, `/32`/`/128` host override detection
  and a warning for broad overrides that would also proxy carrier CGNAT traffic.
* `engine/mihomo/tailscale/`
  * `MihomoTailscaleOutbound.kt` - reads tailscale outbounds from profile YAML; secrets are reduced
    to booleans (`hasAuthKey`, `usesCustomControlUrl`).
  * `TailscaleStateDirPolicy.kt` - read-only diagnostics: implicit default dir, two outbounds sharing
    one dir, dir outside the home (not portable to ROOT), another app's data dir (for example a
    standalone Tailscale app), shared storage. It never creates, reads or chmods state.
  * `TailscaleStatus.kt` - typed contract `Stopped | Starting | NeedsLogin | Running | Error`,
    `TailscaleStatusSource`, ipn-state mapping and `BundledCoreTailscaleStatusSource`, which reports
    `CoreApiMissing` instead of guessing.
  * `TailnetRedaction.kt` - redacts login URLs of any control server, `tskey-*`, node/machine keys,
    bearer tokens, cookies and `auth-key` assignments.
* `features/mihomo/tailscale/` - a Home dashboard **Tailnet** card, shown only when the selected
  profile has a tailscale outbound: name, hostname, state (currently "core has no status API"),
  state-dir warnings and, in TPROXY/TUN2SOCKS/BPF2SOCKS, the private-address hint.
* JVM unit tests (`app/src/test`, enabled for the debug variant) and a source-boundary test that
  keeps Tailnet code free of ROOT runtime imports, log scraping and hardcoded identities.
* `scripts/tailnet/tailnet-smoke.sh` (+ hermetic self-test) and `REGRESSION_CHECKLIST.md`.

VPN/ROOT separation: all new code is side-effect free and lives outside `engine/root` except the
pure policy extraction; the VPN lifecycle gains no ROOT call. The card reads only the selected
profile file and app settings.

AsteriskBOX/AsteriskNG: not affected. Tailscale outbounds are a Mihomo feature; the new code sits in
META-specific `engine/mihomo` and `features/mihomo` areas as `AGENTS.md` requires.

## Core API blocker

Typed state cannot be shown yet because the bundled core does not expose it. Log lines such as the
interactive login prompt are not an API: they are unstructured, localized by upstream, may carry
secrets and are not available in ROOT mode through the embedded bridge. The app therefore ships the
contract and a fake-driven presentation, and the production source returns `CoreApiMissing`.

Minimal binding change needed (separate, reviewable PRs; do not ship uncommitted binaries):

1. Mihomo (upstream request MetaCubeX/mihomo#3203): expose per-outbound status from
   `tsnet.Server.LocalClient().StatusWithoutPeers()` / `WatchIPNBus` - backend state, Tailnet IPs,
   self DNS name, `BrowseToURL` presence - through the Clash API (for example
   `GET /proxies/{name}/tailscale`) so ROOT mode can read it over the controller.
2. AndroidLibClashLite: export `QueryTailscaleStatus(name) string` (JSON) for the embedded path and
   an explicit `RequestTailscaleLoginUrl(name) string` that is only called on user action.
3. AsteriskMETA: implement `TailscaleStatusSource` for VPN (binding) and ROOT (controller), map
   with `tailscaleBackendStateFromIpn`, and redact every error.
4. Build the binding with `scripts/build_shared.py` from a tagged AndroidLibClashLite commit and bump
   `ANDROID_LIB_CLASH_LITE_VERSION`; never commit a locally built AAR.

## Known limitations (P0)

* Tailscale outbounds that come from proxy providers or override scripts are not detected by the
  card (it parses the selected profile file only).
* ROOT-created state is root-owned; switching the same identity to VPN mode may fail to read it.
  This needs on-device verification and an explicit ownership plan (P1); do not chmod state dirs.
* No status, login, IP or reset actions until the core API exists.

## P1 plan (not implemented)

1. Typed state events (`Stopped`, `Starting`, `NeedsLogin`, `Running`, `Error`) with optional
   on-demand login URL, Tailnet IPs and hostname, delivered by the API above.
2. Tailnet screen: state, open the login URL in the browser only after verifying the configured
   control host and expiry, show assigned IPs, disconnect/reset identity behind an explicit
   confirmation (reset deletes only the resolved state-dir inside the Mihomo home), backend errors.
3. Security: redact everywhere diagnostics are shared; never persist login URLs; keep state dirs
   in app-private storage; never log state files.
4. State ownership across modes: decide between (a) asteriskd restoring app ownership of the
   Mihomo-home state after ROOT stop, or (b) documented per-mode identities; verify no duplicate
   registration on mode switch.
5. DNS: MagicDNS names via Mihomo `nameserver-policy` (`+.ts.net` / custom tailnet suffix) in VPN,
   ROOT TPROXY and ROOT TUN; keep non-Tailnet DNS, carrier CGNAT, captive portals and split DNS
   working. Tests for Wi-Fi/cellular switching, airplane mode, restart, IPv4/IPv6 and app UID bypass.
6. Loop prevention: verify the core's own tsnet traffic (DERP/peer UDP, which may use carrier CGNAT
   addresses) is never re-intercepted by ROOT TPROXY/TUN, and that no Android `VpnService` is created
   in ROOT modes.

## P2 plan (not implemented)

* Inbound: evaluate `tsnet.Server.Listen()`/`ListenTLS()` port forwarding into Android loopback with
  a shared TS identity, opt-in per port, Tailnet ACL guidance; review the archived
  `fl0w1nd/mihomo-tailscale` code for licensing and design only. This is not a full L3 interface.
* Device list and exit-node management only after typed interfaces exist.
