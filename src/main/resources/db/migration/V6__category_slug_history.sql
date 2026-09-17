CREATE TABLE category_slug_aliases (
    slug varchar(160) PRIMARY KEY CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    category_id varchar(64) REFERENCES categories(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE INDEX idx_category_slug_aliases_category ON category_slug_aliases(category_id);
