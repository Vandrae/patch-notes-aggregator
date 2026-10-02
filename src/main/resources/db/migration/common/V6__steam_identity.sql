-- Email/password accounts are replaced by Steam identities (Steam-only sign-in).
-- Existing rows were created by the email/password flow and have no SteamID, so they cannot be migrated.
-- This only ever held local development data; it is removed rather than left in an unusable state.
DELETE FROM watchlist_entry;
DELETE FROM app_user;

ALTER TABLE app_user DROP CONSTRAINT uq_app_user_email;
ALTER TABLE app_user DROP COLUMN email;
ALTER TABLE app_user DROP COLUMN password_hash;

ALTER TABLE app_user ADD COLUMN steam_id      BIGINT       NOT NULL;
ALTER TABLE app_user ADD COLUMN persona_name  VARCHAR(100) NOT NULL;
ALTER TABLE app_user ADD COLUMN avatar_url    VARCHAR(500);
ALTER TABLE app_user ADD COLUMN last_login_at DATETIME(6)  NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_steam_id UNIQUE (steam_id);
