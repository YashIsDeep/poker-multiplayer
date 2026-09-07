CREATE TABLE ranked_hand (
    room_id UUID NOT NULL,
    hand_id BIGINT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    big_blind BIGINT NOT NULL CHECK (big_blind = 10),
    participant_count INTEGER NOT NULL CHECK (participant_count BETWEEN 3 AND 6),
    PRIMARY KEY (room_id, hand_id)
);
CREATE TABLE ranked_result (
    room_id UUID NOT NULL,
    hand_id BIGINT NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id),
    net_chips BIGINT NOT NULL,
    PRIMARY KEY (room_id, hand_id, user_id),
    FOREIGN KEY (room_id, hand_id) REFERENCES ranked_hand(room_id, hand_id)
);
CREATE INDEX ranked_result_user ON ranked_result(user_id);
CREATE TABLE ranked_player_total (
    user_id UUID PRIMARY KEY REFERENCES app_user(id),
    net_chips BIGINT NOT NULL,
    hand_count BIGINT NOT NULL CHECK (hand_count > 0)
);
