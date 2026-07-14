#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${ROOT_DIR}/prod-run/server"
MODS_DIR="${SERVER_DIR}/mods"
LOG_DIR="${SERVER_DIR}/logs"
BENCH_DIR="${ROOT_DIR}/prod-run/benchmarks"
MODE="${1:-without-c2mef}"
TIMEOUT_SECONDS="${2:-900}"
BENCHMARK_COUNTS="${3:-1,5,10,20}"
COUNTS_SLUG="$(echo "${BENCHMARK_COUNTS}" | tr ',' '-' | tr -cd '0-9-')"

case "${MODE}" in
  without-c2mef)
    WITH_C2MEF="false"
    OUTPUT_FILE="${BENCH_DIR}/instance-benchmark-no-c2mef-${COUNTS_SLUG}.json"
    ;;
  with-c2mef)
    WITH_C2MEF="true"
    OUTPUT_FILE="${BENCH_DIR}/instance-benchmark-with-c2mef-${COUNTS_SLUG}.json"
    ;;
  *)
    echo "Usage: $0 [without-c2mef|with-c2mef] [timeout-seconds] [counts]" >&2
    exit 1
    ;;
esac

mkdir -p "${BENCH_DIR}" "${LOG_DIR}"
rm -f "${OUTPUT_FILE}"

MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.20.1}"
FORGE_VERSION="${FORGE_VERSION:-47.4.10}"
FORGE_COORD_VERSION="${MINECRAFT_VERSION}-${FORGE_VERSION}"
C2MEF_JAR_NAME="${C2MEF_JAR_NAME:-c2meF-0.2.0+alpha.13-all.jar}"
C2MEF_JAR_PATH="${C2MEF_JAR_PATH:-${HOME}/.gradle/caches/modules-2/files-2.1/curse.maven/concurrent-chunk-management-engine-for-forge-the-1484205/7746800/f70f85cfd8bfdc94bf608c7f527bb5f1fa8d1776/concurrent-chunk-management-engine-for-forge-the-1484205-7746800.jar}"
KFF_JAR_NAME="${KFF_JAR_NAME:-kotlin-for-forge-351264-4578885.jar}"
KFF_JAR_PATH="${KFF_JAR_PATH:-${HOME}/.gradle/caches/modules-2/files-2.1/curse.maven/kotlin-for-forge-351264/4578885/8ee4cc16fa04a089f5386baae13137364c29a287/kotlin-for-forge-351264-4578885.jar}"
FORGE_INSTALLER_PATH="${FORGE_INSTALLER_PATH:-${ROOT_DIR}/.cache/forge/forge-${FORGE_COORD_VERSION}-installer.jar}"

ENGINE_JAR="$(find "${ROOT_DIR}/instanced-dimensions/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | head -n 1)"
DIMENSION_DRINK_JAR="$(find "${ROOT_DIR}/dimension_drink/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' | head -n 1)"

require_file() {
  local path="$1"
  local label="$2"
  if [[ ! -f "${path}" ]]; then
    echo "Missing ${label}: ${path}" >&2
    exit 1
  fi
}

download_installer() {
  mkdir -p "$(dirname "${FORGE_INSTALLER_PATH}")"
  if [[ ! -f "${FORGE_INSTALLER_PATH}" ]]; then
    curl -L "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_COORD_VERSION}/forge-${FORGE_COORD_VERSION}-installer.jar" -o "${FORGE_INSTALLER_PATH}"
  fi
}

install_server() {
  mkdir -p "${SERVER_DIR}"
  if [[ ! -f "${SERVER_DIR}/run.sh" ]]; then
    (
      cd "${SERVER_DIR}"
      java -jar "${FORGE_INSTALLER_PATH}" --installServer
    )
  fi
}

