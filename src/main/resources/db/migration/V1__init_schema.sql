-- Static reference tables
-- Define the rules of the game; seeded once, never mutated.

CREATE TABLE cards
(
    id                 SERIAL PRIMARY KEY,
    card_type          VARCHAR2(50) NOT NULL,
    cost               INT         NOT NULL,
    income             INT         NOT NULL,
    color              VARCHAR2(20) NOT NULL,
    establishment_type VARCHAR2(20) NOT NULL,
    payment_source     VARCHAR2(20) NOT NULL,
    CONSTRAINT cards_card_type_unique UNIQUE (card_type),
    CONSTRAINT cards_card_type_check CHECK (card_type IN (
                                                          'WHEAT_FIELD', 'RANCH', 'FOREST', 'MINE', 'APPLE_ORCHARD',
                                                          'BAKERY', 'CONVENIENCE_STORE', 'CHEESE_FACTORY',
                                                          'FURNITURE_FACTORY',
                                                          'FRUIT_AND_VEGETABLE_MARKET', 'CAFE', 'FAMILY_RESTAURANT',
                                                          'STADIUM', 'TV_STATION', 'BUSINESS_CENTER'
        )),
    CONSTRAINT cards_color_check CHECK (color IN ('BLUE', 'GREEN', 'RED', 'PURPLE')),
    CONSTRAINT cards_establishment_type_check CHECK (establishment_type IN (
                                                                            'WHEAT', 'COW', 'GEAR', 'BREAD', 'FACTORY',
                                                                            'FRUIT', 'CUP', 'MAJOR'
        )),
    CONSTRAINT cards_payment_source_check CHECK (payment_source IN (
                                                                    'BANK', 'ACTIVE_PLAYER', 'ALL_PLAYERS',
                                                                    'CHOSEN_PLAYER', 'NONE'
        ))
);

-- Dice activation numbers per card (supports non-contiguous ranges like [1, 9, 10]).
CREATE TABLE card_activation_numbers
(
    card_id INT NOT NULL REFERENCES cards (id) ON DELETE CASCADE,
    number  INT NOT NULL,
    PRIMARY KEY (card_id, number)
);

CREATE TABLE landmarks
(
    id            SERIAL PRIMARY KEY,
    landmark_type VARCHAR2(50) NOT NULL,
    cost          INT         NOT NULL,
    CONSTRAINT landmarks_landmark_type_unique UNIQUE (landmark_type),
    CONSTRAINT landmarks_landmark_type_check CHECK (landmark_type IN (
                                                                      'TRAIN_STATION', 'SHOPPING_MALL',
                                                                      'AMUSEMENT_PARK', 'RADIO_TOWER'
        ))
);

-- Dynamic tables
-- Mutate during gameplay: users, games, players, and their state.
CREATE TABLE users
(
    id                 SERIAL PRIMARY KEY,
    username           VARCHAR2(50) NOT NULL,
    password_hash      VARCHAR2(60),
    session_token      VARCHAR2(255),
    total_wins         INT         NOT NULL DEFAULT 0,
    total_games_played INT         NOT NULL DEFAULT 0,
    CONSTRAINT users_username_unique UNIQUE (username),
    CONSTRAINT users_session_token_unique UNIQUE (session_token)
);

CREATE TABLE games
(
    id                      SERIAL PRIMARY KEY,
    status                  VARCHAR2(20) NOT NULL DEFAULT 'WAITING',
    host_user_id            INT         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    lobby_code              VARCHAR2(7)  NOT NULL,
    max_players             INT         NOT NULL DEFAULT 4,
    current_turn_index      INT         NOT NULL DEFAULT 0,
    turn_phase              VARCHAR2(20) NOT NULL DEFAULT 'ROLL_DICE',
    last_dice_roll          INT,
    round_number            INT         NOT NULL DEFAULT 1,
    has_purchased_this_turn BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT games_lobby_code_unique UNIQUE (lobby_code),
    CONSTRAINT games_status_check CHECK (status IN ('WAITING', 'IN_PROGRESS', 'FINISHED')),
    CONSTRAINT games_turn_phase_check CHECK (turn_phase IN ('ROLL_DICE', 'RESOLVE_EFFECTS', 'BUY_OR_BUILD', 'END_TURN'))
);

-- A user inside a specific game. last_seen_at is a timestamp (epoch millis)
-- rather than a boolean flag to avoid stale "isConnected = true" rows after
-- crashes or dropped websockets.
CREATE TABLE players
(
    id           SERIAL PRIMARY KEY,
    game_id      INT NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id      INT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    turn_order   INT NOT NULL,
    coins        INT NOT NULL DEFAULT 3,
    last_seen_at BIGINT,
    CONSTRAINT players_game_user_unique UNIQUE (game_id, user_id),
    CONSTRAINT players_game_turn_order_unique UNIQUE (game_id, turn_order)
);

CREATE TABLE player_cards
(
    player_id INT NOT NULL REFERENCES players (id) ON DELETE CASCADE,
    card_id   INT NOT NULL REFERENCES cards (id) ON DELETE RESTRICT,
    quantity  INT NOT NULL DEFAULT 1,
    PRIMARY KEY (player_id, card_id)
);

CREATE TABLE player_landmarks
(
    player_id   INT     NOT NULL REFERENCES players (id) ON DELETE CASCADE,
    landmark_id INT     NOT NULL REFERENCES landmarks (id) ON DELETE RESTRICT,
    is_built    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (player_id, landmark_id)
);

-- Per-game card supply. Each game has its own marketplace.
CREATE TABLE game_marketplace
(
    game_id            INT NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    card_id            INT NOT NULL REFERENCES cards (id) ON DELETE RESTRICT,
    quantity_available INT NOT NULL,
    PRIMARY KEY (game_id, card_id)
);
