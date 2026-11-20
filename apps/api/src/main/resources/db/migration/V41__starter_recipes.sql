-- The recipes a new account finds in the store, and the one the guided tour applies. They are
-- authored by an account nobody can sign in as: no Auth0 subject starts with "system|". Their ids
-- are fixed because the web app's tour names the WordPress one.
INSERT INTO app_user (id, idp_subject, role)
VALUES ('00000000-0000-4000-8000-000000000a01', 'system|workspaced', 'USER')
ON CONFLICT DO NOTHING;

INSERT INTO recipe (id, author_id, author_first_name, author_last_name, title, summary, category,
                    tags, visibility, published_at, content)
VALUES
('00000000-0000-4000-8000-00000000b001', '00000000-0000-4000-8000-000000000a01', 'workspaced', '',
 'WordPress blog', 'A public WordPress blog with a first post, ready for scheduled posting.',
 'websites', '{wordpress,blog,docker}', 'PUBLIC', now(),
$md$Set up a WordPress blog anyone can visit, ready for new posts to be published from a scheduled task.

Do not ask me anything: choose every name, title and setting yourself, and work through to the end. Applying this recipe is my go-ahead to make the blog public on port 80 of this workspace's address. Without a domain of mine in `domains`, serve it over plain HTTP and tell me it is not encrypted.

1. Run WordPress and MariaDB with Docker Compose in `~/project/blog`, with `restart: unless-stopped`, WordPress published on port 80 and the database on a private network only. Generate the database passwords and keep them in that folder's `.env`, never in the chat.
2. `firewall open 80`, then install WordPress with the `wordpress:cli` image on the same network: the site URL `http://$WORKSPACE_IP`, the title "My workspaced blog", an admin user `admin` with a generated password, and a tagline of your choice.
3. Write a friendly first post welcoming readers, with a featured image from https://picsum.photos, and delete the sample "Hello world!" post and page.
4. Check the home page answers on `http://$WORKSPACE_IP` from outside.
5. Add an instruction file `blog.md` saying where the blog lives and how to publish a post with `wordpress:cli` (`docker compose run --rm cli wp post create … --post_status=publish`), so a later session or a scheduled task can post without working it out again.
6. Save the admin sign-in in `outbox/wordpress-admin.txt` and link it, then tell me the blog's address and its admin address, `http://$WORKSPACE_IP/wp-admin`.
$md$),

('00000000-0000-4000-8000-00000000b002', '00000000-0000-4000-8000-000000000a01', 'workspaced', '',
 'Photo gallery', 'A public photo gallery page with a grid, a lightbox and sample photos.',
 'websites', '{gallery,photos,static-site}', 'PUBLIC', now(),
$md$Build a photo gallery website anyone can visit.

Do not ask me anything: choose the design and the sample photos yourself. Applying this recipe is my go-ahead to make the gallery public on port 8081 of this workspace's address, over plain HTTP; tell me it is not encrypted.

1. In `~/project/gallery`, write a single static page: a title, a responsive grid of thumbnails and a lightbox that opens a photo full size and moves between photos with the arrow keys.
2. Download twelve sample photos from https://picsum.photos into `photos/`, with a short caption for each.
3. Serve the folder with the `nginx:alpine` image on port 8081 with `restart: unless-stopped`, then `firewall open 8081`.
4. Check it answers on `http://$WORKSPACE_IP:8081` from outside.
5. Add an instruction file `gallery.md` saying where the photos live and that adding a photo means adding it to `photos/` and to the page.
6. Tell me the address, and that I can attach my own photos in this chat to have them added.
$md$),

('00000000-0000-4000-8000-00000000b003', '00000000-0000-4000-8000-000000000a01', 'workspaced', '',
 'Personal links page', 'A one-page link-in-bio site with your name, a short bio and your links.',
 'websites', '{links,profile,static-site}', 'PUBLIC', now(),
$md$Build a personal "link in bio" page: a name, a one-line bio, a picture and a list of links, as a single page anyone can visit.

Ask me once, in a single message, for my name, a one-line bio and the links I want on it, and offer to fill in placeholders I can change later. Applying this recipe is my go-ahead to make the page public on port 8082 of this workspace's address, over plain HTTP; tell me it is not encrypted.

1. In `~/project/links`, write one static page that looks good on a phone first: a round picture, the name, the bio and a button for each link.
2. Serve it with the `nginx:alpine` image on port 8082 with `restart: unless-stopped`, then `firewall open 8082`.
3. Check it answers on `http://$WORKSPACE_IP:8082` from outside.
4. Add an instruction file `links.md` saying where the page lives, so a later session can change a link.
5. Tell me the address.
$md$),

('00000000-0000-4000-8000-00000000b004', '00000000-0000-4000-8000-000000000a01', 'workspaced', '',
 'Morning news digest', 'A short summary of the day''s headlines, written every morning by a scheduled task.',
 'automation', '{news,rss,tasks}', 'PUBLIC', now(),
$md$Every morning, read a few news feeds and write a short digest of the stories that matter, as a page I can open.

Ask me once which topics I care about, and offer technology and world news if I have no preference.

1. Pick three or four RSS feeds for those topics and write a small script in `~/project/digest` that fetches them and prints each item's title, link and summary.
2. Write today's digest now: at most ten stories, one or two sentences each, grouped by topic, as `~/project/digest/today.html`, and copy it to `outbox/` with a link so I can open it.
3. Add a scheduled task, `morning-digest`, that runs every day at 07:00 on my clock and writes the next digest the same way, keeping the previous ones in `~/project/digest/archive/`.
4. Tell me when the next one is written, and that I can change the time or the topics in the workspace's settings under Tasks.
$md$),

('00000000-0000-4000-8000-00000000b005', '00000000-0000-4000-8000-000000000a01', 'workspaced', '',
 'Website uptime monitor', 'Uptime Kuma watching your websites, with a status page you can share.',
 'devops', '{monitoring,uptime,status-page}', 'PUBLIC', now(),
$md$Set up Uptime Kuma to watch websites and show whether they are up, with a public status page.

Ask me once which websites to watch, and offer to start with https://workspaced.online if I have none in mind. Applying this recipe is my go-ahead to make it public on port 3001 of this workspace's address, over plain HTTP; tell me it is not encrypted.

1. Run the `louislam/uptime-kuma` image with Docker in `~/project/uptime`, its data in a volume, `restart: unless-stopped`, published on port 3001, then `firewall open 3001`.
2. Create the admin account with a generated password, add an HTTP monitor for each website checked every minute, and a public status page listing them.
3. Check both the dashboard and the status page answer on `http://$WORKSPACE_IP:3001` from outside.
4. Add an instruction file `uptime.md` saying where it runs and how to add a monitor.
5. Save the admin sign-in in `outbox/uptime-kuma-admin.txt` and link it, then tell me both addresses.
$md$);
