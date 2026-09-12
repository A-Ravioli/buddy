#!/usr/bin/env bash
# Generate the signing keys for a device, once. Follows the GrapheneOS build guide's
# key generation: APK signing keys with make_key, and the verified boot (AVB) key.
#
# The keys directory is git-ignored. Back it up somewhere that is not this machine.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

cd "$BUDDY_TREE" || die "no tree at $BUDDY_TREE; run sync.sh first"
[[ -x development/tools/make_key ]] || die "development/tools/make_key not found; is the tree synced?"

if [[ -d "$BUDDY_KEYS" ]] && [[ -n "$(ls -A "$BUDDY_KEYS")" ]]; then
    die "$BUDDY_KEYS already has keys; refusing to overwrite. Move it aside if you really want new keys."
fi
mkdir -p "$BUDDY_KEYS"
cd "$BUDDY_KEYS"

SUBJECT='/CN=buddy/'
# The key names the GrapheneOS build expects. VERIFY the list against the pinned tag's
# build documentation; new keys appear across Android releases.
for key in releasekey platform shared media networkstack sdk_sandbox bluetooth verifiedboot; do
    log "generating $key"
    ../../development/tools/make_key "$key" "$SUBJECT" </dev/null
done

log "generating the verified boot key (AVB)"
openssl genrsa 4096 | openssl pkcs8 -topk8 -scrypt -out avb.pem
../../external/avb/avbtool.py extract_public_key --key avb.pem --output avb_pkmd.bin

log "keys written to $BUDDY_KEYS"
log "next: back this directory up, then scripts/build.sh"
