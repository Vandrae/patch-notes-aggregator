-- Steam's small square "community icon" for each game, shown next to articles in the feed.
-- Stored as {appid}/{40-hex hash}.jpg, relative to an icon CDN base URL that is configuration, not data.
ALTER TABLE game ADD COLUMN icon_path VARCHAR(100);

-- Games whose store details were fetched before this column existed have no icon yet. Marking them pending makes the
-- details job fetch them again (chart games first, then recently updated ones, at Steam's pace of about one request
-- per three seconds). Until a game's turn comes, the UI shows its generated initials tile, so nothing breaks.
-- Games without a store page (no image) have no icon to find and are left alone.
UPDATE game SET metadata_synced_at = NULL WHERE image_path IS NOT NULL;
