package com.nearbyfreinds.websocket;

import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

public class UserSession {

    private static final int SEND_TIME_LIMIT = 5000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final String userId;
    private final WebSocketSession session;
    private volatile int x;
    private volatile int y;
    private volatile long timestamp;

    public UserSession(String userId, WebSocketSession session, int x, int y, long timestamp) {
        this.userId = userId;
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT, BUFFER_SIZE_LIMIT);
        this.x = x;
        this.y = y;
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public WebSocketSession getSession() {
        return session;
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

    public void updateLocation(int x, int y, long timestamp) {
        this.x = x;
        this.y = y;
        this.timestamp = timestamp;
    }
}
