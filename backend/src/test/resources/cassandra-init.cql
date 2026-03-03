CREATE KEYSPACE IF NOT EXISTS nearby_friends
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS nearby_friends.location_history (
    user_id text,
    timestamp timestamp,
    x int,
    y int,
    ws_server text,
    PRIMARY KEY (user_id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);
