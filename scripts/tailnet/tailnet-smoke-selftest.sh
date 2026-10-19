#!/usr/bin/env bash
# Copyright 2026, AsteriskMETA contributors
# SPDX-License-Identifier: GPL-3.0
#
# Hermetic self-test for tailnet-smoke.sh using a fake netcat; needs no network or device.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cat >"$work/nc" <<'FAKE'
#!/usr/bin/env bash
# Fake OpenBSD nc: advertises -X; answers per FAKE_NC_SOCKS / FAKE_NC_DIRECT.
if [[ "${1:-}" == -h ]]; then echo "usage: nc [-X proxy_protocol] [-x proxy_address]"; exit 0; fi
echo "$*" >>"$FAKE_NC_LOG"
if [[ " $* " == *" -X 5 "* ]]; then printf '%s\n' "$FAKE_NC_SOCKS"; else printf '%s\n' "$FAKE_NC_DIRECT"; fi
FAKE
chmod +x "$work/nc"
export TAILNET_NC="$work/nc" FAKE_NC_LOG="$work/calls"

failures=0
expect() {
  local name="$1" want="$2"; shift 2
  : >"$FAKE_NC_LOG"
  "$here/tailnet-smoke.sh" "$@" >"$work/out" 2>&1
  local got=$?
  if [[ "$got" == "$want" ]]; then echo "ok   $name"; else echo "FAIL $name (exit $got, want $want)"; cat "$work/out"; failures=$((failures + 1)); fi
}

FAKE_NC_SOCKS='SSH-2.0-OpenSSH_fixture' FAKE_NC_DIRECT='SSH-2.0-OpenSSH_fixture' expect "both pass" 0 192.0.2.10
FAKE_NC_SOCKS='SSH-2.0-OpenSSH_fixture' FAKE_NC_DIRECT='' expect "direct fails" 1 192.0.2.10
FAKE_NC_SOCKS='' FAKE_NC_DIRECT='' expect "socks only fails" 1 --mode socks 192.0.2.10
FAKE_NC_SOCKS='SSH-2.0-x' FAKE_NC_DIRECT='' expect "socks only passes" 0 -m socks -p 23456 192.0.2.10 2222
grep -q -- "-X 5 -x 127.0.0.1:23456 192.0.2.10 2222" "$FAKE_NC_LOG" && echo "ok   custom ports forwarded" || { echo "FAIL custom ports forwarded"; failures=$((failures + 1)); }
FAKE_NC_SOCKS='' FAKE_NC_DIRECT='HTTP/1.1 200' expect "custom banner" 0 -m direct -b '^HTTP/1\.1' 192.0.2.10 80
TAILNET_SOCKS_PORT=34567 FAKE_NC_SOCKS='SSH-2.0-x' FAKE_NC_DIRECT='' expect "env port" 0 -m socks 192.0.2.10
grep -q -- "127.0.0.1:34567" "$FAKE_NC_LOG" && echo "ok   env port forwarded" || { echo "FAIL env port forwarded"; failures=$((failures + 1)); }
FAKE_NC_SOCKS="error tskey-fixture-000 https://control.example.net/a/fixture" FAKE_NC_DIRECT='' expect "leaky error fails" 1 -m socks 192.0.2.10
if grep -q "tskey-fixture-000\|/a/fixture" "$work/out"; then echo "FAIL secrets redacted"; failures=$((failures + 1)); else echo "ok   secrets redacted"; fi
expect "missing target" 2
expect "bad port" 2 192.0.2.10 70000
expect "bad mode" 2 -m tcp 192.0.2.10

(( failures == 0 )) && echo "PASS: tailnet-smoke.sh self-test" || echo "FAIL: $failures self-test case(s)"
exit $(( failures == 0 ? 0 : 1 ))
