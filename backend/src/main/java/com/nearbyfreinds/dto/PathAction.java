package com.nearbyfreinds.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PathAction {

    RECEIVE("receive"),
    PUBLISH("publish"),
    SUBSCRIBE_RECEIVE("subscribe_receive"),
    DELIVER("deliver");

    private final String value;

    PathAction(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
