# The harness each workspace pod runs: `opencode serve`, one opencode session per chat
# session, each working in its own directory under ~/sessions.

# opencode ships as one self-contained binary per platform on npm; only the musl build
# runs on Alpine. It is checked against the registry's integrity hash before use, so a
# tampered tarball fails the build rather than reaching a workspace. Bumping it means both
# ARGs: the hex of `npm view opencode-linux-x64-musl@<version> dist.integrity`.
ARG OPENCODE_VERSION=1.19.5
ARG OPENCODE_SHA512=cba9d46c7dd589a8cddfb29a555cb4fca9d6bff8b8f01c7940fbfd525f4e349c9ad68b2c57b08755bf9934573ad4ce46aa556d8c2bd957b4f478132a8491bb9a

FROM alpine:3.24 AS fetch
ARG OPENCODE_VERSION
ARG OPENCODE_SHA512
RUN apk add --no-cache curl \
 && curl -fsSL -o opencode.tgz \
      "https://registry.npmjs.org/opencode-linux-x64-musl/-/opencode-linux-x64-musl-${OPENCODE_VERSION}.tgz" \
 && echo "${OPENCODE_SHA512}  opencode.tgz" | sha512sum -c - \
 && tar -xzf opencode.tgz \
 && install -m 0755 package/bin/opencode /opencode

# opencode installs its plugin SDK from npm into the config directory whenever it is
# missing. Doing it once here keeps every pod start off the network, and lets opencode
# pick the SDK version that matches its own binary.
FROM alpine:3.24 AS config
RUN apk add --no-cache libgcc libstdc++
COPY --from=fetch /opencode /usr/local/bin/opencode
COPY opencode/ /stage/.config/opencode/
RUN HOME=/stage opencode debug config > /dev/null \
 && test -d /stage/.config/opencode/node_modules/@opencode-ai/plugin

FROM alpine:3.24
ARG OPENCODE_VERSION
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai" \
      io.workspaced.opencode.version="${OPENCODE_VERSION}"

# The agent's own tools: git and ripgrep, which opencode would otherwise download at run
# time into a home that does not survive the pod, curl, and a shell for its bash tool.
# tini reaps the processes that tool leaves behind.
RUN apk add --no-cache bash ca-certificates curl git libgcc libstdc++ ripgrep tini \
 && adduser -D -u 1000 -h /home/workspace workspace

COPY --from=fetch /opencode /usr/local/bin/opencode
# Kept out of the home: the pod mounts a fresh volume over it, which would hide anything
# baked in there. start.sh copies it across instead. Not /etc/opencode: opencode reads that
# as a managed config directory of its own, loading the plugin twice and writing into it.
COPY --from=config /stage/.config/opencode/ /usr/share/harness/opencode/
COPY start.sh /usr/local/bin/start-harness

ENV HOME=/home/workspace

USER 1000
WORKDIR /home/workspace
EXPOSE 4096
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["start-harness"]
