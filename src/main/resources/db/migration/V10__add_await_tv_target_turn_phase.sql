-- Allow the AWAIT_TV_TARGET turn phase, used by the TV Station steal interaction
-- round-trip where the active player picks the opponent to steal from (issue #433).
ALTER TABLE games DROP CONSTRAINT games_turn_phase_check;
ALTER TABLE games ADD CONSTRAINT games_turn_phase_check
    CHECK (turn_phase IN ('ROLL_DICE', 'RESOLVE_EFFECTS', 'AWAIT_TV_TARGET', 'BUY_OR_BUILD', 'END_TURN'));
