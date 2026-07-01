-- Remove Business Center card and its turn-tracking column
ALTER TABLE games DROP COLUMN business_center_used_this_turn;

DELETE FROM cards WHERE card_type = 'BUSINESS_CENTER';
