package com.nearbyfreinds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(name = "color_hue", nullable = false)
    private int colorHue;

    @Column(name = "initial_x", nullable = false)
    private int initialX;

    @Column(name = "initial_y", nullable = false)
    private int initialY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
    }

    public User(String id, String nickname, int colorHue, int initialX, int initialY) {
        this.id = id;
        this.nickname = nickname;
        this.colorHue = colorHue;
        this.initialX = initialX;
        this.initialY = initialY;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public int getColorHue() {
        return colorHue;
    }

    public int getInitialX() {
        return initialX;
    }

    public int getInitialY() {
        return initialY;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
