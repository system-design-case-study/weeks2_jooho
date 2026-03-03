package com.nearbyfreinds.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class LocationUpdateMessage {

    private final String userId;
    private final int x;
    private final int y;
    private final long timestamp;
    private final List<PropagationPath> path;

    @JsonCreator
    public LocationUpdateMessage(
            @JsonProperty("userId") String userId,
            @JsonProperty("x") int x,
            @JsonProperty("y") int y,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("path") List<PropagationPath> path) {
        this.userId = userId;
        this.x = x;
        this.y = y;
        this.timestamp = timestamp;
        this.path = path != null ? new ArrayList<>(path) : new ArrayList<>();
    }

    public LocationUpdateMessage(String userId, int x, int y, long timestamp) {
        this(userId, x, y, timestamp, null);
    }

    public String getUserId() {
        return userId;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<PropagationPath> getPath() {
        return path;
    }

    public void addPath(PropagationPath entry) {
        this.path.add(entry);
    }
}
