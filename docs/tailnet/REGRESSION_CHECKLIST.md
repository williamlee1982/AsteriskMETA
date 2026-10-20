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

- [ ] A1 First start: the card shows **Not started** or **Login required**. Tap **Log in**: the
      browser opens the control server's login page; complete it. The card turns **Connected** with
      the Tailnet IPs and MagicDNS name within a few seconds.
- [ ] A1b The login URL does not appear in the app log, logcat (`adb logcat | grep '/a/'`), exported
      logs, toasts or the core log file.
- [ ] A1c ROOT mode with an empty controller secret: **Log in** reports that a secret is required;
      with a secret set it opens the browser.
- [ ] A2 Admin console shows a new device `<ts-hostname>`, distinct from any standalone Tailscale
      device on the same phone.
- [ ] A3 Stop and start the service: no new login, same device and Tailnet IP (identity persisted).
- [ ] A4 Force-stop the app and start again: same device, no duplicate registration.
- [ ] A5 Two outbounds with distinct `state-dir`s produce two devices; removing `state-dir` from both
      shows "shared" warnings on the card.
- [ ] A5b **Log out / reset** asks for confirmation; afterwards the card shows **Login required** and
      the admin console shows the node logged out. Logging in again works.
- [ ] A5c An APK built against an upstream core (no state API) shows "no Tailscale state API" and no
      buttons.
- [ ] A6 (ROOT) `ls -ld <filesDir>/clash/<state-dir>` is inside the app data directory and not
      world-readable. Do not change permissions.

- [ ] A7 A tailscale outbound only in a proxy provider (and one added by an override script): with
      the service running the card lists it with "From proxy provider: <name>" (the script one
      without), and Log in / Log out act on that outbound. Repeat in VPN and one ROOT mode.
- [ ] A8 Remove the outbound from the provider and refresh the provider: the card drops it within
      one poll; add it back: it reappears as **Not started**.
- [ ] A9 Two providers with a tailscale outbound of the same name: two rows, each logs in its own
      node; their state dirs are reported as shared if they resolve to the same directory.
- [ ] A10 Service stopped: the card shows the profile-file note and only top-level outbounds.

## B. Explicit SOCKS (all modes)

- [ ] B1 VPN mode: `bash tailnet-smoke.sh -m socks -p <socks-port> <peer-ip> <peer-port>` passes.
- [ ] B2 ROOT TPROXY: same command passes.
- [ ] B3 ROOT TUN, TUN2SOCKS, BPF2SOCKS: same command passes.
- [ ] B4 Change the local proxy port in Settings, restart, rerun with the new `-p`: passes; the old
      port is closed.
- [ ] B5 MagicDNS: `bash tailnet-smoke.sh -m socks -p <socks-port> <peer-name> <peer-port>` passes in
      VPN and ROOT modes, with fake-ip and with redir-host DNS.
- [ ] B6 `nslookup <peer-name>` (VPN mode, or ROOT with DNS hijack) returns the peer's Tailnet IP;
      non-Tailnet names still resolve normally.
- [ ] B7 `asterisk-tailnet-dns: false` in the profile: B5 fails, everything else unchanged.

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

## G. Inbound forwarding (core `tailscale.4` or newer)

- [ ] G1 With the generic profile declaration (listen ports >= `20000`) and the toggle off, the
      card shows the configured rows but the active and standby runtime configs contain no
      `inbound-forwards`; no listener is reachable.
- [ ] G2 Toggle **Enable forwarding (always online)** on while the proxy is running: the profile
      reloads without a supervised ROOT restart, the row reaches **Listening** after login, and a
      Tailnet peer can connect to the shown MagicDNS/IP address.
- [ ] G3 Toggle off again: the profile reload removes the listener and a new connection is refused;
      existing root/VPN routing and the Tailscale identity remain healthy.
- [ ] G4 Before login, each configured row shows **Waiting for login**. A profile-only row that is
      absent from the running core shows **Not running**.
- [ ] G5 Connect repeatedly and record active/total counters. A target dial failure reports the
      row error while the listener stays available for a later retry.
- [ ] G6 Verify the Tailnet ACL limits the forwarding port to the intended group/tag. Do not paste
      login URLs, auth keys, state files, or personal hostnames/IPs into the test record.
- [ ] G7 Repeat G1–G5 in VPN and one ROOT mode (TPROXY or TUN), with IPv6 enabled and disabled;
      confirm the VPN lifecycle still performs no ROOT operation.
- [ ] G8 Run against an older core (`tailscale.3`, e.g. a local `-Pasterisk.libclash.localAar`
      build): the toggle/card reports that the core does not support inbound forwards. Do not treat an absent status field as an empty list.

## F. Privacy

- [ ] F1 Shared app logs and exported diagnostics contain no `tskey-`, login URL path, cookie or
      bearer value.
- [ ] F2 Screenshots of the Tailnet card contain no secrets (hostname and IPs only).
