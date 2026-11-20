-- Generate internal database credentials; users provide only their WordPress login.
UPDATE recipe
SET content = $recipe$# WordPress blog

Follow the phases in order. This recipe deliberately pauses for the user to save secrets and
open ports using the real Settings screens. Do not skip a checkpoint, open ports yourself, or
claim success before the checks pass. Applying the recipe permits publishing this blog over
trusted HTTPS on the workspace's own public IP. It does not permit deleting existing content.

Use `~/project/blog` and Compose project name `workspaced-blog`. On a fresh workspace, use the
files below. On retries, reuse existing files, volumes and valid certificates; do not reset the
database, repeat forced certificate issuance or overwrite an existing site. Inspect a different
existing setup before adapting it. Keep user-provided secret values out of chat, files, command literals and
logs. Generated database credentials belong only in the protected credentials volume described below. Command examples reference the environment; do not expand them into tool-call text or use
shell tracing. The private TLS key belongs only in the certificate volume.

## Phase 1 — check secrets, then pause if any are missing

Check that both `WORDPRESS_ADMIN_USER` and `WORDPRESS_ADMIN_PASSWORD` are set
without printing their values. If any are missing, list both required names (mark which
are missing), explain
**Settings → Secrets**, and ask the user to save them and restart the workspace. These
are their chosen WordPress login. Generate database credentials yourself in Phase 2; never ask
the user to create a database secret or paste a database password. Never ask for a
password in chat. End this turn and wait for the user to return. Do not start installation yet.

If both exist after a restart, continue. Get the real public IPv4 from `$WORKSPACE_IP` or
`firewall list`; never invent an address, use the platform hostname for the site, or request a
certificate for the platform hostname. The site URL is `https://<the-public-IP>`.

## Phase 2 — generate internal credentials and prepare the private stack

Create `compose.yml` with these services. All persistent data and proxy configuration use named
volumes: a harness path is not necessarily a path on the Docker daemon's host. Only nginx
publishes ports. Pull the official images, record their resolved digests in `images.lock`, and
pin the Compose image references to those digests before launch so restarts use the same images.
Check that the Certbot image is version 5.4 or newer, which supports IP webroot certificates.

The `credentials` helper generates two independent random 256-bit passwords inside a named
Docker volume: one for WordPress's database user and one for database administration. It never
prints them or writes them into Compose, chat, shell arguments or workspace instructions.
The services use the official images' `_FILE` settings to read these files. Run the helper
before starting the stack. Reuse existing files on every retry; do not rotate a database's
password by regenerating files. If database data exists but credentials are missing, stop and
recover the existing credentials rather than inventing new ones or deleting data. For a setup
created with an earlier recipe, retain its working credentials and configuration until a safe
migration is possible; never overwrite them with this fresh-install template.

