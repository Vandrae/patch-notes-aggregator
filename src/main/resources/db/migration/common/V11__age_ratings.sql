-- ESRB age rating per game (E, E10, T, M, AO; see the AgeRating enum), NULL when Steam shows none. Many games, such as
-- free-to-play and Valve titles, have no ESRB rating at all.
ALTER TABLE game ADD COLUMN age_rating VARCHAR(20);
CREATE INDEX idx_game_age_rating ON game (age_rating);

-- Details fetched so far (a few games, since V10 only just re-queued everything) lack it: fetch them again.
UPDATE game SET metadata_synced_at = NULL WHERE metadata_synced_at IS NOT NULL;
