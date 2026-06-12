-- Tracks an outstanding (uncaught) Insider Trading cheat per player (issue #361).
-- Row present = that player has cheated and has not yet been caught.
-- Reporting is idempotent (one row per player); a successful accusation deletes the row.
-- ON DELETE CASCADE so flags are removed automatically when a player/game is deleted.
CREATE TABLE cheat_flags
(
    player_id INT NOT NULL PRIMARY KEY REFERENCES players (id) ON DELETE CASCADE
);
