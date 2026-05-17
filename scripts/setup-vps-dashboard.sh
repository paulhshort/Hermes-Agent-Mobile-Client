#!/usr/bin/env bash
set -euo pipefail

# Hardened Hermes dashboard bootstrap for Tailscale-only use.
# This script intentionally avoids public exposure:
# 1) validates `hermes`, `systemctl`, `curl`, and `tailscale` are available
# 2) detects the host's Tailscale IPv4 address unless HERMES_DASHBOARD_HOST is set
# 3) writes a systemd service bound to that Tailscale IP
# 4) optionally allows UFW from Tailscale CGNAT only (100.64.0.0/10)
# 5) prints a Tailscale-only mobile endpoint

SERVICE_NAME="hermes-dashboard.service"
SERVICE_PATH="/etc/systemd/system/${SERVICE_NAME}"
DASHBOARD_PORT="${HERMES_DASHBOARD_PORT:-9119}"
RUN_USER="${HERMES_RUN_USER:-$USER}"
RUN_HOME="$(eval echo "~${RUN_USER}")"
WORKDIR="${HERMES_WORKDIR:-$RUN_HOME}"
TAILSCALE_CIDR="100.64.0.0/10"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 1
  fi
}

need_cmd systemctl
need_cmd curl
need_cmd tailscale

if ! command -v hermes >/dev/null 2>&1; then
  echo "hermes CLI not found in PATH for user '$USER'." >&2
  echo "Install/configure Hermes first, then re-run this script." >&2
  exit 1
fi

is_tailscale_ipv4() {
  local ip="$1" a b c d extra
  IFS=. read -r a b c d extra <<<"${ip}"
  [[ -z "${extra:-}" ]] || return 1
  for octet in "$a" "$b" "$c" "$d"; do
    [[ "${octet}" =~ ^[0-9]+$ ]] || return 1
    (( octet >= 0 && octet <= 255 )) || return 1
  done
  (( a == 100 && b >= 64 && b <= 127 ))
}

DASHBOARD_HOST="${HERMES_DASHBOARD_HOST:-$(tailscale ip -4 | head -n 1)}"
if [[ -z "${DASHBOARD_HOST}" ]]; then
  echo "Could not detect a Tailscale IPv4 address. Is Tailscale up?" >&2
  echo "Override with HERMES_DASHBOARD_HOST=<100.x.y.z> if needed." >&2
  exit 1
fi

if ! is_tailscale_ipv4 "${DASHBOARD_HOST}"; then
  echo "Refusing to bind to non-Tailscale address: ${DASHBOARD_HOST}" >&2
  echo "Use a Tailscale IPv4 address in 100.64.0.0/10." >&2
  exit 1
fi

echo "[1/5] Writing systemd service bound to Tailscale IP: ${DASHBOARD_HOST}"
sudo tee "${SERVICE_PATH}" >/dev/null <<EOF
[Unit]
Description=Hermes Dashboard (Tailscale-only)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${WORKDIR}
Environment=HOME=${RUN_HOME}
Environment=HERMES_DASHBOARD_TUI=1
ExecStart=$(command -v hermes) dashboard --host ${DASHBOARD_HOST} --port ${DASHBOARD_PORT} --no-open --insecure --tui
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

echo "[2/5] Reloading systemd + enabling service"
sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}" >/dev/null

echo "[3/5] Restarting service"
sudo systemctl restart "${SERVICE_NAME}"

echo "[4/5] Waiting for Tailscale-bound dashboard health"
for i in $(seq 1 20); do
  if curl -fsS "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/status" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/status" >/dev/null 2>&1; then
  echo "Dashboard did not become healthy on ${DASHBOARD_HOST}:${DASHBOARD_PORT}" >&2
  echo "Check: sudo journalctl -u ${SERVICE_NAME} -n 100 --no-pager" >&2
  exit 1
fi

echo "[5/5] Restricting UFW, if present, to Tailscale CIDR only"
if command -v ufw >/dev/null 2>&1; then
  if sudo ufw status numbered | grep -E "ALLOW.*${DASHBOARD_PORT}/tcp" | grep -vq "${TAILSCALE_CIDR}"; then
    echo "WARNING: Existing broad UFW allow rule(s) for TCP ${DASHBOARD_PORT} may still exist." >&2
    echo "Review with: sudo ufw status numbered" >&2
  fi
  sudo ufw allow from "${TAILSCALE_CIDR}" to any port "${DASHBOARD_PORT}" proto tcp >/dev/null 2>&1 || true
fi

MAGIC_DNS="$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data.get("Self",{}).get("DNSName","").rstrip("."))' 2>/dev/null || true)"

MOBILE_URL="http://${DASHBOARD_HOST}:${DASHBOARD_PORT}"
if [[ -n "${MAGIC_DNS}" ]]; then
  MOBILE_URL="http://${MAGIC_DNS}:${DASHBOARD_PORT}"
fi

echo
echo "Dashboard is running with Tailscale-only binding."
echo "Service:      ${SERVICE_NAME}"
echo "Local check:  http://${DASHBOARD_HOST}:${DASHBOARD_PORT}/api/status"
echo "Mobile URL:   ${MOBILE_URL}"
echo
echo "Do NOT open public/cloud firewall access to TCP ${DASHBOARD_PORT}."
echo "Use Tailscale ACLs so only approved phone/user/device identities can reach this port."
