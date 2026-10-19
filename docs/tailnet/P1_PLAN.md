# Tailnet P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show real, typed Tailscale state for Mihomo `type: tailscale` outbounds, with login, logout, secret redaction and MagicDNS resolution, on any rooted or non-rooted Android device.

**Architecture:** The core (AndroidLibClashLite fork) exposes per-outbound tsnet state through JNI and the authenticated Clash controller. The app polls it through the existing backend selection (JNI in VPN mode, controller in ROOT/API mode), renders it on the Home Tailnet card and rewrites the runtime profile so MagicDNS names resolve through the tailnet.

**Tech Stack:** Go 1.27 + gomobile (core), tailscale v1.104.1 tsnet, Mihomo stable v1.19.32, Kotlin/Compose, JUnit 4, kotlinx.serialization, JDK `HttpServer` for controller tests.

## Global Constraints

- Generic app: no device-specific hostnames, IPs, tailnet names, ports or SSH targets; values come from profile/settings; fixtures use `example.test` / `100.101.102.103`.
- Never persist or log login URLs; redact auth URLs, `tskey-*`, node keys, cookies, bearer/basic credentials everywhere.
- No log scraping for state.
- VPN and ROOT stay separate: Tailnet code imports nothing from `engine.root`.
- Mihomo is pinned to the latest **stable** release tag (currently `v1.19.32`), never Alpha.
- The app consumes the core from a tagged release, pinned by SHA-256; no AAR is committed.
- `--max-workers=4`; verify with `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`.

---

### Task 1: Core state API (AndroidLibClashLite `feat/tailscale-status`)

**Files:**
- Create: `native/tailscale/{status,redact,lifecycle,controller,routeauth}.go`, `native/tailscale/tailscale_test.go`
- Create: `native/tailscale/api/{api,lookup}.go`, `native/tailscale/api/api_test.go`
- Create: `overlay/mihomo/adapter/outbound/tailscale_status.go`, `libclash_tailscale.go`
- Modify: `scripts/prepare_sources.py` (lifecycle hooks, redacted tsnet loggers, `MarkControllerAuth`)
- Test: `scripts/test_pinned_core.py`

**Interfaces:**
- Produces: `GET /asterisk/tailscale` → `{"outbounds":[{name,state,loginUrlAvailable,tailnetIps,hostname,dnsName,magicDnsSuffix,health,error}]}`; `POST …/login?name=` (202); `GET …/login-url?name=` (403 without controller secret); `POST …/logout?name=` (204); JNI `queryTailscaleStatus()`, `startTailscaleLogin(name)`, `queryTailscaleLoginURL(name)`, `logoutTailscale(name)`.

- [x] **Step 1:** Write failing tests in `tailscale_test.go` (state mapping, redaction, lifecycle, "Status never starts tsnet", login flow, async login failure reported as redacted `Error`, stale login results ignored).
- [x] **Step 2:** Run `go test -race ./native/...`; expect FAIL.
- [x] **Step 3:** Implement the package, API routes, overlay and patches.
- [x] **Step 4:** Run `go test -race -count=3 ./native/...`, `go vet`, `hostcheck.sh` (android/arm64 build); expect PASS / `HOSTBUILD-OK`.
- [x] **Step 5:** Pin Mihomo `v1.19.32`; `python3 -m unittest scripts/test_pinned_core.py` fails on the Alpha pseudo-version and passes on the stable tag.
- [x] **Step 6:** Commit; build with `scripts/build_shared.py`; publish the prerelease `v1.19.32-android.5-tailscale.1` with SHA-256 in the notes.

### Task 2: tailscale v1.104.1 module (forks `tailscale`, `tailscale-wireguard-go`)

**Files:** fork branches `feat/upstream-v1.104.1`, `feat/upstream-417aef36`; `replace` directives in the AndroidLibClashLite `go.mod`.

- [x] **Step 1:** Rebuild the MetaCubeX layout on v1.104.1; `go test ./...` in the fork (pre-existing baseline failures documented).
- [x] **Step 2:** Mihomo `adapter/outbound` and `config` build and test against the forks.
- [x] **Step 3:** Confirm `go version -m libgojni.so` shows the replaced modules.

### Task 3: App consumes the pinned core

**Files:** Modify `buildSrc/src/main/kotlin/BuildConfig.kt`, `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`.

- [x] **Step 1:** Bump the tag only; run `:app:verifyLibclashAar`; expect FAIL "SHA-256 mismatch".
- [x] **Step 2:** Set `ANDROID_LIB_CLASH_LITE_SHA256`; rerun; expect PASS.
- [x] **Step 3:** Commit.

### Task 4: Typed state in the app

**Files:** Create `engine/mihomo/tailscale/TailscaleCoreApi.kt`; modify `TailscaleStatus.kt`, `MihomoTailscaleOutbound.kt`, `MihomoBridge.kt`, `MihomoControlClient.kt`, `MihomoRuntimeRepository.kt`, `features/mihomo/tailscale/TailnetStatusPresentation.kt`. Tests: `TailscaleStatusTest`, `MihomoControlClientTailscaleTest`, `MihomoTailscaleOutboundTest`, `TailnetStatusPresentationTest`.

**Interfaces:**
- Produces: `TailscaleStatusQuery`, `TailscaleBackendState` (`Idle|Stopped|Starting|NeedsLogin|Running|Error`), `validatedTailscaleLoginUri(url, allowedHosts)`, `MihomoRuntimeRepository.tailscaleStatuses/startTailscaleLogin/tailscaleLoginUrl/logoutTailscale`.

- [x] Steps: failing tests (JSON mapping, URL validation, 404 → `CoreApiMissing`, 403 → secret required, redacted errors) → implement → `:app:testDebugUnitTest` PASS → commit.

### Task 5: Login/logout UI

**Files:** Create `TailnetLoginFlow.kt`, `TailnetStatusSection.kt`; modify `TailnetStatusCard.kt`, `DashboardPage.kt`, strings (en, zh-rCN). Test: `TailnetLoginFlowTest`, `TailnetStatusPresentationTest`.

- [x] Steps: failing tests (wait for URL then validate, reject foreign hosts, timeouts, machine auth, error mapping, `toString` hides the URL, polling interval, `Checking` placeholder) → implement → PASS → commit.

### Task 6: Redaction at every sink

**Files:** Modify `TailnetRedaction.kt`, `features/logs/{CoreLogRepository,AndroidLogcatRepository,AndroidMihomoLogRepository,AndroidAppLogger,LogDisplayReducer}.kt`, `ui/feedback/AndroidToastTipNotifier.kt`, `features/proxy/ProxyErrorDialog.kt`. Test: `CoreLogRedactionTest`, `TailnetSourceBoundaryTest` (Tailnet code must not log or persist).

- [x] Steps: failing tests → implement → PASS → commit.

### Task 7: MagicDNS in VPN and ROOT

**Files:** Create `engine/mihomo/tailscale/TailnetDnsOverrides.kt`; modify `engine/mihomo/MihomoProfile.kt`. Test: `TailnetDnsOverridesTest`.

- [x] Steps: failing tests (defaults, configurable key stripped, user entries win, disabled/inapplicable, VPN + ROOT runtime profiles) → implement → PASS → commit.

### Task 8: Verification and review

- [x] `go test -race -count=3 ./native/...`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` fresh.
- [x] Review both branch diffs (security/redaction, regressions, generic-device assumptions) and fix findings.
- [ ] On-device checks in `REGRESSION_CHECKLIST.md` (needs a phone).
