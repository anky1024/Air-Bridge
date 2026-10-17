#!/usr/bin/env sh
set -eu

GRADLE_VERSION="8.11.1"
DIST="gradle-${GRADLE_VERSION}-bin.zip"
CACHE_DIR="${HOME}/.gradle/airbridgetouch-bootstrap"
GRADLE_HOME="${CACHE_DIR}/gradle-${GRADLE_VERSION}"
ZIP_PATH="${CACHE_DIR}/${DIST}"
URL="https://services.gradle.org/distributions/${DIST}"

if ! command -v java >/dev/null 2>&1; then
  echo "Java is required. Install JDK 17 and set JAVA_HOME." >&2
  exit 1
fi

if command -v sdkmanager >/dev/null 2>&1; then
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null || true
fi

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  mkdir -p "${CACHE_DIR}"
  if [ ! -f "${ZIP_PATH}" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "${URL}" -o "${ZIP_PATH}"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "${URL}" -O "${ZIP_PATH}"
    else
      echo "curl or wget is required to bootstrap Gradle." >&2
      exit 1
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "${ZIP_PATH}" -d "${CACHE_DIR}"
  else
    echo "unzip is required to bootstrap Gradle." >&2
    exit 1
  fi
fi

exec "${GRADLE_HOME}/bin/gradle" "$@"
