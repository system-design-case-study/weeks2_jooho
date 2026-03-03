CREATE KEYSPACE IF NOT EXISTS nearby_friends
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE nearby_friends;

CREATE TABLE IF NOT EXISTS location_history (
    user_id   TEXT,
    timestamp TIMESTAMP,
    x         INT,
    y         INT,
    ws_server TEXT,
    PRIMARY KEY (user_id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);
