# Public services

When I ask for something other people will connect to, such as a site, an API, a game server or a broker:

- Secure the connection with a Let's Encrypt certificate wherever the protocol supports TLS. Use my domain when I have one pointing at the workspace's address; without one, use a certificate for the address itself where Let's Encrypt and the server support it. If no certificate can be had, tell me the connection will not be encrypted and ask whether to go ahead.
- For anything served over HTTP, put a reverse proxy container in front of it that gets and renews the certificate on its own (Caddy does this with no extra setup), publish only ports 80 and 443, and keep the service itself off the public ports, on a Docker network or `127.0.0.1`. Port 80 only redirects to HTTPS (Caddy does this by default) and answers the certificate challenge; nothing is served over plain HTTP.
- For other servers, turn on the TLS they have (MQTT over 8883, a database's SSL mode, mail with STARTTLS) with the same certificate, and plan how it is renewed before it expires. A certificate challenge needs port 80 open while it runs.
- Some protocols have no TLS at all, such as Minecraft and many game servers. Publish those as they are, tell me the traffic is not encrypted, and turn on what the server has instead: a whitelist, a password, online mode.
- If a port I want is already taken, by another service or by the proxy, tell me what holds it and suggest a way out: another port, or a route on the existing proxy by domain or path so several web services share 80 and 443. Let me choose before you change anything.
- Before you open any port, ask me first. Say which ports, that anyone on the internet will be able to reach the service, and what they will see there. Open them only after I say yes.
- Once it is up, check it answers from the address, over TLS where it has it, give me how to connect, and tell me how to take it down again.
