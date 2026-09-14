#!/bin/bash
# Vultr Startup Script (runs once as root on first boot) - prepares an Ubuntu 22.04/24.04 or Debian 12 server
# for deploy/demo before anything is copied to it:
#   - Docker Engine + compose plugin
#   - a non-root deploy user in the docker group, reachable with the same SSH key as root
#   - UFW allowing only SSH, HTTP and HTTPS
#   - a 2 GB swap file (headroom for the JVM on a 4 GB instance)
#   - unattended security updates, and SSH password login disabled once a key is confirmed present
#   - ~/svalinn-demo waiting for `scp -r deploy/demo/* svalinn@<ip>:~/svalinn-demo/`
#
# Progress: /var/log/svalinn-setup.log      Finished when: /var/lib/svalinn-setup.done exists
set -euo pipefail
exec > >(tee -a /var/log/svalinn-setup.log) 2>&1

DEPLOY_USER="svalinn"
SWAP_SIZE="2G"

log() { echo "[$(date '+%F %T')] $*"; }

log "starting setup"
export DEBIAN_FRONTEND=noninteractive

# --- base packages ---------------------------------------------------------------------------------------------
apt-get update -y
apt-get upgrade -y
apt-get install -y ca-certificates curl gnupg ufw unattended-upgrades openssl

# --- Docker Engine + compose plugin (official repository) ------------------------------------------------------
if ! command -v docker > /dev/null 2>&1; then
  . /etc/os-release
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
log "docker $(docker --version)"

# Keep container logs from filling the disk.
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
systemctl restart docker

# --- deploy user -------------------------------------------------------------------------------------------------
if ! id "$DEPLOY_USER" > /dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"
if [[ -s /root/.ssh/authorized_keys ]]; then
  install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
  install -m 600 -o "$DEPLOY_USER" -g "$DEPLOY_USER" /root/.ssh/authorized_keys "/home/${DEPLOY_USER}/.ssh/authorized_keys"
fi
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/svalinn-demo"

# --- swap --------------------------------------------------------------------------------------------------------
if ! swapon --show | grep -q /swapfile; then
  fallocate -l "$SWAP_SIZE" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf

# --- firewall ----------------------------------------------------------------------------------------------------
# Docker publishes ports through its own iptables chains, bypassing UFW; the demo compose file publishes only
# 80/443 (Caddy) and binds MailHog to loopback, so nothing else is reachable regardless.
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# --- automatic security updates ----------------------------------------------------------------------------------
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF

# --- SSH hardening (only when a key exists, so a password-only instance is never locked out) ----------------------
if [[ -s /root/.ssh/authorized_keys ]]; then
  cat > /etc/ssh/sshd_config.d/99-svalinn.conf <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
CONF
  systemctl reload ssh 2>/dev/null || systemctl reload sshd
  log "ssh: key-only login enabled"
else
  log "ssh: no authorized_keys for root - password login left ON; add an SSH key and rerun hardening"
fi

touch /var/lib/svalinn-setup.done
log "setup complete - next: scp -r deploy/demo/* ${DEPLOY_USER}@<ip>:~/svalinn-demo/"
