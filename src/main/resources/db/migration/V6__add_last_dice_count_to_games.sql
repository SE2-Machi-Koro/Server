-- Persists the dice count from the initial roll so rerolls use the server-side value
ALTER TABLE games
    ADD COLUMN last_dice_count INT;