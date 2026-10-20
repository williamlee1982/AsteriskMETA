# Tailnet card: effective-config fallback (design)

**Approved approach:** option 2 — card fallback list comes from profile **after** the selected override script (same path as runtime / MagicDNS), not the raw subscription file alone.

## Problem
Dashboard `tailscaleOutbounds` is parsed from the raw profile bytes. Override-script-added `type: tailscale` proxies (e.g. `TS`) never appear in that list. When `/asterisk/tailscale` is unavailable (`CoreApiMissing`, `QueryFailed`, etc.), `resolveTailnetCardOutbounds` falls back to that empty list and the card is hidden — even though the node list already shows `TS`.

## Goal
If the effective config (raw profile + bound override script) declares any tailscale outbound, the home Tailnet card stays visible. When live status cannot be queried, show those outbounds and the concrete `TailscaleStatusUnavailableReason` (existing strings).

## Non-goals
- Do not query stock `/proxies` as a third source in this change.
- Do not change login/logout APIs or security redaction rules.
- Do not hardcode device-specific hostnames or IPs.

## Behavior
1. Recompute dashboard outbound list from YAML after `applyMihomoProfileScriptOverride` for the selected profile + scripts (reuse existing helpers; avoid duplicating QuickJS wiring).
2. Keep `resolveTailnetCardOutbounds` contract: running core wins when `Available`; otherwise profile/effective list is marked fallback with reason.
3. Optionally extend “keep lastReported on QueryFailed” to other transient reasons only if tests stay clear — not required if (1) fixes the empty-list case.
4. Script apply failure: fall back to raw profile parse (card may stay empty); do not crash the dashboard.

## Success
- Unit tests: script-only TS appears in the list used for the card when status is Unavailable; reason still set.
- Existing TailnetCardOutbounds / presentation tests still pass.
- PR opened on williamlee1982/AsteriskMETA only (never push Asterisk4Magisk).
