-- Add rerolled_this_turn flag that records whether the active turn has consumed a reroll
ALTER TABLE games ADD COLUMN rerolled_this_turn BOOLEAN NOT NULL DEFAULT FALSE;