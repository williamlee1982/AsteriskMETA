# Tailnet regression checklist

Self-contained manual checks for Mihomo `type: tailscale` in AsteriskMETA. Works on any Android
device; replace every `<placeholder>`. Record the APK version, device model, Android version, run
mode, IPv6 setting and result for each row. Never paste login URLs, auth keys, cookies or state
files into notes, issues or screenshots.

## Parameters

| Placeholder | Meaning | Where to find it |
| --- | --- | --- |
| `<socks-port>` | AsteriskMETA local proxy port (overrides the profile `mixed-port`) | Settings, default `10809` |
| `<peer-ip>` | Tailnet IP of a reachable peer running a TCP service | Tailnet admin console |
| `<peer-port>` | Service port on the peer (SSH `22` by default) | peer |
| `<peer-name>` | MagicDNS name of the peer (optional) | admin console |
| `<ts-hostname>` | `hostname` of the tailscale outbound in the profile | profile |
| `<state-dir>` | `state-dir` of the outbound, relative to the Mihomo home | profile |

## Preconditions

- [ ] Profile contains one tailscale outbound per identity with a unique explicit `state-dir`
      inside the Mihomo home (the Home **Tailnet** card shows no state-dir warning).
- [ ] Any standalone Tailscale app and other VPNs are **off** for routing checks; their own
      identity and data are left untouched (do not copy or reuse their state).
- [ ] Termux (or adb shell) has OpenBSD netcat: `pkg install netcat-openbsd`.
- [ ] Copy `scripts/tailnet/tailnet-smoke.sh` to the device.

## A. Login and identity

- [ ] A1 First start: the new identity requests login; complete it in the browser. The login URL is
      not saved anywhere outside the core's log ring and is not shared.
- [ ] A2 Admin console shows a new device `<ts-hostname>`, distinct from any standalone Tailscale
      device on the same phone.
- [ ] A3 Stop and start the service: no new login, same device and Tailnet IP (identity persisted).
- [ ] A4 Force-stop the app and start again: same device, no duplicate registration.
- [ ] A5 Two outbounds with distinct `state-dir`s produce two devices; removing `state-dir` from both
      shows "shared" warnings on the card.
- [ ] A6 (ROOT) `ls -ld <filesDir>/clash/<state-dir>` is inside the app data directory and not
      world-readable. Do not change permissions.

## B. Explicit SOCKS (all modes)

- [ ] B1 VPN mode: `bash tailnet-smoke.sh -m socks -p <socks-port> <peer-ip> <peer-port>` passes.
- [ ] B2 ROOT TPROXY: same command passes.
- [ ] B3 ROOT TUN, TUN2SOCKS, BPF2SOCKS: same command passes.
- [ ] B4 Change the local proxy port in Settings, restart, rerun with the new `-p`: passes; the old
      port is closed.
- [ ] B5 MagicDNS (optional): `bash tailnet-smoke.sh -m socks -p <socks-port> <peer-name> <peer-port>`.

## C. ROOT transparent routing (TPROXY, TUN2SOCKS, BPF2SOCKS)

- [ ] C1 Without a private-address override: `bash tailnet-smoke.sh -m direct <peer-ip> <peer-port>`
      fails (CGNAT is bypassed by default) and the card shows the `/32` hint.
- [ ] C2 Add `<peer-ip>/32` under **Private addresses** and restart: the direct check passes and the
      card counts one host override.
- [ ] C3 Another Tailnet peer not listed stays bypassed (direct check fails).
- [ ] C4 Carrier CGNAT / LAN traffic is unaffected: normal browsing on cellular and Wi-Fi works.
- [ ] C5 Adding `100.64.0.0/10` shows the broad-override warning; remove it afterwards.
- [ ] C6 Repeat C2 with IPv6 enabled and disabled.
- [ ] C7 No Android VPN key icon / `VpnService` while in ROOT modes.

## D. Mode switching and lifecycle

- [ ] D1 ROOT -> VPN -> ROOT with the same profile: record whether the identity stays the same and
      whether VPN mode can read state created by ROOT (known P1 risk: root-owned files).
- [ ] D2 VPN lifecycle performs no ROOT operation (no root prompt, no asteriskd control request).
- [ ] D3 Wi-Fi <-> cellular switch while connected: SOCKS check recovers within one minute.
- [ ] D4 Airplane mode on/off: recovers without re-login.
- [ ] D5 Reboot with ROOT boot script enabled: recovers without re-login.

## E. Non-Tailnet regressions

- [ ] E1 Normal subscription nodes still work in every mode.
- [ ] E2 Rule/Global/Direct switches still apply hot (no restart).
- [ ] E3 Profiles without tailscale outbounds show no Tailnet card.

## F. Privacy

- [ ] F1 Shared app logs and exported diagnostics contain no `tskey-`, login URL path, cookie or
      bearer value.
- [ ] F2 Screenshots of the Tailnet card contain no secrets (hostname and IPs only).
