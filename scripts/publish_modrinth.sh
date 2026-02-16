#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

API_BASE="${MODRINTH_API_BASE:-https://api.modrinth.com/v2}"
PROJECT_SLUG_OR_ID="${MODRINTH_PROJECT:-safeserver}"
USER_AGENT="${MODRINTH_USER_AGENT:-andrewzhuang/safeserver-release-script/1.0 (https://modrinth.com/mod/safeserver)}"
VERSION_TYPE="${MODRINTH_VERSION_TYPE:-release}"    # release|beta|alpha
STATUS="${MODRINTH_STATUS:-listed}"                  # listed|unlisted|draft|archived
FEATURED="${MODRINTH_FEATURED:-false}"               # true|false
LOADERS_CSV="${MODRINTH_LOADERS:-fabric}"
GAME_VERSIONS_CSV="${MODRINTH_GAME_VERSIONS:-}"
CHANGELOG_FILE="${MODRINTH_CHANGELOG_FILE:-}"
INCLUDE_FABRIC_API_DEP="${MODRINTH_INCLUDE_FABRIC_API_DEP:-true}"

NEW_VERSION=""
SET_VERSION="false"
SKIP_BUILD="false"
JAR_FILE=""
SOURCES_JAR_FILE=""
CHANGELOG_TEXT=""

usage() {
  cat <<'EOF'
Usage: scripts/publish_modrinth.sh [options]

Options:
  --version <semver>          Override mod version for upload.
  --set-version               Also write --version into gradle.properties (mod_version).
  --jar <path>                Use this jar instead of auto-discovering build/libs artifact.
  --sources-jar <path>        Use this sources jar instead of auto-discovering build/libs artifact.
  --changelog-file <path>     Read changelog text from file.
  --changelog-text <text>     Provide changelog inline.
  --game-versions <csv>       Example: "1.21.11,1.21.10"
  --loaders <csv>             Example: "fabric,quilt" (default: fabric)
  --version-type <type>       release|beta|alpha
  --status <status>           listed|unlisted|draft|archived
  --featured <true|false>     Mark this version as featured.
  --skip-build                Skip ./gradlew build.
  -h, --help                  Show this help.

Required environment:
  MODRINTH_TOKEN              Personal access token with VERSION_CREATE scope.

Optional environment:
  MODRINTH_PROJECT            Project slug/id (default: safeserver)
  MODRINTH_API_BASE           API base (default: https://api.modrinth.com/v2)
  MODRINTH_USER_AGENT         Unique user-agent string
  MODRINTH_GAME_VERSIONS      CSV fallback if --game-versions not passed
  MODRINTH_LOADERS            CSV fallback if --loaders not passed
  MODRINTH_CHANGELOG_FILE     Fallback changelog file
  MODRINTH_INCLUDE_FABRIC_API_DEP  true|false (default: true)
EOF
}

log() {
  printf '[modrinth-release] %s\n' "$*"
}

fail() {
  printf '[modrinth-release] ERROR: %s\n' "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

read_gradle_property() {
  local key="$1"
  awk -F'=' -v k="${key}" '$1 == k { print substr($0, index($0, "=") + 1) }' gradle.properties | tail -n 1
}

write_gradle_mod_version() {
  local version="$1"
  local tmp
  tmp="$(mktemp)"
  awk -F'=' -v v="${version}" '
    BEGIN { updated=0 }
    $1=="mod_version" { print "mod_version=" v; updated=1; next }
    { print $0 }
    END { if (updated==0) exit 42 }
  ' gradle.properties > "${tmp}" || {
    rm -f "${tmp}"
    fail "Could not update mod_version in gradle.properties"
  }
  mv "${tmp}" gradle.properties
}

csv_to_json_array() {
  local csv="$1"
  jq -n --arg csv "${csv}" '
    $csv
    | split(",")
    | map(gsub("^\\s+|\\s+$"; ""))
    | map(select(length > 0))
  '
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      NEW_VERSION="${2:-}"
      shift 2
      ;;
    --set-version)
      SET_VERSION="true"
      shift
      ;;
    --jar)
      JAR_FILE="${2:-}"
      shift 2
      ;;
    --sources-jar)
      SOURCES_JAR_FILE="${2:-}"
      shift 2
      ;;
    --changelog-file)
      CHANGELOG_FILE="${2:-}"
      shift 2
      ;;
    --changelog-text)
      CHANGELOG_TEXT="${2:-}"
      shift 2
      ;;
    --game-versions)
      GAME_VERSIONS_CSV="${2:-}"
      shift 2
      ;;
    --loaders)
      LOADERS_CSV="${2:-}"
      shift 2
      ;;
    --version-type)
      VERSION_TYPE="${2:-}"
      shift 2
      ;;
    --status)
      STATUS="${2:-}"
      shift 2
      ;;
    --featured)
      FEATURED="${2:-}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1 (use --help)"
      ;;
  esac
