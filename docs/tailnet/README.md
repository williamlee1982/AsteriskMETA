# Tailnet integration (Mihomo `type: tailscale`)

This document describes how AsteriskMETA runs Mihomo's embedded Tailscale (`tsnet`) outbound,
what the P0 and P1 change sets add, how the app consumes the core, and the plan for P2. It is written for any
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

Facts established from the sources (AsteriskMETA `main`, AndroidLibClashLite `main` + the
Tailscale state API (`v1.19.32-android.5-tailscale.2`), Mihomo stable `v1.19.32`, tailscale v1.104.1):

| Area | Behavior | Where |
| --- | --- | --- |
| Local proxy port | The profile's `mixed-port` is always replaced by the app's *local proxy port* setting (default `10809`, `VpnDefaults.LOCAL_PROXY_PORT`). `port`/`socks-port` are forced to `0` unless a ROOT mode needs them; bind address is `127.0.0.1` unless LAN access is enabled. | `engine/mihomo/MihomoProfile.kt` |
| ROOT private ranges | ROOT rules bypass reserved ranges including `100.64.0.0/10` and `fc00::/7`. Entries in **Private addresses** become proxy-private CIDRs that asteriskd evaluates *before* the bypass list. A YAML `IP-CIDR` rule alone therefore does **not** capture transparent ROOT traffic to Tailnet peers. | `engine/root/config/RootNetworkPolicy.kt`, `AsteriskdConfigCompiler.kt`, `asteriskd_runtime.c` |
| ROOT TUN | TUN and eBPF modes do not use the private-address lists; Mihomo's TUN routes instead. | `AsteriskdConfigCompiler.kt` |
| Mihomo home | Both the embedded VPN core (`Libclash.init(<filesDir>/clash)`) and the ROOT core (`mihomo -d <filesDir>/clash`) use the same home, now shared through `mihomoHomeDirectory()`. Relative `state-dir` values resolve identically in both modes. | `engine/mihomo/MihomoHome.kt` |
| `state-dir` | Empty means `tailscale`; relative paths are joined to the home; absolute paths are kept. The embedded core is a CMFA build and accepts any path; the ROOT core (CLI build, CMFA disabled) rejects paths outside the home unless `SAFE_PATHS` is set. | Mihomo `adapter/outbound/tailscale.go`, `constant/path.go`; AndroidLibClashLite `scripts/prepare_sources.py` |
| ROOT core identity | asteriskd starts the ROOT core as uid `0`, gid `3005`. Files created in ROOT mode are owned by root. | `asteriskd_core.c` |
| Core API | The core build used since P1 exports typed Tailscale state over JNI and the Clash controller (see below). Upstream AndroidLibClashLite releases and custom ROOT cores do not; the card then shows "no Tailscale state API". | `libclash_tailscale.go`, `native/tailscale` |

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
  * `TailscaleStatus.kt` - typed contract `Idle | Stopped | Starting | NeedsLogin | Running | Error`.
  * `TailnetRedaction.kt` - redacts login URLs of any control server, `tskey-*`, node/machine keys,
    bearer tokens, cookies and `auth-key` assignments.
* `features/mihomo/tailscale/` - a Home dashboard **Tailnet** card, shown when the running core or
  the selected profile has a tailscale outbound (see "Effective outbounds" below): name, hostname, state,
  state-dir warnings and, in TPROXY/TUN2SOCKS/BPF2SOCKS, the private-address hint.
* JVM unit tests (`app/src/test`, enabled for the debug variant) and a source-boundary test that
  keeps Tailnet code free of ROOT runtime imports, log scraping and hardcoded identities.
* `scripts/tailnet/tailnet-smoke.sh` (+ hermetic self-test) and `REGRESSION_CHECKLIST.md`.

(P0 shipped `BundledCoreTailscaleStatusSource`, which always reported `CoreApiMissing`; P1 replaced
it with the core API below.)

VPN/ROOT separation: all new code is side-effect free and lives outside `engine/root` except the
pure policy extraction; the VPN lifecycle gains no ROOT call. The card reads only the selected
profile file and app settings.

