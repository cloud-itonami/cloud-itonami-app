#!/usr/bin/env bash
# itonami agent container entrypoint (JUN-77)
#   serve (default)  -> resident host (bin/cloud-itonami-server) in the foreground
#   cli <args…>      -> itonami CLI against the resident (CLOUD_ITONAMI_* env)
#   sh               -> debug shell
set -eu
APP=/opt/itonami/cloud-itonami/cloud-itonami-app
CLI=/opt/itonami/cloud-itonami/cloud-itonami-cli

case "${1:-serve}" in
  serve)
    exec kbb --backend sci --classpath "$APP/bin" "$APP/bin/cloud-itonami-server"
    ;;
  cli)
    shift
    exec "$CLI/bin/itonami" "$@"
    ;;
  sh)
    exec /bin/bash
    ;;
  *)
    exec "$CLI/bin/itonami" "$@"
    ;;
esac
