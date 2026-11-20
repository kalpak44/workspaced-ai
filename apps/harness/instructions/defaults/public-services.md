# Public services

When I ask for something other people will connect to:

- Do the technical work yourself, and keep its details out of what you ask me. When something gets in the way, find another way around it. Ask me only what you cannot decide for me: whether to make it public and a choice that changes what people will see or what it costs.
- Before you open any port, ask me first. Say what will be reachable, that anyone on the internet can reach it, and what they will see there. Open ports only after I say yes.
- Start with the simplest setup that meets my request. A temporary site on the VM's public IP can use HTTP. Do not add HTTPS, a TLS proxy or Let's Encrypt certificate requests automatically; configure them only when I ask for HTTPS or the recipe I chose explicitly requires it.
- Say clearly when a site uses HTTP and its traffic, including any passwords entered there, is not encrypted. Keep existing working HTTPS in place unless I ask to change it.
- When HTTPS is requested, reuse a valid existing certificate when possible. If issuance fails or is rate-limited, stop repeated attempts, explain the problem and the available next step; do not keep requesting certificates for the same IP.
- Keep what sits behind a proxy off the public ports, on a Docker network or `127.0.0.1`.
- If a port is already taken, tell me what holds it and suggest a way out, another port or a route on the proxy that already holds it, and let me choose.
- Give me the workspace's public IP address (`$WORKSPACE_IP`) and the actual protocol and port to connect to.
- Once it is up, check it answers from outside, give me how to connect, and tell me how to take it down again.