AsteriskBOX/AsteriskNG: not affected. Tailscale outbounds are a Mihomo feature; the new code sits in
META-specific `engine/mihomo` and `features/mihomo` areas as `AGENTS.md` requires.

## What P1 adds

### Core (forks under `williamlee1982`, feature branches)

| Repository | Branch | Change |
| --- | --- | --- |
| `AndroidLibClashLite` | `feat/tailscale-status` | Typed state API, redacted tsnet logs, Mihomo stable v1.19.32 (pinned, test-guarded), Go >= 1.27.1 |
| `tailscale` | `feat/upstream-v1.104.1` | MetaCubeX fork layout rebuilt on tailscale v1.104.1 (capability version 148) |
| `tailscale-wireguard-go` | `feat/upstream-417aef36` | wireguard-go matching tailscale v1.104.1, on `metacubex/gvisor` |

API (both the JNI export and the authenticated Clash controller group):

| Route / JNI | Result |
| --- | --- |
| `GET /asterisk/tailscale` / `queryTailscaleStatus()` | `{"outbounds":[{name,state,loginUrlAvailable,tailnetIps,hostname,dnsName,magicDnsSuffix,health,error}]}`; never starts tsnet, never carries a URL |
| `POST /asterisk/tailscale/login?name=` / `startTailscaleLogin` | 202; starts tsnet if needed and requests an interactive login |
| `GET /asterisk/tailscale/login-url?name=` / `queryTailscaleLoginURL` | The pending URL, on demand, `Cache-Control: no-store`; HTTP returns 403 unless the controller has a secret |
| `POST /asterisk/tailscale/logout?name=` / `logoutTailscale` | 204; deauthorizes the node |

States: `Idle` (declared, not started - Mihomo starts tsnet on first use), `Starting`,
`NeedsLogin`, `NeedsMachineAuth`, `Running`, `Stopped`, `Error`. A failed asynchronous login is
reported as `Error` until the next attempt. Errors and health are redacted in the core; the app
redacts again.

Release tags of the fork use `v<mihomo>-android.<upstream N>-tailscale.<M>` so they never collide
with upstream `-android.N` tags.

### How the app consumes the core

* `buildSrc/.../BuildConfig.kt` pins `ANDROID_LIB_CLASH_LITE_VERSION` (a release tag) and
  `ANDROID_LIB_CLASH_LITE_SHA256`; `:app:verifyLibclashAar` runs before every build and fails on a
  mismatch.
