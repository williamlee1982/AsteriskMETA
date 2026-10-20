# Tailnet Inbound Forwarding: App Implementation Plan (AsteriskMETA)

> **For agentic workers:** REQUIRED SUB-SKILLS: superpowers:test-driven-development for every
> task. superpowers:verification-before-completion before claiming done.
> superpowers:requesting-code-review before the PR. Steps use `- [ ]`.

**Goal:** Pass `inbound-forwards` through to the active core only when the user enabled the toggle, show forwards and their
live state on the Tailnet card, and (Phase B) edit them in the app.

**Branch:** `feat/tailscale-inbound` (worktree `/workspace/AsteriskMETA-tailscale-inbound`),
currently based on `chore/core-tailscale-3` (PR #5, unmerged). Rebase onto `main` after PR #5
merges. The PR targets `williamlee1982/AsteriskMETA` only, never upstream.

**Order:** the core plan (`INBOUND_FORWARD_CORE_PLAN.md`) Tasks C0-C5 come first. App Task A5
bumps the pin to `tailscale.4`. Tasks A1-A4 can be developed before against fixtures.

**Constraints:** generic app (fixtures `example.test`, `100.101.102.103`, ports ≥ 20000). Tailnet
code imports nothing from `engine.root`. No login URLs/keys persisted or logged.
Gradle `--max-workers=4`. Verify with `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`.

---

### Task A1: Model and profile parsing

**Files:** `engine/mihomo/tailscale/MihomoTailscaleOutbound.kt`, new `TailnetInboundForward.kt`.
**Tests:** `MihomoTailscaleOutboundTest.kt`, new `TailnetInboundForwardTest.kt`.

- [ ] RED:
  - `parsesInboundForwards`: listen/target/name/network. Missing name → listen as label.
  - `invalidEntriesAreReportedNotDropped`: a bad entry yields an `Invalid(reason)` item (the
    card shows it, and the core is the authority that rejects it).
  - `overrideScriptForwardsAreVisible`: `effectiveMihomoTailscaleOutbounds` with a QuickJS
    script that adds `inbound-forwards` returns them.
  - `noForwardsKeepsEmptyList` (regression: existing fixtures unchanged).
- [ ] GREEN: `data class TailnetInboundForward(name, network, listen: Int, target: String)`.

### Task A2: Standby core never gets forwards

**Files:** new `putTailnetInboundOverrides` in `engine/mihomo/tailscale/TailnetInboundOverrides.kt`.
It's called from `putAsteriskRuntimeOverrides` (`MihomoProfile.kt`) next to `putTailnetDnsOverrides`.

- [ ] RED (`TailnetInboundOverridesTest.kt`):
  - `exposePortsFalseStripsForwardsFromEveryTailscaleProxy`.
  - `exposePortsTrueKeepsForwardsUnchanged`.
  - `nonTailscaleProxiesUntouched`.
  - `disableOverridesProfileIsUntouched`: documents that with overrides disabled the raw
    profile reaches the core (`resolveMihomoRuntimeProfileBytes`). Check here, using
    systematic-debugging, whether standby can run with `disableOverrides`. If it can, strip
    in that path too.
- [ ] GREEN. Add a test in `TailnetSourceBoundaryTest` that the new file has no `engine.root`
  import.

### Task A2b: Toggle "启用转发（一直在线）" (D8)

**Files:** `AppState` (new persisted `tailnetInboundForwardsEnabled`, default false), the
`MihomoProfile.kt` call site, `TailnetStatusSection.kt` and presentation, strings.

- [ ] RED:
  - `toggleOffStripsForwards` / `toggleOnKeepsForwards` / `standbyStripsEvenWhenOn`.
  - `defaultIsOff` (settings default and serialization round trip).
  - `switchEnabledOnlyWhenEffectiveProfileHasForwards` (presentation: enabled/disabled + hint).
  - Toggling requests a config reload (repository/view-model test with a fake reloader, or
    the existing setting-change → reload path).
- [ ] GREEN.

### Task A3: Core status parsing

**Files:** `engine/mihomo/tailscale/TailscaleStatus.kt`, `TailscaleCoreApi.kt`.
**Tests:** `TailscaleStatusTest.kt`, `MihomoControlClientTailscaleTest.kt`.

- [ ] RED:
  - `parsesInboundForwardStates` (Listening/Waiting/Error, counters, error text redacted
    via `TailnetRedaction`).
  - `missingFieldMeansUnsupportedCore` (null, not empty list).
  - `unknownStateMapsToError`.
- [ ] GREEN.

### Task A4: Tailnet card section

**Files:** `features/mihomo/tailscale/TailnetStatusPresentation.kt`,
`TailnetStatusSection.kt`, `TailnetCardOutbounds.kt`, strings (zh + en).
**Tests:** `TailnetStatusPresentationTest.kt`, `TailnetCardOutboundsTest.kt`.

- [ ] RED (presentation is pure, test it there):
  - `forwardRowShowsReachableAddress`: Running + dnsName `phone.example.test.` + listen
    20022 → `phone.example.test:20022`. Without a dnsName, use the first IPv4 tailnet IP.
  - `waitingForwardWhenNeedsLogin`: shows "等待登录".
  - `unsupportedCoreNotice`: forwards in profile and field missing from core → notice.
  - `profileOnlyForwardsShownAsNotRunning` (the NotInRunningConfig outbound).
  - `aclHintShownOnce`: an ACL reminder line appears when any forward exists.
- [ ] GREEN. The Compose UI only renders the presentation model and adds a copy-address
  action.

### Task A5: Pin core `tailscale.4`, docs, verification

- [ ] `buildSrc/.../BuildConfig.kt` version + SHA-256 (verified by download).
- [ ] `docs/tailnet/README.md`: "入站转发" section with a YAML + override-script example
  (generic values), an ACL example, and adb/sshd notes. Add device-test rows to
  `REGRESSION_CHECKLIST.md`.
- [ ] Full unit tests, assemble, lint, CI green, APK contains `InboundController` symbol.
- [ ] Device verification on a rooted phone (ROOT TPROXY + VPN mode): forward to a local
  high-port listener, connect from another tailnet device, reload the profile, confirm no
  duplicate node and that the card counters move.
- [ ] Requesting-code-review, open the PR. William merges.

### Phase B (decision D1, separate PR): in-app editor

- [ ] Settings model `tailnetInboundForwards: Map<TailnetOutboundKey, List<TailnetInboundForward>>`
  in app state (persisted, contains no secrets).
- [ ] `putTailnetInboundOverrides` merges app entries into the matching top-level tailscale
  proxy. Profile/script entries win on a `listen` conflict, and the conflict is surfaced.
  Tests cover merge, conflict, outbound missing, and the standby strip still applied last.
- [ ] Editor dialog on the card (listen, target with loopback default `127.0.0.1:`, name),
  with input validation mirroring the core rules. Presentation/validation tests.
