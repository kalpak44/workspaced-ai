-- A user's own domain, or one name under it, pointed at a workspace's VM. Where the VM is
-- reached is copied in when it is attached, so the records shown and the ones checked never
-- change under the user; a deleted workspace's go with it.
CREATE TABLE workspace_domain (
    id            UUID PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    -- The zone, e.g. example.com, and the name under it, '@' for the zone itself.
    domain        VARCHAR(253) NOT NULL,
    host          VARCHAR(253) NOT NULL,
    mode          VARCHAR(20)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    -- The user's DNS provider, when it applies our template itself.
    provider_name VARCHAR(255),
    connect_url   VARCHAR(2048),
    vm_id         UUID         NOT NULL,
    ipv4          VARCHAR(45)  NOT NULL,
    ipv6          VARCHAR(45),
    vm_hostname   VARCHAR(253) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    verified_at   TIMESTAMPTZ,
    UNIQUE (workspace_id, domain, host)
);

-- Anyone may claim a name while it is pending; only the one its DNS points at becomes active,
-- so claiming a domain someone else owns blocks nobody.
CREATE UNIQUE INDEX workspace_domain_active ON workspace_domain (domain, host)
    WHERE status = 'ACTIVE';