* `gradle.properties` `asterisk.libclash.repository` names the GitHub repository whose release
  hosts `libclash.aar` (currently the fork's prerelease).
* Local development: build with AndroidLibClashLite `scripts/build_shared.py --output <path>` and
  pass `-Pasterisk.libclash.localAar=<absolute path>` (or set it in `~/.gradle/gradle.properties`).
  The checksum is skipped for a local AAR. Never commit an AAR.

### App

* `engine/mihomo/tailscale/TailscaleCoreApi.kt` - JSON contract, mapping to
  `TailscaleBackendState`, login-URL validation (https, no user info, host = the outbound's
  `control-url` host, or `login.tailscale.com` without one).
* `MihomoBridge`/`MihomoControlClient`/`MihomoRuntimeRepository` - JNI in VPN mode, controller in
  ROOT and API mode (the same backend selection as other hot controls). A 404 on the list route
  means the core has no API (`CoreApiMissing`).
* `features/mihomo/tailscale/TailnetStatusSection.kt` - the Home **Tailnet** card polls state
  (2 s while starting or waiting for login, 10 s otherwise) and shows state, Tailnet IPs, hostname
  and MagicDNS name. **Log in** starts the login, waits for the URL, validates it and opens it with
  `ACTION_VIEW`; the URL stays in memory and is never logged, persisted or shown. **Log out / reset**
  asks for confirmation and logs the node out. It does not delete files: to wipe an identity,
  stop the service and delete its `state-dir` (in ROOT mode the files are root-owned).
* Redaction (`redactTailnetSecrets`) at every sink: the in-memory core/app log repositories (ingest
  and restore), persisted log files, platform `Log` output with stack traces, log export, toasts and
  the proxy error dialog (display and copy). The app has no crash reporter.

### DNS

The app reads the profile key `asterisk-tailnet-dns`, removes it before the profile reaches the
core and, by default whenever the profile declares a tailscale outbound:

* adds `dns.nameserver-policy` `+.<suffix>: tailscale://<outbound>` so MagicDNS names resolve
  through the tailnet in every run mode (VPN, ROOT TPROXY/TUN/TUN2SOCKS/BPF2SOCKS, fake-ip and
  redir-host);
* adds `DOMAIN-SUFFIX,<suffix>,<outbound>` after the app's DNS-hijack rules so connections to those
  names go through the tailnet.

```yaml
asterisk-tailnet-dns:
  outbound: TS                       # default: first tailscale outbound
  suffixes: [ts.net]                 # default; use your control server's base domain if custom
  rules: true                        # false: only the nameserver-policy entry
# asterisk-tailnet-dns: false        # disable
```

User `nameserver-policy` entries or `DOMAIN-SUFFIX` rules for the same suffix win. In ROOT
TPROXY/TUN2SOCKS/BPF2SOCKS with redir-host DNS, a resolved Tailnet IP is still bypassed unless it is
listed under **Private addresses** (`/32`); fake-ip mode does not need that. Raw configurations
(overrides disabled) are passed through unchanged. Bare short names (MagicDNS search domains) are
not handled.

### Effective outbounds (`-tailscale.2`)

The card lists the running core's effective tailscale outbounds, so outbounds from proxy
providers and override scripts are shown and follow provider refreshes (the card polls every 10 s,
2 s while a login is in progress, whenever the service runs).

* Core: every status carries `provider` (empty for top-level proxies) and a non-secret `config`
  (`hostname`, resolved `stateDir`, `stateDirDefault`, `ephemeral`, `hasAuthKey`,
  `customControlUrl`, `controlUrlHost`). An adapter is reported once even though Mihomo's implicit
  `default` provider wraps the top-level proxies; the same name in two providers is two entries.
  Login, login URL and logout take the provider (`provider=` on the controller, a second JNI
  argument); without it the top-level outbound or the only provider member is used, and an
  ambiguous name returns `409`.
* App: `TailnetOutboundKey(name, provider)` identifies an outbound. `resolveTailnetCardOutbounds`
  uses the core list while the core reports it; top-level profile outbounds the core does not run
  stay visible as "not in the running configuration". While the service is stopped, the core has
  no state API, the query fails or the first poll is pending, the card falls back to the profile
  file and says so. Rows from a provider show the provider name. State-dir diagnostics use the
  core-reported resolved directory; outbounds whose options are unknown (older core builds, for
  provider members) get none. VPN mode uses JNI, ROOT modes the controller, as before.

## Known limitations

* The DNS defaults below (`asterisk-tailnet-dns`) are computed from the profile file, so a
  tailscale outbound that only a proxy provider or an override script adds gets no default
  MagicDNS policy (`asterisk-tailnet-dns.outbound` must name a top-level outbound); add the
  `nameserver-policy` entry and `DOMAIN-SUFFIX` rule in the profile or the override instead.
* ROOT-created state is root-owned; switching the same identity to VPN mode may fail to read it.
  This needs on-device verification and an explicit ownership plan; do not chmod state dirs.
* Opening the login page through the HTTP controller requires a controller secret, so another app
  on the device cannot read the URL.
* The forked tailscale module needs Go >= 1.27.1 (the Go 1.20 back-ports of the MetaCubeX fork were
  dropped).

## P2 plan (not implemented)

* Inbound: evaluate `tsnet.Server.Listen()`/`ListenTLS()` port forwarding into Android loopback with
  a shared TS identity, opt-in per port, Tailnet ACL guidance; review the archived
  `fl0w1nd/mihomo-tailscale` code for licensing and design only. This is not a full L3 interface.
* Device list and exit-node management only after typed interfaces exist.
