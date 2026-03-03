package com.nearbyfreinds.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nearbyfreinds.dto.FriendAddRequest;
import com.nearbyfreinds.dto.FriendAddResponse;
import com.nearbyfreinds.dto.FriendInfo;
import com.nearbyfreinds.service.FriendService;
import com.nearbyfreinds.websocket.LocationWebSocketHandler;

@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private static final Logger log = LoggerFactory.getLogger(FriendController.class);

    private final FriendService friendService;
    private final LocationWebSocketHandler webSocketHandler;

    public FriendController(FriendService friendService,
                            LocationWebSocketHandler webSocketHandler) {
        this.friendService = friendService;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 친구를 추가한다.
     *
     * @param userId 요청 사용자 ID
     * @param request 친구 추가 요청
     * @return 201 Created + 친구 관계 정보
     */
    @PostMapping
    public ResponseEntity<FriendAddResponse> addFriend(
            @RequestParam String userId,
            @RequestBody FriendAddRequest request) {
        log.info("친구 추가 요청: userId={}, friendId={}", userId, request.friendId());

        try {
            friendService.addFriendDbOnly(userId, request.friendId());
        } catch (IllegalStateException e) {
            log.info("이미 친구 관계 존재: userId={}, friendId={}", userId, request.friendId());
        }

        webSocketHandler.publishFriendNotify("FRIEND_ADDED", userId, request.friendId());
        webSocketHandler.publishFriendNotify("FRIEND_ADDED", request.friendId(), userId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FriendAddResponse(userId, request.friendId()));
    }

    /**
     * 친구를 제거한다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 제거할 친구 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{friendId}")
    public ResponseEntity<Void> removeFriend(
            @RequestParam String userId,
            @PathVariable String friendId) {
        log.info("친구 제거 요청: userId={}, friendId={}", userId, friendId);

        friendService.removeFriendDbOnly(userId, friendId);

        webSocketHandler.publishFriendNotify("FRIEND_REMOVED", userId, friendId);
        webSocketHandler.publishFriendNotify("FRIEND_REMOVED", friendId, userId);

        return ResponseEntity.noContent().build();
    }

    /**
     * 친구 목록을 조회한다. (위치/온라인 상태 포함)
     *
     * @param userId 요청 사용자 ID
     * @return 200 OK + 친구 정보 목록
     */
    @GetMapping
    public ResponseEntity<List<FriendInfo>> getFriends(@RequestParam String userId) {
        log.info("친구 목록 조회 요청: userId={}", userId);

        List<FriendInfo> friends = friendService.getFriendsWithLocation(userId);
        return ResponseEntity.ok(friends);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }
}
