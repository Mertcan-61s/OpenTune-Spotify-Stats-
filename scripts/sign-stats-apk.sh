#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
  echo 'Usage: bash scripts/sign-stats-apk.sh unsigned.apk output-directory' >&2
  exit 1
fi

apk=$1
output=$2
tools="${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/36.0.0"
keystore=${OPENTUNE_STATS_KEYSTORE:?Set OPENTUNE_STATS_KEYSTORE to the release keystore path}
password=${OPENTUNE_STATS_PASSWORD_FILE:?Set OPENTUNE_STATS_PASSWORD_FILE to the password file path}
expected_signer=0dca64e0eba5a42e19998d68446f8c70dd14fe5e448381db6da8f1bcabb1718a

manifest=$("$tools/aapt" dump badging "$apk")
package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$manifest")
code=$(sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" <<< "$manifest")
version=$(sed -n "s/^package: .* versionName='\([0-9]*\.[0-9]*\.[0-9]*\)-spotifystats'.*/\1/p" <<< "$manifest")
if [[ "$package" != com.opentune.spotifystats || -z "$version" || -z "$code" || "$code" -lt 49 ]] ||
  grep -q '^application-debuggable' <<< "$manifest"; then
  echo 'Expected an optimized Spotify Stats release APK, version code 49 or higher.' >&2
  exit 1
fi

certificate=$(keytool -exportcert -keystore "$keystore" -storepass:file "$password" -alias opentune-stats | sha256sum | cut -d' ' -f1)
if [[ "$certificate" != "$expected_signer" ]]; then
  echo 'The provided key is different from the Spotify Stats release signing key.' >&2
  exit 1
fi

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
name="OpenTune-Spotify-Stats-v$version-universal.apk"
"$tools/zipalign" -P 16 -f 4 "$apk" "$scratch/aligned.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias opentune-stats \
  --ks-pass "file:$password" --v4-signing-enabled false \
  --out "$scratch/$name" "$scratch/aligned.apk"
signer=$("$tools/apksigner" verify --print-certs "$scratch/$name" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')
[[ "$signer" == "$expected_signer" ]] || { echo 'APK signature verification failed.' >&2; exit 1; }
"$tools/zipalign" -c -P 16 4 "$scratch/$name"

mkdir -p "$output"
mv "$scratch/$name" "$output/$name"
(
  cd "$output"
  sha256sum "$name" > SHA256SUMS.txt
  printf '{"package":"%s","version":"%s","versionCode":%s,"signerSha256":"%s"}\n' \
    "$package" "$version" "$code" "$signer" > release-metadata.json
)