stage_mods() {
  mkdir -p "${MODS_DIR}" "${LOG_DIR}"
  rm -f "${MODS_DIR}"/*.jar
  cp "${ENGINE_JAR}" "${MODS_DIR}/"
  cp "${DIMENSION_DRINK_JAR}" "${MODS_DIR}/"
  cp "${KFF_JAR_PATH}" "${MODS_DIR}/${KFF_JAR_NAME}"
  if [[ "${WITH_C2MEF}" == "true" ]]; then
    cp "${C2MEF_JAR_PATH}" "${MODS_DIR}/${C2MEF_JAR_NAME}"
  fi
  printf 'eula=true\n' > "${SERVER_DIR}/eula.txt"
}

backup_jvm_args() {
  USER_JVM_ARGS_PATH="${SERVER_DIR}/user_jvm_args.txt"
  USER_JVM_ARGS_BACKUP_PATH="${SERVER_DIR}/user_jvm_args.txt.codex.bak"
  cp "${USER_JVM_ARGS_PATH}" "${USER_JVM_ARGS_BACKUP_PATH}"
  cat "${USER_JVM_ARGS_BACKUP_PATH}" > "${USER_JVM_ARGS_PATH}"
  {
    printf '\n'
    echo "-Dinstanceddimensions.benchmark=true"
    echo "-Dinstanceddimensions.benchmark.output=${OUTPUT_FILE}"
    echo "-Dinstanceddimensions.benchmark.counts=${BENCHMARK_COUNTS}"
    echo "-Dinstanceddimensions.benchmark.steadyTicks=100"
    echo "-Dinstanceddimensions.benchmark.template=overworld"
  } >> "${USER_JVM_ARGS_PATH}"
}

restore_jvm_args() {
  if [[ -n "${USER_JVM_ARGS_BACKUP_PATH:-}" && -f "${USER_JVM_ARGS_BACKUP_PATH}" ]]; then
    mv "${USER_JVM_ARGS_BACKUP_PATH}" "${USER_JVM_ARGS_PATH}"
  fi
}

wait_for_benchmark_completion() {
  local start_ts
  start_ts="$(date +%s)"

  while true; do
    if [[ -f "${OUTPUT_FILE}" ]]; then
      return 0
    fi

    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      echo "Benchmark server exited before producing ${OUTPUT_FILE}" >&2
      tail -n 200 "${LOG_FILE}" >&2 || true
      return 1
    fi

    if (( "$(date +%s)" - start_ts >= TIMEOUT_SECONDS )); then
      echo "Timed out waiting for benchmark results at ${OUTPUT_FILE}" >&2
      tail -n 200 "${LOG_FILE}" >&2 || true
      return 1
    fi

    sleep 2
  done
}

shutdown_server() {
  if [[ -n "${PIPE_FD:-}" ]]; then
    printf 'stop\n' >&"${PIPE_FD}" || true
    exec {PIPE_FD}>&- || true
    exec {PIPE_FD}<&- || true
  fi
  wait "${SERVER_PID:-0}" || true
  rm -f "${STDIN_PIPE:-}"
  restore_jvm_args
}

main() {
  require_file "${KFF_JAR_PATH}" "Kotlin for Forge jar"
  require_file "${ENGINE_JAR}" "Instanced Dimensions jar"
  require_file "${DIMENSION_DRINK_JAR}" "Dimension Drink jar"
  if [[ "${WITH_C2MEF}" == "true" ]]; then
    require_file "${C2MEF_JAR_PATH}" "C2MEF jar"
  fi

  download_installer
  require_file "${FORGE_INSTALLER_PATH}" "Forge installer"
  install_server
  stage_mods
  backup_jvm_args

  LOG_FILE="${LOG_DIR}/instance-benchmark-${MODE}.log"
  STDIN_PIPE="${SERVER_DIR}/server.stdin"

  rm -f "${SERVER_DIR}/world/session.lock" "${STDIN_PIPE}"
  mkfifo "${STDIN_PIPE}"
  : > "${LOG_FILE}"
  exec {PIPE_FD}<>"${STDIN_PIPE}"

  (
    cd "${SERVER_DIR}"
    bash ./run.sh nogui <&"${PIPE_FD}" > "${LOG_FILE}" 2>&1
  ) &
  SERVER_PID=$!
  trap shutdown_server EXIT

  wait_for_benchmark_completion
  echo "Benchmark completed: ${OUTPUT_FILE}"
}

main
