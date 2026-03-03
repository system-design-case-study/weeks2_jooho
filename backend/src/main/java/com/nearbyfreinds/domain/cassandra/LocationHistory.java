package com.nearbyfreinds.domain.cassandra;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("location_history")
public class LocationHistory {

    @PrimaryKey
    private LocationHistoryKey key;

    @Column("x")
    private int x;

    @Column("y")
    private int y;

    @Column("ws_server")
    private String wsServer;

    public LocationHistory() {
    }

    public LocationHistory(String userId, Instant timestamp, int x, int y, String wsServer) {
        this.key = new LocationHistoryKey(userId, timestamp);
        this.x = x;
        this.y = y;
        this.wsServer = wsServer;
    }

    public LocationHistoryKey getKey() {
        return key;
    }

    public String getUserId() {
        return key.getUserId();
    }

    public Instant getTimestamp() {
        return key.getTimestamp();
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public String getWsServer() {
        return wsServer;
    }
}
