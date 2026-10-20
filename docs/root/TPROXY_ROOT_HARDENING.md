# TPROXY / ROOT runtime hardening (fork, v1.2.21)

This fork carries a patched asteriskd (`williamlee1982/asteriskd`, tag `v2.1.11-wl.1`,
branched from upstream `v2.1.11`). Upstream sync: when rebasing onto a newer upstream
asteriskd, re-apply or upstream these patches; until then `ASTERISKD_VERSION` points at the
fork tag and `.gitmodules` at the fork URL.

## Settings (ROOT)

| Setting | Default | Effect |
| --- | --- | --- |
| Capture network-bound apps (TPROXY only) | off | Off keeps upstream behaviour: traffic from apps that bind an explicit network (netd fwmark rule at priority 13000, e.g. some VoIP/Wi-Fi assistant/IMS apps) bypasses the proxy. On adds owned `fwmark 0x20000000/0x60000000 lookup 160` rules at priority 12998 (IPv4/IPv6), so those apps are captured too. Turn on only if you need it; it can break apps that deliberately pin a network. |
| Block encrypted DNS (port 853) | off | Only when local DNS is enabled. Drops TCP/UDP 853 from every app except the core GID, so *automatic* Private DNS falls back to port 53 and gets intercepted. **Strict** Private DNS (a hostname) will fail; switch Private DNS to Off/Automatic instead. |

With the 853 block off, a ROOT start checks `settings get global private_dns_mode` and logs a
warning (`root_private_dns`) when Private DNS is `hostname` (always bypasses DNS hijack) or
`opportunistic` (may bypass it).

## Runtime behaviour

- **Orphan cleanup / watchdog.** `asteriskd cleanup --config <cfg>` removes owned rules,
  routes and policy rules when no supervisor is live; the app runs it on Stop/Start when no
  daemon is bound. A detached guard process (`asteriskd guard`) runs the same cleanup if the
  supervisor dies without an orderly shutdown (SIGKILL, LMK), retrying up to three times.
  A pass that leaves owned resources behind returns `internal_error`; Stop then reports
  "ROOT cleanup incomplete" instead of success.
- **Readiness.** TPROXY readiness requires a TCP socket in `LISTEN` state on the transparent
  port (tcp or tcp6 table, local column only) *and* a bound UDP socket on that port.
- **Preflight.** Before any rule is written, TPROXY checks iptables/ip6tables, the `TPROXY`
  target, `socket` match and IPv6 availability and fails with an actionable message
  (`failed_stage=capability-preflight`). A modular `xt_TPROXY` is autoloaded by a `-C` probe
  before the check. Without an IPv6 stack the IPv6 path is skipped; with IPv6 active but no
  usable ip6tables/IPv6 TPROXY, start fails (IPv6 DNS would bypass local DNS) and asks you to
  enable *Disable system IPv6* or turn off local DNS.
- **Rule order.** OUTPUT hooks are inserted at the head of the chain and verified after
  apply/reconcile (a warning is logged if another tool moved them). Established transparent
  sockets (`-m socket --transparent`) are marked and returned before the general TPROXY rule
  for both families. Core-GID traffic has the Asterisk mark bits cleared.
- **Routing tables 160/168.** Reconcile deletes only the routes asteriskd owns
  (`local default dev lo` in 160; `default dev <configured tunnel>` in 168); foreign routes
  in those tables are left alone.
- **Profile safety.** In ROOT modes a non-loopback raw controller without a secret blocks the
  start; the managed controller always carries a random secret. `routing-mark` values that
  overlap the Asterisk mask `0x60000000` are stripped (they would loop through TPROXY). The
  name `dns-out` is reserved for the injected DNS outbound. A raw config only counts as
  hijacking DNS when its `dns-out` rule applies to the TPROXY inbound (or is unrestricted).
- **Start timeout.** The app waits for the daemon's terminal state (re-watching while it is
  still starting) up to a 150 s hard cap, then stops the daemon instead of leaving it running.

## Known residuals

- While the guard runs its cleanup (a few seconds after an unclean supervisor exit) it holds
  the control socket; a Start in that window fails once and succeeds on retry.
- The guard needs the published asteriskd config to know what to clean; if app data is gone,
  run Stop again after reinstalling or reboot.
- Readiness checks the port in the core's network namespace, not socket ownership.
- `routing-mark` and `dns-out` checks cover the profile itself, not proxy-provider content.
- TUN2SOCKS OUTPUT hooks are still appended (only TPROXY hooks are head-inserted).
