-- BIGINT denominators are at most 2^63-1: distinct rational rates differ by
-- more than 1 / (2^63-1)^2. Forty decimal places preserve their exact ordering,
-- including rates whose twelve-place display values are identical.
ALTER TABLE ranked_player_total ADD COLUMN qualified BOOLEAN
    GENERATED ALWAYS AS (hand_count >= 1000);
ALTER TABLE ranked_player_total ADD COLUMN rank_rate NUMERIC(80,40)
    GENERATED ALWAYS AS (CAST(net_chips AS NUMERIC(80,40)) / hand_count);
CREATE INDEX ranked_player_board ON ranked_player_total
    (qualified, rank_rate DESC, hand_count DESC, user_id);