done

need_cmd curl
need_cmd jq
need_cmd awk
need_cmd find

[[ -n "${MODRINTH_TOKEN:-}" ]] || fail "MODRINTH_TOKEN is required"
[[ "${VERSION_TYPE}" =~ ^(release|beta|alpha)$ ]] || fail "Invalid --version-type: ${VERSION_TYPE}"
[[ "${STATUS}" =~ ^(listed|unlisted|draft|archived)$ ]] || fail "Invalid --status: ${STATUS}"
[[ "${FEATURED}" =~ ^(true|false)$ ]] || fail "--featured must be true or false"

if [[ -n "${NEW_VERSION}" && "${SET_VERSION}" == "true" ]]; then
  log "Updating gradle.properties mod_version -> ${NEW_VERSION}"
  write_gradle_mod_version "${NEW_VERSION}"
fi

MOD_VERSION="${NEW_VERSION:-$(read_gradle_property mod_version)}"
[[ -n "${MOD_VERSION}" ]] || fail "Unable to resolve mod version"

ARCHIVES_BASE_NAME="$(read_gradle_property archives_base_name)"
[[ -n "${ARCHIVES_BASE_NAME}" ]] || ARCHIVES_BASE_NAME="safeserver"

if [[ -z "${GAME_VERSIONS_CSV}" ]]; then
  GAME_VERSIONS_CSV="$(read_gradle_property minecraft_version)"
fi
[[ -n "${GAME_VERSIONS_CSV}" ]] || fail "No game versions provided or found in gradle.properties"

if [[ -n "${CHANGELOG_TEXT}" ]]; then
  :
elif [[ -n "${CHANGELOG_FILE}" ]]; then
  [[ -f "${CHANGELOG_FILE}" ]] || fail "Changelog file not found: ${CHANGELOG_FILE}"
  CHANGELOG_TEXT="$(cat "${CHANGELOG_FILE}")"
else
  CHANGELOG_TEXT="Automated release for version ${MOD_VERSION}"
fi

if [[ "${SKIP_BUILD}" != "true" ]]; then
  log "Running build..."
  ./gradlew build
fi

if [[ -z "${JAR_FILE}" ]]; then
  if [[ -f "build/libs/${ARCHIVES_BASE_NAME}-${MOD_VERSION}.jar" ]]; then
    JAR_FILE="build/libs/${ARCHIVES_BASE_NAME}-${MOD_VERSION}.jar"
  else
    JAR_FILE="$(find build/libs -maxdepth 1 -type f -name "*.jar" ! -name "*-sources.jar" -print | sort | tail -n 1)"
  fi
fi

[[ -n "${JAR_FILE}" ]] || fail "No jar file found in build/libs"
[[ -f "${JAR_FILE}" ]] || fail "Jar file does not exist: ${JAR_FILE}"

if [[ -z "${SOURCES_JAR_FILE}" ]]; then
  if [[ -f "build/libs/${ARCHIVES_BASE_NAME}-${MOD_VERSION}-sources.jar" ]]; then
    SOURCES_JAR_FILE="build/libs/${ARCHIVES_BASE_NAME}-${MOD_VERSION}-sources.jar"
  else
    SOURCES_JAR_FILE="$(find build/libs -maxdepth 1 -type f -name "*-sources.jar" -print | sort | tail -n 1)"
  fi
fi

[[ -n "${SOURCES_JAR_FILE}" ]] || fail "No sources jar file found in build/libs"
[[ -f "${SOURCES_JAR_FILE}" ]] || fail "Sources jar file does not exist: ${SOURCES_JAR_FILE}"

