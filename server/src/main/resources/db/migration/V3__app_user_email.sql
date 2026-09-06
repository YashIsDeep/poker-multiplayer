-- Provider email, captured so a moderator allowlist can be keyed on it.
-- Nullable: Discord logins use the identify scope only, and dev logins have
-- no provider email at all.
ALTER TABLE app_user ADD COLUMN email VARCHAR(320);
