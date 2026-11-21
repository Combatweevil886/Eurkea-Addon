#!/usr/bin/env bash
# Boots a real Forge dedicated server with Valkyrien Skies + Eureka + this mod and runs the in-game self-test.
#
#   ci/smoke-test.sh <eureka-version> <mod-jar> <output-dir>
#
# Exit code 0 only if the self-test printed "SMOOTHSAILING-SELFTEST RESULT: PASS".
set -uo pipefail

EUREKA_VERSION="$1"   # e.g. 1.6.3
MOD_JAR="$2"
OUT="$3"

FORGE_VERSION="1.20.1-47.4.20"   # the pack's Forge version
VS2_VERSION="1.20.1-forge-2.4.11" # the pack's Valkyrien Skies version
KFF_VERSION="4.12.0"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
MOD_JAR="$(cd "$(dirname "$MOD_JAR")" && pwd)/$(basename "$MOD_JAR")"
BASE="${RUNNER_TEMP:-/tmp}/forge-server-base"
WORK="${RUNNER_TEMP:-/tmp}/smoke-eureka-${EUREKA_VERSION}"

log() { echo "[smoke $EUREKA_VERSION] $*"; }

# ---------------------------------------------------------------- Forge server (installed once, reused)
if [ ! -f "$BASE/run.sh" ]; then
  log "installing Forge $FORGE_VERSION"
  mkdir -p "$BASE"
  curl -fsSL -o "$BASE/installer.jar" \
    "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-installer.jar"
  (cd "$BASE" && java -jar installer.jar --installServer > installer.log 2>&1) || { log "forge install failed"; cp "$BASE/installer.log" "$OUT/"; exit 1; }
  rm -f "$BASE/installer.jar"
fi

rm -rf "$WORK"
cp -r "$BASE" "$WORK"
mkdir -p "$WORK/mods"

# ---------------------------------------------------------------- mods from Modrinth
modrinth() { # slug version_number
  local slug="$1" ver="$2" url
  url=$(curl -fsSL "https://api.modrinth.com/v2/project/${slug}/version?loaders=%5B%22forge%22%5D&game_versions=%5B%221.20.1%22%5D" \
    | jq -r --arg v "$ver" '[.[] | select(.version_number == $v)][0].files | (map(select(.primary)) + .)[0].url')
  if [ -z "$url" ] || [ "$url" = "null" ]; then
    log "could not find $slug $ver on Modrinth"; return 1
  fi
  log "downloading $slug $ver"
  curl -fsSL -o "$WORK/mods/$(basename "$url")" "$url"
}
modrinth valkyrien-skies "$VS2_VERSION" || exit 1
modrinth eureka "1.20.1-forge-${EUREKA_VERSION}" || exit 1
modrinth kotlin-for-forge "$KFF_VERSION" || exit 1
cp "$MOD_JAR" "$WORK/mods/"
ls -la "$WORK/mods" | tee "$OUT/mods.txt"

# ---------------------------------------------------------------- server config
cat > "$WORK/eula.txt" <<EOF
eula=true
EOF
cat > "$WORK/server.properties" <<EOF
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-tick-time=-1
allow-flight=true
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
enable-command-block=false
sync-chunk-writes=false
EOF
cat > "$WORK/user_jvm_args.txt" <<EOF
-Xmx3G
-Dsmoothsailing.selftest=true
-Dfml.readTimeout=180
EOF

# ---------------------------------------------------------------- run
log "starting server"
cd "$WORK"
timeout 900 bash ./run.sh nogui > "$OUT/console.log" 2>&1
CODE=$?
log "server exited with $CODE"

cp -f logs/latest.log "$OUT/latest.log" 2>/dev/null || true
cp -f smoothsailing-selftest.txt "$OUT/selftest.txt" 2>/dev/null || true
cp -rf crash-reports "$OUT/" 2>/dev/null || true
cp -f config/smoothsailing-common.toml "$OUT/" 2>/dev/null || true
grep -n -i -E "mixin.*(smoothsailing|error|fail)|smoothsailing|SelfTest|Exception|Caused by" logs/latest.log | head -400 > "$OUT/highlights.log" 2>/dev/null || true

if grep -q "SMOOTHSAILING-SELFTEST RESULT: PASS" "$OUT/console.log" logs/latest.log 2>/dev/null; then
  log "SELFTEST PASS"
  exit 0
fi
log "SELFTEST FAILED"
exit 1
