# Serves the bundle `npm run build` already produced, so CI builds the site once.
# Build it first: `npm ci && npm run build && docker build .`

# Runs as uid 101 and listens on 8080, so no root and no privileged port.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine
LABEL org.opencontainers.image.source="https://github.com/kalpak44/workspaced-ai"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
EXPOSE 8080
