CREATE TABLE users (
    id          VARCHAR PRIMARY KEY,
    nickname    VARCHAR(50)  NOT NULL,
    color_hue   INT          NOT NULL CHECK (color_hue >= 0 AND color_hue <= 360),
    initial_x   INT          NOT NULL CHECK (initial_x >= 0 AND initial_x <= 1000),
    initial_y   INT          NOT NULL CHECK (initial_y >= 0 AND initial_y <= 1000),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE friendships (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id   VARCHAR   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_friendship UNIQUE (user_id, friend_id),
    CONSTRAINT chk_no_self_friend CHECK (user_id != friend_id)
);

CREATE INDEX idx_friendships_friend_id ON friendships(friend_id);
