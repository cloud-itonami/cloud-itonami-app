#!/bin/sh
set -eu
: "${CLOUD_ITONAMI_DATA_DIR:?Set a dedicated controller data directory}"
: "${JAVA_HOME:?Set JAVA_HOME on the controller host}"
cd "$(dirname "$0")"
exec "$JAVA_HOME/bin/java" -Xmx2g -cp "$(cat classpath.txt)" clojure.main -m cloud.itonami.app.controller-entry
