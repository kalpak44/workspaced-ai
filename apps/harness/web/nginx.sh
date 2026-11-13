# The workspace's web server, written by the web server setting; turning the setting off
# deletes it. nginx reads ~/.config/workspaced/web/nginx.conf.
mkdir -p /tmp/nginx
exec nginx -e stderr -c "$HOME/.config/workspaced/web/nginx.conf" -g 'daemon off;'
