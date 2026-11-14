FROM golang:1.27-alpine AS build
ARG VERSION=dev
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# Static binary, because the distroless static base carries no libc.
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" \
    -o /out/supervisor .

# Runs as root on purpose: it rewrites the root-owned env file on the host mount.
FROM gcr.io/distroless/static-debian12
COPY --from=build /out/supervisor /supervisor
ENTRYPOINT ["/supervisor"]
