CREATE TABLE devices (
    device_id     VARCHAR(128) PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every data point, regardless of source, lands here.
-- Re-sending the same (source, category, sub_category, started_at) updates the row instead of
-- duplicating it, so the phone can safely re-collect and re-upload overlapping windows.
CREATE TABLE events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      VARCHAR(64)   NOT NULL,
    category     VARCHAR(32)   NOT NULL,
    sub_category VARCHAR(160)  NOT NULL DEFAULT '',
    value        NUMERIC(12,4) NOT NULL,
    unit         VARCHAR(16)   NOT NULL,
    started_at   TIMESTAMPTZ   NOT NULL,
    ended_at     TIMESTAMPTZ,
    source       VARCHAR(32)   NOT NULL,
    metadata     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    synced_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT events_dedupe UNIQUE (user_id, source, category, sub_category, started_at)
);

CREATE INDEX events_user_category_time ON events (user_id, category, started_at);

CREATE TABLE daily_aggregates (
    user_id         VARCHAR(64)  NOT NULL,
    date            DATE         NOT NULL,
    sleep_hours     NUMERIC(4,2),
    sleep_start     TIMESTAMPTZ,
    sleep_end       TIMESTAMPTZ,
    sleep_source    VARCHAR(32),
    screen_minutes  INTEGER      NOT NULL DEFAULT 0,
    unlocks         INTEGER      NOT NULL DEFAULT 0,
    usage_minutes   INTEGER      NOT NULL DEFAULT 0,
    steps           INTEGER,
    top_app         VARCHAR(160),
    top_app_label   VARCHAR(160),
    top_app_minutes INTEGER,
    focus_sessions  INTEGER      NOT NULL DEFAULT 0,
    focus_minutes   INTEGER      NOT NULL DEFAULT 0,
    active_minutes  INTEGER      NOT NULL DEFAULT 0,
    mood_score      NUMERIC(3,2),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, date)
);

CREATE TABLE insights (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       VARCHAR(64)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,           -- weekly | alert | notice
    generated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    week_start    DATE,
    alert_key     VARCHAR(96),
    pattern       TEXT         NOT NULL,
    data_evidence TEXT         NOT NULL,
    action        TEXT         NOT NULL,
    category      VARCHAR(32)  NOT NULL,
    confidence    VARCHAR(8)   NOT NULL,
    dismissed_at  TIMESTAMPTZ,
    actioned_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX insights_alert_key ON insights (user_id, alert_key) WHERE alert_key IS NOT NULL;
CREATE INDEX insights_user_generated ON insights (user_id, generated_at DESC);
