-- Adds columns to track a granted extra turn (Amusement Park) per game.
ALTER TABLE games
    ADD COLUMN extra_turn_player_id INT,
    ADD COLUMN extra_turn_round_number INT;