# The harness each workspace pod runs: OpenCode V2's service, with every chat attached to
# the workspace project and terminals served by OpenCode's PTY API.

# opencode ships as one self-contained binary per platform on npm; only the musl build
# runs on Alpine. It is checked against the registry's integrity hash before use, so a
# tampered tarball fails the build rather than reaching a workspace. Bumping it means both
# ARGs: the hex of `npm view opencode-linux-x64-musl@<version> dist.integrity`.
ARG OPENCODE_VERSION=2.2.5
ARG OPENCODE_SHA512=5e2e262ba044001d04e642096ed33aa4a93e9a0b4c8dba3782b5f87aef8d0e97d61a9e1e32e02fb1a5e7858609bf1ca27b695240123661d8bb437b28e323f6ba
# Alpine 3.24 packages npm 11.12.1, whose bundled tar, brace-expansion, ip-address,
# pacote, picomatch and sigstore versions have published vulnerabilities. The latest npm
# in the same major line carries those fixes; pin and verify it rather than trusting a
# mutable registry tag. Bumping NPM_VERSION means recomputing the tarball's SHA-512.
ARG NPM_VERSION=11.21.2
ARG NPM_SHA512=ec73c0a64f807e53146af4ac78bd4f9ba0a762a3081e4e77c7e2a6ba1aa64c3a8f9233cc8312294cf713aac13dd4428dc3880504957d7e75e249d1a6d435eaf4

FROM alpine:3.24 AS fetch
ARG OPENCODE_VERSION
ARG OPENCODE_SHA512
RUN apk add --no-cache curl \
 && curl -fsSL -o opencode.tgz \
      "https://registry.npmjs.org/@opencode/cli-linux-x64-musl/-/cli-linux-x64-musl-${OPENCODE_VERSION}.tgz" \
 && echo "${OPENCODE_SHA512}  opencode.tgz" | sha512sum -c - \
 && tar -xzf opencode.tgz \
 && install -m 0755 package/bin/opencode /opencode

FROM alpine:3.24 AS config
RUN apk add --no-cache libgcc libstdc++
COPY --from=fetch /opencode /usr/local/bin/opencode
COPY opencode/ /stage/.config/opencode/
RUN HOME=/stage opencode debug config > /dev/null

FROM alpine:3.24
ARG OPENCODE_VERSION
ARG NPM_VERSION
ARG NPM_SHA512
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai" \
      io.workspaced.opencode.version="${OPENCODE_VERSION}" \
      io.workspaced.npm.version="${NPM_VERSION}"

# The agent's own tools: git and ripgrep, which opencode would otherwise download at run
# time into a home that does not survive the pod, curl, and a shell for its bash tool.
# tini reaps the processes that tool leaves behind. Node.js and npm run MCP servers started
# with npx; nodejs-current is Alpine's Node 26, the active LTS line, where nodejs is still 24.
# Python, uv (which runs Python MCP servers with uvx), jq and yq, make, an editor, an SSH
# client for git, SQLite and the usual archive and file tools come with the image; anything
# else, the agent installs itself with apk (see apk.sh), into the persistent home.
# zlib arrives with the alpine:3.24 base as 1.3.2-r1, which carries CVE-2026-4738. The same
# branch publishes 1.4-r0, but apk add leaves an already-installed base package alone, so
# ask for that upgrade explicitly instead of relying on the base image to move.
RUN apk add --no-cache --upgrade zlib bash ca-certificates curl git libgcc libstdc++ nodejs-current ripgrep tini \
      file jq make nano openssh-client py3-pip python3 sqlite tree uv yq-go zip \
 && curl -fsSL -o /tmp/npm.tgz \
      "https://registry.npmjs.org/npm/-/npm-${NPM_VERSION}.tgz" \
 && echo "${NPM_SHA512}  /tmp/npm.tgz" | sha512sum -c - \
 && mkdir -p /usr/local/lib/node_modules/npm \
 && tar -xzf /tmp/npm.tgz -C /usr/local/lib/node_modules/npm --strip-components=1 \
 && ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && rm -r /tmp/npm.tgz /usr/local/lib/node_modules/npm/docs /usr/local/lib/node_modules/npm/man \
 && adduser -D -u 1000 -h /home/workspace workspace \
 && rm /var/log/apk.log

COPY --from=fetch /opencode /usr/local/bin/opencode
# Kept out of the home: the pod mounts a fresh volume over it, which would hide anything
# baked in there. start.sh copies it across instead. Not /etc/opencode: opencode reads that
# as a managed config directory of its own, loading the plugin twice and writing into it.
COPY --from=config /stage/.config/opencode/opencode.json /usr/share/harness/opencode/opencode.json
# What every agent is told: system.md, the guide to this container, which OpenCode reads from
# here, read-only; and defaults/, the user's starting instructions, which start.sh copies into
# the home on the first start, where the user and the agent can change them.
COPY instructions/ /usr/share/harness/instructions/
COPY start.sh /usr/local/bin/start-harness
# Ahead of /sbin/apk on PATH, so a plain `apk add` installs into the home without root.
COPY apk.sh /usr/local/bin/apk
# The musl loader looks in the image's library folders first, then in the home's package
# root, so a program installed there finds its libraries and the image's own are never
# shadowed by them.
RUN printf '%s\n' /lib /usr/local/lib /usr/lib \
      /home/workspace/.local/alpine/lib /home/workspace/.local/alpine/usr/lib \
      > /etc/ld-musl-x86_64.path
COPY terminal.sh /etc/profile.d/workspaced-terminal.sh

# The root filesystem is read-only and only the home survives a restart, so a global npm
# install goes into the home, and what it installs is on PATH. terminal.sh restores this PATH
# for login shells, whose /etc/profile resets it.
# pip and uv install into the home too; pip is told Alpine's own Python may take user packages.
# The home's apk root comes after the image's folders on PATH, so the image's tools win.
ENV HOME=/home/workspace TERM=xterm-256color COLORTERM=truecolor \
    NPM_CONFIG_PREFIX=/home/workspace/.local \
    PIP_USER=1 PIP_BREAK_SYSTEM_PACKAGES=1 PYTHONUSERBASE=/home/workspace/.local \
    UV_TOOL_BIN_DIR=/home/workspace/.local/bin \
    PATH=/home/workspace/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/workspace/.local/alpine/usr/bin:/home/workspace/.local/alpine/bin:/home/workspace/.local/alpine/usr/sbin

USER 1000
WORKDIR /home/workspace
EXPOSE 4096
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["start-harness"]