log "Resolving project: ${PROJECT_SLUG_OR_ID}"
PROJECT_JSON="$(curl -fsS -H "User-Agent: ${USER_AGENT}" "${API_BASE}/project/${PROJECT_SLUG_OR_ID}")"
PROJECT_ID="$(jq -r '.id' <<< "${PROJECT_JSON}")"
PROJECT_SLUG="$(jq -r '.slug' <<< "${PROJECT_JSON}")"
[[ -n "${PROJECT_ID}" && "${PROJECT_ID}" != "null" ]] || fail "Could not resolve project id"

log "Checking for duplicate version number: ${MOD_VERSION}"
EXISTING_COUNT="$(
  curl -fsS -H "User-Agent: ${USER_AGENT}" "${API_BASE}/project/${PROJECT_ID}/version" \
  | jq --arg v "${MOD_VERSION}" '[.[] | select(.version_number == $v)] | length'
)"
if [[ "${EXISTING_COUNT}" != "0" ]]; then
  fail "Version ${MOD_VERSION} already exists on Modrinth for ${PROJECT_SLUG}"
fi

LOADERS_JSON="$(csv_to_json_array "${LOADERS_CSV}")"
GAME_VERSIONS_JSON="$(csv_to_json_array "${GAME_VERSIONS_CSV}")"
[[ "$(jq 'length' <<< "${LOADERS_JSON}")" -gt 0 ]] || fail "No loaders provided"
[[ "$(jq 'length' <<< "${GAME_VERSIONS_JSON}")" -gt 0 ]] || fail "No game versions provided"

DEPENDENCIES_JSON='[]'
if [[ "${INCLUDE_FABRIC_API_DEP}" == "true" ]] && jq -e '.[] | select(. == "fabric")' >/dev/null <<< "${LOADERS_JSON}"; then
  FABRIC_API_ID="$(curl -fsS -H "User-Agent: ${USER_AGENT}" "${API_BASE}/project/fabric-api" | jq -r '.id')"
  if [[ -n "${FABRIC_API_ID}" && "${FABRIC_API_ID}" != "null" ]]; then
    DEPENDENCIES_JSON="$(jq -n --arg pid "${FABRIC_API_ID}" '[{project_id:$pid, dependency_type:"required"}]')"
  fi
fi

VERSION_NAME="${PROJECT_SLUG} ${MOD_VERSION}"
PAYLOAD="$(
  jq -n \
    --arg name "${VERSION_NAME}" \
    --arg version_number "${MOD_VERSION}" \
    --arg changelog "${CHANGELOG_TEXT}" \
    --arg version_type "${VERSION_TYPE}" \
    --arg project_id "${PROJECT_ID}" \
    --arg status "${STATUS}" \
    --argjson featured "${FEATURED}" \
    --argjson dependencies "${DEPENDENCIES_JSON}" \
    --argjson game_versions "${GAME_VERSIONS_JSON}" \
    --argjson loaders "${LOADERS_JSON}" \
    '{
      name: $name,
      version_number: $version_number,
      changelog: $changelog,
      dependencies: $dependencies,
      game_versions: $game_versions,
      version_type: $version_type,
      loaders: $loaders,
      featured: $featured,
      status: $status,
      project_id: $project_id,
      file_parts: ["file", "sources"],
      primary_file: "file"
    }'
)"

log "Uploading ${JAR_FILE} and ${SOURCES_JAR_FILE} to Modrinth..."
UPLOAD_RESPONSE="$(
  curl -fsS -X POST "${API_BASE}/version" \
    -H "Authorization: ${MODRINTH_TOKEN}" \
    -H "User-Agent: ${USER_AGENT}" \
    -F "data=${PAYLOAD}" \
    -F "file=@${JAR_FILE}" \
    -F "sources=@${SOURCES_JAR_FILE}"
)"

VERSION_ID="$(jq -r '.id' <<< "${UPLOAD_RESPONSE}")"
[[ -n "${VERSION_ID}" && "${VERSION_ID}" != "null" ]] || fail "Upload response missing version id"

log "Release created successfully."
log "Version ID: ${VERSION_ID}"
log "Version URL: https://modrinth.com/mod/${PROJECT_SLUG}/version/${VERSION_ID}"
