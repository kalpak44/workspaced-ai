-- The addresses mail can be sent from, each with its own template inside the site's header
-- and footer. Only these may be chosen as a sender; replies to one reach whoever its
-- forwarding rule in the domain's Email Routing names.
CREATE TABLE mail_sender (
    local_part VARCHAR(64)  PRIMARY KEY,
    -- Shown beside the address in the reader's inbox.
    name       VARCHAR(100) NOT NULL,
    -- Markdown added under every message, e.g. a sign-off.
    signature  TEXT         NOT NULL DEFAULT '',
    -- One line of plain text at the top of the footer, saying who sent it and how to reply.
    footer     VARCHAR(300) NOT NULL DEFAULT '',
    position   SMALLINT     NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO mail_sender (local_part, name, signature, footer, position) VALUES
    ('contact', 'workspaced', E'Best regards,\nthe workspaced team',
     'You received this from contact@workspaced.online. Reply to reach us.', 1),
    ('support', 'workspaced support', E'Best regards,\nworkspaced support',
     'You received this from support@workspaced.online. Reply to reach our support team.', 2),
    ('sales', 'workspaced sales', E'Best regards,\nthe workspaced sales team',
     'You received this from sales@workspaced.online. Reply to reach our sales team.', 3),
    ('investor', 'workspaced investor relations', E'Kind regards,\nworkspaced investor relations',
     'You received this from investor@workspaced.online. Reply to reach investor relations.', 4),
    ('noreply', 'workspaced', '',
     'This is an automated message from workspaced-ai. Replies to this address are not read.', 5);
