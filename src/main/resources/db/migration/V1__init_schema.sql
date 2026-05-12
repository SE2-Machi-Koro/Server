CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(60),
    session_token VARCHAR(255) UNIQUE,
    total_wins INTEGER NOT NULL DEFAULT 0,
    total_games_played INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cards (
    id SERIAL PRIMARY KEY,
    card_type VARCHAR(50) NOT NULL UNIQUE,
    cost INTEGER NOT NULL,
    income INTEGER NOT NULL,
    color VARCHAR(20) NOT NULL,
    establishment_type VARCHAR(20) NOT NULL,
    payment_source VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS landmarks (
    id SERIAL PRIMARY KEY,
    landmark_type VARCHAR(50) NOT NULL UNIQUE,
    cost INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS games (
    id SERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    host_user_id INTEGER NOT NULL,
    lobby_code VARCHAR(7) NOT NULL UNIQUE,
    max_players INTEGER NOT NULL DEFAULT 4,
    current_turn_index INTEGER NOT NULL DEFAULT 0,
    turn_phase VARCHAR(20) NOT NULL DEFAULT 'ROLL_DICE',
    last_dice_roll INTEGER,
    round_number INTEGER NOT NULL DEFAULT 1,
    has_purchased_this_turn BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_games_host_user_id FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS players (
    id SERIAL PRIMARY KEY,
    game_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    turn_order INTEGER NOT NULL,
    coins INTEGER NOT NULL DEFAULT 3,
    last_seen_at BIGINT,
    CONSTRAINT uq_players_game_user UNIQUE (game_id, user_id),
    CONSTRAINT uq_players_game_turn UNIQUE (game_id, turn_order),
    CONSTRAINT fk_players_game_id FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
    CONSTRAINT fk_players_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS card_activation_numbers (
    card_id INTEGER NOT NULL,
    number INTEGER NOT NULL,
    PRIMARY KEY (card_id, number),
    CONSTRAINT fk_card_activation_numbers_card_id FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS player_cards (
    player_id INTEGER NOT NULL,
    card_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (player_id, card_id),
    CONSTRAINT fk_player_cards_player_id FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_player_cards_card_id FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS player_landmarks (
    player_id INTEGER NOT NULL,
    landmark_id INTEGER NOT NULL,
    is_built BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (player_id, landmark_id),
    CONSTRAINT fk_player_landmarks_player_id FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT fk_player_landmarks_landmark_id FOREIGN KEY (landmark_id) REFERENCES landmarks(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS game_marketplace (
    game_id INTEGER NOT NULL,
    card_id INTEGER NOT NULL,
    quantity_available INTEGER NOT NULL,
    PRIMARY KEY (game_id, card_id),
    CONSTRAINT fk_game_marketplace_game_id FOREIGN KEY (game_id) REFERENCES games(id) ON DELETE CASCADE,
    CONSTRAINT fk_game_marketplace_card_id FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT
);

INSERT INTO landmarks (landmark_type, cost)
VALUES
    ('TRAIN_STATION', 4),
    ('SHOPPING_MALL', 10),
    ('AMUSEMENT_PARK', 16),
    ('RADIO_TOWER', 22)
ON CONFLICT (landmark_type) DO NOTHING;

-- Sonar sql:S1192 (duplicated string literals) is suppressed for this file via
-- build.gradle.kts (sonar.issue.ignore.multicriteria). The repeated color,
-- establishment_type and payment_source literals below are intrinsic to the
-- immutable seed data of the V1 baseline; normalizing them into lookup tables
-- would alter the V1 schema (a Flyway anti-pattern) and is deferred.
INSERT INTO cards (card_type, cost, income, color, establishment_type, payment_source)
VALUES
    ('WHEAT_FIELD', 1, 1, 'BLUE', 'WHEAT', 'BANK'),
    ('RANCH', 1, 1, 'BLUE', 'COW', 'BANK'),
    ('FOREST', 3, 1, 'BLUE', 'GEAR', 'BANK'),
    ('MINE', 6, 5, 'BLUE', 'GEAR', 'BANK'),
    ('APPLE_ORCHARD', 3, 3, 'BLUE', 'WHEAT', 'BANK'),
    ('BAKERY', 1, 1, 'GREEN', 'BREAD', 'BANK'),
    ('CONVENIENCE_STORE', 2, 3, 'GREEN', 'BREAD', 'BANK'),
    ('CHEESE_FACTORY', 5, 3, 'GREEN', 'FACTORY', 'BANK'),
    ('FURNITURE_FACTORY', 3, 3, 'GREEN', 'FACTORY', 'BANK'),
    ('FRUIT_AND_VEGETABLE_MARKET', 2, 2, 'GREEN', 'FRUIT', 'BANK'),
    ('CAFE', 2, 1, 'RED', 'CUP', 'ACTIVE_PLAYER'),
    ('FAMILY_RESTAURANT', 3, 2, 'RED', 'CUP', 'ACTIVE_PLAYER'),
    ('STADIUM', 6, 2, 'PURPLE', 'MAJOR', 'ALL_PLAYERS'),
    ('TV_STATION', 7, 0, 'PURPLE', 'MAJOR', 'CHOSEN_PLAYER'),
    ('BUSINESS_CENTER', 8, 0, 'PURPLE', 'MAJOR', 'NONE')
ON CONFLICT (card_type) DO NOTHING;

-- Seed activation numbers in a single statement: each (card_type, number) pair
-- below maps to one row in card_activation_numbers via a join on cards.card_type.
-- Idempotent thanks to ON CONFLICT DO NOTHING on the composite PK.
INSERT INTO card_activation_numbers (card_id, number)
SELECT c.id, v.number
FROM cards c
JOIN (
    VALUES
        ('WHEAT_FIELD', 1),
        ('RANCH', 2),
        ('FOREST', 5),
        ('MINE', 9),
        ('APPLE_ORCHARD', 10),
        ('BAKERY', 2),
        ('BAKERY', 3),
        ('CONVENIENCE_STORE', 4),
        ('CHEESE_FACTORY', 7),
        ('FURNITURE_FACTORY', 8),
        ('FRUIT_AND_VEGETABLE_MARKET', 11),
        ('FRUIT_AND_VEGETABLE_MARKET', 12),
        ('CAFE', 3),
        ('FAMILY_RESTAURANT', 9),
        ('FAMILY_RESTAURANT', 10),
        ('STADIUM', 6),
        ('TV_STATION', 6),
        ('BUSINESS_CENTER', 6)
) AS v(card_type, number) ON c.card_type = v.card_type
ON CONFLICT (card_id, number) DO NOTHING;
