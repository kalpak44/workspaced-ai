-- ShedLock's table: one row per scheduled job, held by whichever API instance is running it.
-- Plain TIMESTAMP, as ShedLock documents: it writes UTC wall-clock times from the database's own
-- clock, which a TIMESTAMPTZ column would shift by the session's time zone.
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
