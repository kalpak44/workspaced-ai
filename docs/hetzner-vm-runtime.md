# Next goal: Hetzner VM workspaces

Move workspaces from the current Kubernetes pod runtime to isolated Hetzner VMs, while keeping central management
on our existing infrastructure to minimize costs.

Central management is a new internal service, `workspaced-ai-vm-manager` (`apps/vm-manager`). It runs in k3s
behind a ClusterIP Service with no IngressRoute, so only the workspace API reaches it. VMs report ready and send
heartbeats to the public workspace API, which relays them to the VM manager.

Raw plan:

```mermaid
flowchart TB
    USER["User"]
    CF["Cloudflare DNS and HTTPS"]

    subgraph HOME["Existing Proxmox homelab"]
        TUNNEL["cloudflared → Traefik"]

        subgraph K3S["Existing k3s — Flux CD"]
            WEB["workspaced-ai-web"]
            API["workspaced-ai-api"]
            VMM["NEW: workspaced-ai-vm-manager<br/>Internal Service, no IngressRoute"]
        end

        PG["PostgreSQL<br/>Workspaces and VM state"]
        VAULT["Vault<br/>Workspace secrets"]
        BACKUP["RustFS<br/>Workspace backups"]

        TUNNEL --> WEB
        TUNNEL --> API
        WEB --> API
        API --> PG
        API -->|"Create, delete, restart VM · relay ready and heartbeat"| VMM
        VMM -->|"VM status events"| API
        VMM -->|"VM state"| PG
        API -->|"Scoped secret access"| VAULT
    end

    HC["Hetzner Cloud API"]

    subgraph VM["One Hetzner VM per workspace"]
        AGENT["NEW: workspace supervisor"]
        HARNESS["workspaced-ai-harness<br/>OpenCode and MCP tools"]
        NGINX["nginx"]
        APPS["Docker Compose<br/>WordPress · MariaDB · other apps"]
        DISK["Included VM disk"]

        AGENT -->|"Manage"| HARNESS
        HARNESS -->|"Request deployment"| AGENT
        AGENT -->|"Deploy and restart"| APPS
        NGINX --> APPS
        APPS --> DISK
        HARNESS --> DISK
    end

    USER --> CF
    CF -->|"ai.workspaced.online"| TUNNEL
    CF -->|"Workspace website — direct HTTPS"| NGINX
    VMM -->|"Create and delete VM"| HC
    HC --> VM
    AGENT -->|"Outbound authenticated connection · ready and heartbeat"| API
    AGENT -->|"Presigned backup upload"| BACKUP
```
