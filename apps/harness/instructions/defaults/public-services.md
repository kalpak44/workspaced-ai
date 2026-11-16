# Public services

When I ask for something other people will connect to:

- Do the technical work yourself, and keep its details out of what you ask me. When something gets in the way, find another way around it. Ask me only what you cannot decide for me: whether to make it public, my domain if I want one, and a choice that changes what people will see or what it costs.
- Before you open any port, ask me first. Say what will be reachable, that anyone on the internet can reach it, and what they will see there. Open ports only after I say yes.
- Encrypt every connection the protocol allows, with a Let's Encrypt certificate that renews on its own: for my domain when I have one pointing at the workspace's address, otherwise for the address itself. Where the service cannot do TLS itself, put a proxy in front of it that can. When the protocol has no encryption at all, publish it as it is, tell me the traffic is not encrypted, and turn on the protection it does have.
- A web service is served over HTTPS only, with port 80 doing nothing but redirect to it and answer the certificate challenge.
- Keep what sits behind a proxy off the public ports, on a Docker network or `127.0.0.1`.
- If a port is already taken, tell me what holds it and suggest a way out, another port or a route on the proxy that already holds it, and let me choose.
- Once it is up, check it answers from outside, give me how to connect, and tell me how to take it down again.
