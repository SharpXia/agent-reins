#!/bin/bash
# 查 TestFlight build 处理状态（ASC API，ES256 JWT + openssl，零依赖）
# 用法：set -a; source .env; set +a; bash scripts/check-tf-builds.sh
set -euo pipefail
KEY_FILE="$HOME/Documents/Apple证书/AuthKey_${ASC_KEY_ID}.p8"
[ -f "$KEY_FILE" ] || KEY_FILE="$HOME/Documents/AuthKey_${ASC_KEY_ID}.p8"
[ -f "$KEY_FILE" ] || { echo "no key at $KEY_FILE"; exit 1; }

NOW=$(date +%s)
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

HEADER=$(printf '{"alg":"ES256","kid":"%s"}' "$ASC_KEY_ID" | openssl dgst -sha256 -binary | b64url)
PAYLOAD=$(printf '{"iss":"%s","iat":%d,"exp":%d,"aud":"appstoreconnect-v1"}' "$ASC_ISSUER_ID" "$NOW" "$((NOW + 1200))" | openssl dgst -sha256 -binary | b64url)
SIGNING_INPUT="${HEADER}.${PAYLOAD}"
DER_SIG=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$KEY_FILE" -binary)
# DER → r||s (64 bytes)
SIG=$(python3 -c "
import sys
der = sys.stdin.buffer.read()
i = 2
r_len = der[3]; r = der[4:4+r_len]; s_start = 4 + r_len + 2
s = der[s_start:]
r = r.lstrip(b'\x00').rjust(32, b'\x00'); s = s.lstrip(b'\x00').rjust(32, b'\x00')
sys.stdout.write((r + s).hex())
" <<< "$DER_SIG" 2>/dev/null || echo "")
SIG_B64=$(python3 -c "
import binascii, sys
print(binascii.unhexlify('$SIG').hex())" >/dev/null 2>&1; python3 -c "
import binascii
print(binascii.unhexlify('$SIG').hex())" >/dev/null; python3 - << PYEOF
import binascii
print(binascii.unhexlify('$SIG').hex())
PYEOF
)
JWT="${SIGNING_INPUT}.${SIG_B64}"

curl -s --globoff -H "Authorization: Bearer $JWT" \
  "https://api.appstoreconnect.apple.com/v1/apps?filter[bundleId]=dev.sharpxia.agentreins" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['data'][0]['id'] if d.get('data') else 'APP_NOT_FOUND')" > /tmp/app_id.txt
APP_ID=$(cat /tmp/app_id.txt)
echo "appId=$APP_ID"

curl -s --globoff -H "Authorization: Bearer $JWT" \
  "https://api.appstoreconnect.apple.com/v1/builds?filter[app]=$APP_ID&sort=-uploadedDate&limit=5&fields[build]=version,processingState,uploadedDate" \
  | python3 -c "
import json, sys
d = json.load(sys.stdin)
for b in d.get('data', []):
    a = b['attributes']
    print(f\"build {a['version']:>12}  {a['processingState']:<18} uploaded {a['uploadedDate']}\")"
