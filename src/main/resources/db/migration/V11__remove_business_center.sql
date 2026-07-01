-- Clear Business Center references before deleting the card (RESTRICT FK would block otherwise)
DELETE FROM player_cards WHERE card_id = (SELECT id FROM cards WHERE card_type = 'BUSINESS_CENTER');
DELETE FROM game_marketplace WHERE card_id = (SELECT id FROM cards WHERE card_type = 'BUSINESS_CENTER');
DELETE FROM cards WHERE card_type = 'BUSINESS_CENTER';
ALTER TABLE games DROP COLUMN business_center_used_this_turn;
