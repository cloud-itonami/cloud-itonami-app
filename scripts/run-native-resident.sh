#!/bin/sh
set -eu
: "${CLOUD_ITONAMI_NATIVE_RELEASE:?Set the immutable native release directory}"
: "${CLOUD_ITONAMI_DATA_DIR:?Set the existing owner data directory}"
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ITONAMI_MURAKUMO_KEY_FILE:?Set the existing scoped Murakumo credential path}"
unset OPENROUTER_API_KEY
export MURAKUMO_API_KEY="$(cat "$ITONAMI_MURAKUMO_KEY_FILE")"
cd "$CLOUD_ITONAMI_NATIVE_RELEASE"
exec ./run-controller.sh
