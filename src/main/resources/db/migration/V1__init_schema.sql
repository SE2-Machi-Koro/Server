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

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 1 FROM cards WHERE card_type = 'WHEAT_FIELD'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 2 FROM cards WHERE card_type = 'RANCH'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 5 FROM cards WHERE card_type = 'FOREST'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 9 FROM cards WHERE card_type = 'MINE'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 10 FROM cards WHERE card_type = 'APPLE_ORCHARD'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 2 FROM cards WHERE card_type = 'BAKERY'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 3 FROM cards WHERE card_type = 'BAKERY'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 4 FROM cards WHERE card_type = 'CONVENIENCE_STORE'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 7 FROM cards WHERE card_type = 'CHEESE_FACTORY'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 8 FROM cards WHERE card_type = 'FURNITURE_FACTORY'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 11 FROM cards WHERE card_type = 'FRUIT_AND_VEGETABLE_MARKET'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 12 FROM cards WHERE card_type = 'FRUIT_AND_VEGETABLE_MARKET'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 3 FROM cards WHERE card_type = 'CAFE'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 9 FROM cards WHERE card_type = 'FAMILY_RESTAURANT'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 10 FROM cards WHERE card_type = 'FAMILY_RESTAURANT'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 6 FROM cards WHERE card_type = 'STADIUM'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 6 FROM cards WHERE card_type = 'TV_STATION'
ON CONFLICT (card_id, number) DO NOTHING;

INSERT INTO card_activation_numbers (card_id, number)
SELECT id, 6 FROM cards WHERE card_type = 'BUSINESS_CENTER'
ON CONFLICT (card_id, number) DO NOTHING;
