#!/usr/bin/env bash
# One-time build host setup for Debian or Ubuntu. Installs the packages GrapheneOS's
# build documentation lists, the repo tool, and a ccache. Re-runnable.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

need sudo
log "installing build packages"
sudo apt-get update
sudo apt-get install -y \
    git git-lfs gnupg python3 python3-pip curl zip unzip rsync \
    bison flex gperf libssl-dev libncurses-dev libxml2-utils \
    xsltproc zlib1g-dev ccache openjdk-21-jdk-headless \
    e2fsprogs fdisk fontconfig lz4 signify-openbsd yarnpkg \
    android-sdk-platform-tools

log "installing repo"
mkdir -p "$HOME/bin"
curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$HOME/bin/repo"
chmod a+x "$HOME/bin/repo"
grep -q 'HOME/bin' "$HOME/.profile" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.profile"

log "configuring git"
git config --global user.name  >/dev/null || git config --global user.name "buddy builder"
git config --global user.email >/dev/null || git config --global user.email "builder@buddy.invalid"
git config --global color.ui auto

log "configuring ccache (50G)"
ccache -M 50G >/dev/null
grep -q USE_CCACHE "$HOME/.profile" 2>/dev/null || cat >> "$HOME/.profile" <<'EOF'
export USE_CCACHE=1
export CCACHE_EXEC=/usr/bin/ccache
EOF

log "importing the GrapheneOS release signing key for tag verification"
# Their allowed_signers file is published with the platform_manifest; sync.sh verifies
# tags with it. Nothing to do here beyond having ssh-keygen available.
need ssh-keygen

mkdir -p "$BUDDY_TREE" "$BUDDY_RELEASES"
log "done. Open a new shell (PATH and ccache), then run scripts/sync.sh <tag>."