```yaml
name: workspaced-blog
x-wordpress-environment: &wordpress-environment
  WORDPRESS_DB_HOST: db:3306
  WORDPRESS_DB_NAME: wordpress
  WORDPRESS_DB_USER: wordpress
  WORDPRESS_DB_PASSWORD_FILE: /run/secrets/database-password
  WORDPRESS_CONFIG_EXTRA: |
    if (isset($$_SERVER['HTTP_X_FORWARDED_PROTO']) && $$_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https') {
      $$_SERVER['HTTPS'] = 'on';
    }
    if (!defined('FORCE_SSL_ADMIN')) { define('FORCE_SSL_ADMIN', true); }
services:
  credentials:
    image: alpine:latest
    profiles: [tools]
    entrypoint: [sh, -ec]
    command:
      - |
        umask 077
        if [ -d /var/lib/mysql/mysql ]; then
          test -s /run/secrets/database-password && test -s /run/secrets/root-password || {
            echo 'Existing database needs its original credentials; stopping.' >&2
            exit 1
          }
        fi
        if [ ! -s /run/secrets/database-password ]; then
          od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > /run/secrets/database-password.tmp
          test "`wc -c < /run/secrets/database-password.tmp`" -eq 64
          mv /run/secrets/database-password.tmp /run/secrets/database-password
        fi
        if [ ! -s /run/secrets/root-password ]; then
          od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > /run/secrets/root-password.tmp
          test "`wc -c < /run/secrets/root-password.tmp`" -eq 64
          mv /run/secrets/root-password.tmp /run/secrets/root-password
        fi
        chown 33:33 /run/secrets/database-password
        chmod 440 /run/secrets/database-password
        chown 0:0 /run/secrets/root-password
        chmod 400 /run/secrets/root-password
    volumes:
      - credentials:/run/secrets
      - database:/var/lib/mysql:ro
  db:
    image: mariadb:lts
    restart: unless-stopped
    environment:
      MARIADB_DATABASE: wordpress
      MARIADB_USER: wordpress
      MARIADB_PASSWORD_FILE: /run/secrets/database-password
      MARIADB_ROOT_PASSWORD_FILE: /run/secrets/root-password
    volumes:
      - database:/var/lib/mysql
      - credentials:/run/secrets:ro
    healthcheck:
      test: [CMD, healthcheck.sh, --connect, --innodb_initialized]
      interval: 5s
      timeout: 5s
      retries: 30
  wordpress:
    image: wordpress:apache
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment: *wordpress-environment
    volumes:
      - wordpress:/var/www/html
      - credentials:/run/secrets:ro
  proxy:
    image: nginx:alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - proxy-config:/etc/nginx/conf.d:ro
      - challenges:/var/www/acme:ro
      - certificates:/etc/letsencrypt:ro
  proxy-config:
    image: alpine:latest
    profiles: [tools]
    entrypoint: [sh, -c, "cat > /etc/nginx/conf.d/default.conf"]
    volumes:
      - proxy-config:/etc/nginx/conf.d
  cli:
    image: wordpress:cli
    profiles: [tools]
    user: "33:33"
    working_dir: /var/www/html
    entrypoint: [wp]
    environment: *wordpress-environment
    volumes:
      - wordpress:/var/www/html
      - credentials:/run/secrets:ro
  certbot:
    image: certbot/certbot:latest
    profiles: [tools]
    volumes:
      - challenges:/var/www/acme
      - certificates:/etc/letsencrypt
volumes:
  credentials:
  database:
  wordpress:
  proxy-config:
  challenges:
  certificates:
```

Write `nginx-bootstrap.conf`. It exposes only challenge files and returns 404 for every other
HTTP request. Do not expose the WordPress installation screen or login over HTTP.

```nginx
server {
    listen 80 default_server;
    server_name _;
    location ^~ /.well-known/acme-challenge/ { root /var/www/acme; }
    location / { return 404; }
}
```

Populate the configuration volume through standard input and start only the three long-running
services. Do not mount the harness's configuration files into host Docker by path.

```sh
cd ~/project/blog
docker compose run --rm credentials
docker compose run --rm -T proxy-config < nginx-bootstrap.conf
docker compose up -d db wordpress proxy
docker compose exec -T proxy nginx -t
docker compose run --rm cli --info
```

Wait for the database health check and WordPress volume initialization. Verify nginx configuration
and inspect only non-secret service diagnostics. Do not print expanded Compose configuration or
container environments. Validate Compose syntax with `docker compose config --quiet` if needed;
never use an option that prints resolved environment values.

## Phase 3 — ask the user to open the ports, then stop

If public TCP 80 and 443 are not both open, tell the user the private stack is ready and ask them
to open **Settings → Firewall**, add **TCP 80** and **TCP 443**, with **From: Anyone**, then return
to this chat. Explain that 80 is for Let's Encrypt validation and HTTP redirects, while 443 is
for encrypted website traffic. **Do not call `firewall open` yourself**, even if you have that
permission. Stop this turn and wait. Do not request a certificate before this checkpoint passes.

After the user confirms, check `firewall list`. If a required rule is missing or restricted to
addresses that exclude the public ACME validator, explain which rule needs changing and pause
again. Check that a temporary file in the challenge volume can be fetched through
`http://<public-IP>/.well-known/acme-challenge/<test-name>` and remove that test file afterwards.
Only then continue.

## Phase 4 — obtain and enable trusted HTTPS

Reuse a valid `wordpress-ip` certificate if one already exists. Otherwise issue a production
Let's Encrypt IP certificate with the shortlived profile:

```sh
docker compose run --rm certbot certonly --webroot --webroot-path /var/www/acme --ip-address "$WORKSPACE_IP" --preferred-profile shortlived --cert-name wordpress-ip --non-interactive --agree-tos --register-unsafely-without-email
```

Write `nginx-https.conf` exactly as below. The certificate volume contains the whole Certbot
configuration tree, so its live-certificate symlinks resolve. WordPress is private and receives
forwarding headers only from this proxy.

