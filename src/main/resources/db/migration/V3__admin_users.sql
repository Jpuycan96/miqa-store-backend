CREATE TABLE admin_users (
 id varchar(64) PRIMARY KEY,
 username varchar(100) NOT NULL UNIQUE CHECK (username ~ '^[a-zA-Z0-9._-]{3,100}$'),
 password_hash varchar(100) NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TRIGGER admin_users_updated BEFORE UPDATE ON admin_users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
