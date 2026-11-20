-- Keep the original migration unchanged for databases that have already applied it.
-- The recipe is linked to Pavel's verified account at his next sign-in.
UPDATE recipe
SET author_first_name = 'Pavel', author_last_name = 'Usanli',
    content = $recipe$Set up a public WordPress blog ready for scheduled news posts.

Required secrets: `WORDPRESS_ADMIN_USER`, `WORDPRESS_ADMIN_PASSWORD` and `WORDPRESS_DB_PASSWORD`. The guided tutorial collects the first two and generates the database password. Outside the tutorial, ask the user to add any missing values in workspace Settings → Secrets and restart. Check that each environment variable is set without printing it. Never paste a credential into chat, a command literal, an instruction file, or an outbox download.

Applying this recipe authorizes publishing on port 80 of this machine's public IP address. Use `http://$WORKSPACE_IP` (or the address from `firewall list` if unset), never the platform hostname. This is a temporary site over unencrypted HTTP; say so and advise using unique trial credentials. Choose a site title and tagline without further questions.

1. In `~/project/blog`, create a Docker Compose project with the official `wordpress:apache`, `mariadb:lts`, and `wordpress:cli` images. Keep WordPress and database data in named Docker volumes; a bind mount from the harness is not necessarily a path on the Docker daemon's host. Set `restart: unless-stopped` on the web and database services. Publish only the web service on `80:80`; do not publish the database or CLI.
2. Use database name/user `wordpress`. Reference the `WORDPRESS_DB_PASSWORD` environment variable using required-value interpolation in Compose for the database user's password, the database root password, and the web and CLI `WORDPRESS_DB_PASSWORD`. Provide the same `WORDPRESS_DB_HOST`, name, user and password to web and CLI. Keep only environment variable references in Compose, never expanded values or a secrets `.env`. Do not run `docker compose config`, print container environments, enable shell tracing, or display commands with expanded credentials.
3. Wait for the database to be healthy and WordPress files to be initialized. Give the CLI the same WordPress volume, working directory `/var/www/html`, network and database environment as the web service, with a compatible UID/GID (33:33 for the Apache image). Configure its entrypoint explicitly as `wp` and run it only on demand. Open TCP port 80 using `firewall open 80`.
4. Check `wp core is-installed` before installing, so retries keep existing content. Install using the admin username and password from the two secrets. Pass the password by standard input with WP-CLI's `--prompt=admin_password`, not as a command argument or a file. Use a local placeholder admin email such as `admin@example.invalid` and `--skip-email`; the user can change it later. The site URL is `http://$WORKSPACE_IP`.
5. Write an original welcome post and remove the default sample post and page. Check that the public home page and post answer successfully at the machine's IP address. If a step fails, fix it and repeat the check; do not claim a working deployment until it passes.
6. Add `blog.md` under `~/.config/workspaced/instructions/`, the workspace's shared instruction directory. Record the Compose directory, volume and service names, public URL, and tested CLI commands to list and publish posts (for example `docker compose run --rm cli post create --post_status=publish ...`). Record secret names only. Explain how later scheduled sessions can read a current technology RSS feed, avoid duplicate source URLs, and publish an original summary with the source link and date. Do not create a scheduled task yet; the tutorial does that next.
7. Reply with clickable links to the public blog and `http://$WORKSPACE_IP/wp-admin`. Tell the user to use the credentials they saved as secrets and that admin sign-in over HTTP is unencrypted. Do not reveal or export any credential.

References: https://hub.docker.com/_/wordpress and https://developer.wordpress.org/cli/commands/core/install/
$recipe$
WHERE id = '00000000-0000-4000-8000-00000000b001';