```nginx
server {
    listen 80 default_server;
    server_name _;
    location ^~ /.well-known/acme-challenge/ { root /var/www/acme; }
    location / { return 308 https://$host$request_uri; }
}
server {
    listen 443 ssl default_server;
    server_name _;
    ssl_certificate /etc/letsencrypt/live/wordpress-ip/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/wordpress-ip/privkey.pem;
    client_max_body_size 32m;
    location / {
        proxy_pass http://wordpress:80;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

Load it through the configuration helper, test it, then reload nginx. If validation fails,
restore the previous configuration before retrying. Never fall back to a self-signed certificate,
insecure HTTP login, or instructions to bypass a browser warning.

```sh
docker compose run --rm -T proxy-config < nginx-https.conf
docker compose exec -T proxy nginx -t
docker compose exec -T proxy nginx -s reload
```

## Phase 5 — install WordPress and verify a real article

Check `docker compose run --rm cli core is-installed` before installing. For a fresh database,
use the stored admin credentials without putting the password on the command line:

```sh
printf '%s\n' "$WORDPRESS_ADMIN_PASSWORD" | docker compose run --rm -T cli core install --url="https://$WORKSPACE_IP" --title="My WordPress blog" --admin_user="$WORDPRESS_ADMIN_USER" --admin_email=admin@example.invalid --skip-email --prompt=admin_password
```

For a retry, keep the existing database and content. Confirm `home` and `siteurl` use the HTTPS
IP URL; perform safe URL replacement only if migrating an earlier HTTP installation, skipping
GUID columns. Get the numeric admin user ID with WP-CLI. Write an original welcome article to
`welcome.html` and publish it by streaming it into the CLI, substituting that numeric ID:

```sh
cat welcome.html | docker compose run --rm -T cli post create - --post_title='Welcome to my blog' --post_status=publish --post_author=ADMIN_ID --porcelain
```

Check for the existing welcome article first so retries do not duplicate it. Remove only the
untouched default sample post/page if present. Retrieve the published permalink, then verify
both the home page and article over HTTPS with normal certificate validation. Check the admin
page and HTTP redirect too. Do not use `curl -k` or `--insecure`, and fix mixed-content URLs.
Do not announce deployment success until these checks pass.

## Phase 6 — renewal and shared CLI instructions

IP certificates last about six days. Create a persistent workspace service script
`~/.config/workspaced/services/wordpress-tls-renew.sh` using the platform's foreground service
runner. Its `exec bash` loop runs `docker compose run --rm -T certbot renew --quiet` in
`~/project/blog` every 12 hours and tests/reloads nginx only after a successful renewal check.
Log failures without credentials. Do not use an AI task, host cron, systemd, a background
process or a Docker socket mount in nginx. Check `certbot renew --dry-run` and the service log.
The four-hour trial still deletes the entire workspace when it expires.

Write `~/.config/workspaced/instructions/blog.md` as a shared instruction. Include the actual
HTTPS URL, Compose directory/project, service and volume names, and renewal procedure. Explain that database credentials were
generated automatically and persist in the credentials volume; never include their values.
Record the two admin secret names only. Include **tested, copyable commands** for:

- Listing posts with `docker compose run --rm cli post list --post_status=publish --fields=ID,post_title,post_date --format=table`.
- Publishing an HTML article from standard input using the same successful command as the welcome
  article. Replace the admin ID placeholder with the real numeric ID. Keep `-T` when piping.
- Creating a draft (`--post_status=draft`), publishing it later (`post update ID --post_status=publish`),
  and retrieving its permalink.
- Reading a current technology RSS feed, verifying sources and dates, checking existing posts
  for duplicate source URLs, and publishing a short original summary with the source link and date.

Do not create the news task or a personal writing-style instruction yourself: the tutorial
teaches the user to create those with the real Settings editors. The CLI guide belongs here
because later sessions and tasks need it to operate the installed blog.

Finish with verified clickable links to `https://<public-IP>` and
`https://<public-IP>/wp-admin`, and tell the user to use the login they saved as secrets.
Point out **Settings → Instructions → blog** and the real Firewall rules. Never export a login,
password, token or TLS private key.

References: https://hub.docker.com/_/wordpress,
https://hub.docker.com/_/mariadb,
https://developer.wordpress.org/cli/commands/core/install/,
https://developer.wordpress.org/cli/commands/post/create/,
https://letsencrypt.org/2026/03/11/shorter-certs-certbot/$recipe$
WHERE id = '00000000-0000-4000-8000-00000000b001';
