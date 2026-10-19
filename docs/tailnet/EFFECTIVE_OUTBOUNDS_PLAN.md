# Plan: Tailnet card lists the running core's effective outbounds

Goal: the Home **Tailnet** card shows every `type: tailscale` outbound of the *running* core,
including proxy-provider members and outbounds injected by override scripts. The selected
profile file is parsed only as a fallback while the core cannot report its outbounds, and the
card says so.

## Core (AndroidLibClashLite `feat/tailscale-status`)

1. `Status` gains `provider` (empty for top-level proxies) and `config`
   `{hostname, stateDir (resolved absolute), stateDirDefault, ephemeral, hasAuthKey, controlUrlHost}`.
   No auth key, no full control URL. Test: config present in every lifecycle phase.
2. `api.Statuses` iterates top-level proxies, then providers sorted by name; one adapter is
   reported once (Mihomo's implicit `default` provider wraps the top-level proxies); same name in
   two providers yields two entries sorted by `(name, provider)`. Tests for each case.
3. `Lookup(name, provider)` and `provider=` on every route; empty provider means the top-level
   outbound or the only provider member, several members return `409`. JNI functions take
   `(name, provider)`.
4. Overlay fills `Config` from the effective `TailscaleOption`.
5. Prerelease `v1.19.32-android.5-tailscale.2`, AAR SHA-256 pinned in the app.

## App (AsteriskMETA `feat/tailnet-effective-outbounds`)

1. `TailnetOutboundKey(name, provider)` identifies an outbound; actions (login, login URL,
   logout) pass the provider to JNI and to the controller.
2. `parseTailscaleCoreStatuses` returns outbounds with their state and non-secret config; a core
   without `config` (older fork build) is accepted and enriched from the profile entry of the same
   top-level name.
3. `resolveTailnetCardOutbounds(statuses, profileOutbounds)` (pure):
   * core available: core list (follows provider refreshes on the next poll), plus top-level
     profile outbounds the core does not run, shown as "not in the running config";
   * core unavailable (service stopped, API missing, query failed, first poll): profile list,
     marked as a fallback with the reason.
4. State-dir diagnostics use the core-reported resolved directory and `stateDirDefault`.
5. The dashboard polls whenever the service runs, so the card appears when a provider adds an
   outbound even if the profile file has none; it disappears when none remain.
6. VPN mode uses JNI, ROOT modes the controller; both share `resolveInteractiveBackend`.

Tests first for every step (JUnit, Go). Verification: Go tests, app unit tests,
`assembleDebug`, `lint`, review of both diffs.
