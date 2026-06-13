-- Tracks whether the pending Amusement Park extra turn has already been consumed this round
ALTER TABLE games
    ADD COLUMN extra_turn_consumed BOOLEAN NOT NULL DEFAULT FALSE;