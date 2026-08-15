FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce

RUN apk add --no-cache \
      bash build-base ca-certificates clojure coreutils curl git jq nodejs npm \
      openjdk21-jdk python3 py3-pip ripgrep \
 && mkdir -p /workspace \
 && chmod 0777 /workspace

WORKDIR /workspace
CMD ["sleep", "infinity"]
