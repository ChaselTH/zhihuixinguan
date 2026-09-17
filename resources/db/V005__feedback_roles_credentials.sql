ALTER TABLE users ADD COLUMN IF NOT EXISTS initial_password_ciphertext CLOB;
ALTER TABLE access_requests ADD COLUMN IF NOT EXISTS requested_role VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_access_requested_role ON access_requests(requested_role);
