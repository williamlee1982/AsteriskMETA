#!/usr/bin/env bash
# Copyright 2026, AsteriskMETA contributors
# SPDX-License-Identifier: GPL-3.0
#
# Tailnet SSH-banner smoke test for AsteriskMETA with a Mihomo `type: tailscale` outbound.
# Runs on the target Android device (Termux: `pkg install netcat-openbsd`) or any host with
# OpenBSD netcat. Nothing device-specific is built in: every address and port is a parameter.
#
# Usage:
#   tailnet-smoke.sh [options] <tailnet-target> [target-port]
#
# Options (each also settable through the environment variable shown):
#   -p, --socks-port PORT   AsteriskMETA local proxy port (Settings > local proxy port, which
#                           overrides the profile's mixed-port)       [TAILNET_SOCKS_PORT, 10809]
#   -H, --socks-host HOST   Local proxy listen address                 [TAILNET_SOCKS_HOST, 127.0.0.1]
#   -m, --mode MODE         socks | direct | both                      [TAILNET_SMOKE_MODE, both]
#   -t, --timeout SECONDS   Per-check timeout                          [TAILNET_SMOKE_TIMEOUT, 8]
#   -b, --banner REGEX      Expected banner (extended regex)           [TAILNET_BANNER_REGEX, ^SSH-2\.0-]
#   -h, --help              Show this help
#
# Checks:
#   socks   explicit SOCKS5 through AsteriskMETA (works in VPN and ROOT modes)
#   direct  plain TCP to the target; only proves ROOT transparent routing when the target's /32
#           is listed under AsteriskMETA "Private addresses" and no other VPN (for example a
#           standalone Tailscale app) is active
#
# Exit status: 0 all selected checks passed, 1 a check failed, 2 usage or environment error.
# The target may be a Tailnet IP (100.x.y.z / fd7a:115c:a1e0::/48) or a MagicDNS name; names
# are resolved by the proxy for the socks check and by the device resolver for the direct check.
set -u

usage() { sed -n '5,31p' "$0" | sed 's/^# \{0,1\}//'; }

SOCKS_HOST="${TAILNET_SOCKS_HOST:-127.0.0.1}"
SOCKS_PORT="${TAILNET_SOCKS_PORT:-10809}"
MODE="${TAILNET_SMOKE_MODE:-both}"
TIMEOUT="${TAILNET_SMOKE_TIMEOUT:-8}"
BANNER_REGEX="${TAILNET_BANNER_REGEX:-^SSH-2\.0-}"
NC="${TAILNET_NC:-nc}"
TARGET=""
TARGET_PORT="${TAILNET_TARGET_PORT:-22}"

positional=()
while (( $# > 0 )); do
  case "$1" in
    -p|--socks-port) SOCKS_PORT="${2:-}"; shift 2 ;;
    -H|--socks-host) SOCKS_HOST="${2:-}"; shift 2 ;;
    -m|--mode) MODE="${2:-}"; shift 2 ;;
    -t|--timeout) TIMEOUT="${2:-}"; shift 2 ;;
    -b|--banner) BANNER_REGEX="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; positional+=("$@"); break ;;
    -*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) positional+=("$1"); shift ;;
  esac
done
TARGET="${positional[0]:-${TAILNET_TARGET:-}}"
[[ ${#positional[@]} -ge 2 ]] && TARGET_PORT="${positional[1]}"

is_port() { [[ "$1" =~ ^[0-9]+$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }

if [[ -z "$TARGET" ]]; then
  echo "Missing <tailnet-target>." >&2; usage >&2; exit 2
fi
if ! is_port "$TARGET_PORT" || ! is_port "$SOCKS_PORT"; then
  echo "Ports must be integers in 1..65535 (target=$TARGET_PORT socks=$SOCKS_PORT)." >&2; exit 2
fi
if ! [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || (( TIMEOUT < 1 )); then
  echo "Timeout must be a positive integer." >&2; exit 2
fi
case "$MODE" in socks|direct|both) ;; *) echo "Mode must be socks, direct or both." >&2; exit 2 ;; esac
if ! command -v "$NC" >/dev/null 2>&1; then
  echo "Missing $NC. In Termux: pkg install netcat-openbsd" >&2; exit 2
fi
if [[ "$MODE" != direct ]] && ! "$NC" -h 2>&1 | grep -q -- '-X'; then
  echo "$NC lacks SOCKS support (-X/-x); install OpenBSD netcat (Termux: netcat-openbsd)." >&2; exit 2
fi

# Never echo credentials that might appear in error output.
redact() {
  sed -E \
    -e 's#https?://[^[:space:]/]+/a/[^[:space:]]+#<redacted-login-url>#g' \
    -e 's#tskey-[A-Za-z0-9_-]+#tskey-<redacted>#g' \
    -e 's#([Bb]earer[[:space:]]+)[^[:space:]]+#\1<redacted>#g'
}

check_banner() {
  local description="$1"; shift
  local output
  output=$("$NC" -w "$TIMEOUT" "$@" </dev/null 2>&1 | head -c 512 | redact || true)
  printf '\n[%s]\n%s\n' "$description" "${output:-<no output>}"
  if grep -Eq "$BANNER_REGEX" <<<"$output"; then
    echo "PASS: banner observed ($description)"
    return 0
  fi
  echo "FAIL: no matching banner ($description); check routing, Tailnet login state, the target service and AsteriskMETA logs" >&2
  return 1
}

echo "target=$TARGET:$TARGET_PORT socks=$SOCKS_HOST:$SOCKS_PORT mode=$MODE timeout=${TIMEOUT}s"
status=0
if [[ "$MODE" != direct ]]; then
  check_banner "SOCKS5 via $SOCKS_HOST:$SOCKS_PORT" -X 5 -x "$SOCKS_HOST:$SOCKS_PORT" "$TARGET" "$TARGET_PORT" || status=1
fi
if [[ "$MODE" != socks ]]; then
  check_banner "Direct TCP (ROOT transparent routing)" "$TARGET" "$TARGET_PORT" || status=1
fi
if (( status == 0 )); then
  echo "PASS: all selected checks reached $TARGET:$TARGET_PORT"
fi
exit "$status"
