-- TV Station now steals from a random opponent automatically; AWAIT_TV_TARGET phase is gone
UPDATE games SET turn_phase = 'BUY_OR_BUILD' WHERE turn_phase = 'AWAIT_TV_TARGET';

ALTER TABLE games DROP CONSTRAINT games_turn_phase_check;
ALTER TABLE games ADD CONSTRAINT games_turn_phase_check
    CHECK (turn_phase IN ('ROLL_DICE', 'RESOLVE_EFFECTS', 'BUY_OR_BUILD', 'END_TURN'));
