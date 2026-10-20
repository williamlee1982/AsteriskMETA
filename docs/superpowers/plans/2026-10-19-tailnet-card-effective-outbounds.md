# Tailnet card effective-config fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Home Tailnet card uses profile+override-script effective outbounds as fallback so script-added TS stays visible when status API fails.

**Architecture:** Extract or reuse the same “load YAML → applyMihomoProfileScriptOverride → parseMihomoTailscaleOutbounds / mihomoTailscaleOutbounds” path the runtime already uses. Dashboard `produceState` for `tailscaleOutbounds` switches to that effective list. Presentation already shows `profileFallback` reasons.

**Tech Stack:** Kotlin, Compose, QuickJS override scripts, JUnit.

## Global Constraints
- Fork only: williamlee1982/AsteriskMETA; never push Asterisk4Magisk.
- Generic app: no OnePlus / personal IP hardcoding.
- TDD: failing test first.
- Build env: `source /workspace/asterisk-task/swe-env.sh` then `bash ./gradlew $GRADLE_FLAGS`.
- Do not print or commit Tailscale auth URLs, keys, cookies, bearer tokens.

---

## Task 1: Failing tests for effective-config fallback

**Files:**
- Modify: `app/src/test/kotlin/features/mihomo/tailscale/TailnetCardOutboundsTest.kt` (and/or a new helper test next to profile script override tests)
- Create if needed: unit test that builds a raw YAML without tailscale + a script that adds `TS`, then asserts parse of effective config yields `TS`.

- [x] Write failing test(s): Unavailable status + effective list containing script-only TS → card outbounds non-empty + profileFallback set.
- [x] Write failing test for the helper that loads effective tailscale outbounds from raw yaml + script (if extracted).
- [x] Run tests; confirm RED.
- [x] Commit.

## Task 2: Implement effective outbound loading for the dashboard

**Files:**
- Modify: `app/src/main/kotlin/features/mihomo/DashboardPage.kt` (`tailscaleOutbounds` produceState)
- Possibly extract helper under `engine/mihomo/` or `features/mihomo/tailscale/` for testability
- Reuse: `applyMihomoProfileScriptOverride`, `parseMihomoTailscaleOutbounds` / map `mihomoTailscaleOutbounds()`

- [x] Implement minimal code to pass tests (apply script before parse; on script error fall back to raw).
- [x] Wire `selectedProfile`, `appState.mihomoOverrideScripts`, and content hash / script id into produceState keys so script edits refresh the list.
- [x] Run unit tests GREEN.
- [x] Commit.

## Task 3: Verify presentation still surfaces reasons

**Files:**
- Check: `TailnetStatusCard.kt`, `TailnetStatusPresentationTest.kt`, strings for CoreApiMissing / QueryFailed / ProxyNotRunning

- [x] Confirm Unavailable + non-empty effective list shows card + reason banner (existing UI).
- [x] Add/adjust presentation test only if a gap appears.
- [x] Commit.

## Task 4: Docs + PR

- [x] Short note in `docs/tailnet/README.md` or REGRESSION_CHECKLIST: card uses effective config fallback.
- [x] Open PR to `main` on williamlee1982/AsteriskMETA.
- [x] Write `/workspace/asterisk-task/REPORT-tailnet-card-effective.md` with summary, test commands, PR URL, and which superpowers skills were used.
