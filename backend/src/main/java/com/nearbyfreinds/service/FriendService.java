package com.nearbyfreinds.service;

import com.nearbyfreinds.domain.Friendship;
import com.nearbyfreinds.dto.FriendInfo;
import com.nearbyfreinds.dto.Location;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.repository.jpa.FriendshipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Service
public class FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendService.class);
    private static final String CHANNEL_PREFIX = "user:";

    private final FriendshipRepository friendshipRepository;
    private final RedisPubSubManager redisPubSubManager;
    private final LocationCacheService locationCacheService;

    public FriendService(FriendshipRepository friendshipRepository,
                         RedisPubSubManager redisPubSubManager,
                         LocationCacheService locationCacheService) {
        this.friendshipRepository = friendshipRepository;
        this.redisPubSubManager = redisPubSubManager;
        this.locationCacheService = locationCacheService;
    }

    /**
     * 양방향 친구 관계를 생성하고, 트랜잭션 커밋 후 Pub/Sub 채널을 구독한다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     * @param listener 메시지 수신 리스너
     */
    @Transactional
    public void addFriend(String userId, String friendId, MessageListener listener) {
        validateNotSelf(userId, friendId);
        validateNotAlreadyFriends(userId, friendId);

        friendshipRepository.save(new Friendship(userId, friendId));
        friendshipRepository.save(new Friendship(friendId, userId));

        log.info("친구 관계 생성 완료: {} <-> {}", userId, friendId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String channel = CHANNEL_PREFIX + friendId;
                redisPubSubManager.subscribe(channel, listener);
                log.info("친구 채널 구독 완료: channel={}", channel);
            }
        });
    }

    /**
     * 양방향 친구 관계를 생성하고, 트랜잭션 커밋 후 노드 식별 가능한 Pub/Sub 채널을 구독한다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     * @param listenerCallback 노드별 메시지 수신 콜백 (redisNodeId, message)
     */
    @Transactional
    public void addFriendWithNodeInfo(String userId, String friendId, BiConsumer<String, Message> listenerCallback) {
        addFriendWithNodeInfo(userId, friendId, listenerCallback, null);
    }

    /**
     * 양방향 친구 관계를 생성하고, 트랜잭션 커밋 후 노드 식별 가능한 Pub/Sub 채널을 구독한다.
     * 생성된 리스너 목록을 콜백으로 전달하여 호출자가 추적할 수 있다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     * @param listenerCallback 노드별 메시지 수신 콜백 (redisNodeId, message)
     * @param onSubscribed 구독 완료 후 생성된 리스너 목록을 받는 콜백 (null 허용)
     */
    @Transactional
    public void addFriendWithNodeInfo(String userId, String friendId,
                                       BiConsumer<String, Message> listenerCallback,
                                       Consumer<List<MessageListener>> onSubscribed) {
        validateNotSelf(userId, friendId);
        validateNotAlreadyFriends(userId, friendId);

        friendshipRepository.save(new Friendship(userId, friendId));
        friendshipRepository.save(new Friendship(friendId, userId));

        log.info("친구 관계 생성 완료: {} <-> {}", userId, friendId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String channel = CHANNEL_PREFIX + friendId;
                List<MessageListener> listeners = redisPubSubManager.subscribeWithNodeInfo(channel, listenerCallback);
                log.info("친구 채널 노드 식별 구독 완료: channel={}", channel);
                if (onSubscribed != null) {
                    onSubscribed.accept(listeners);
                }
            }
        });
    }

    /**
     * 양방향 친구 관계를 삭제하고, 트랜잭션 커밋 후 Pub/Sub 채널 구독을 해제한다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     * @deprecated 다중 사용자 환경에서 다른 사용자의 리스너도 제거됨. removeFriend(userId, friendId, listeners) 사용 권장.
     */
    @Deprecated
    @Transactional
    public void removeFriend(String userId, String friendId) {
        friendshipRepository.deleteByUserIdAndFriendId(userId, friendId);
        friendshipRepository.deleteByUserIdAndFriendId(friendId, userId);

        log.info("친구 관계 삭제 완료: {} <-> {}", userId, friendId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String channel = CHANNEL_PREFIX + friendId;
                redisPubSubManager.unsubscribe(channel);
                log.info("친구 채널 구독 해제 완료: channel={}", channel);
            }
        });
    }

    /**
     * 양방향 친구 관계를 삭제하고, 트랜잭션 커밋 후 특정 리스너만 Pub/Sub에서 해제한다.
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     * @param listeners 제거할 리스너 목록 (subscribeWithNodeInfo에서 반환된 값)
     */
    @Transactional
    public void removeFriend(String userId, String friendId, List<MessageListener> listeners) {
        friendshipRepository.deleteByUserIdAndFriendId(userId, friendId);
        friendshipRepository.deleteByUserIdAndFriendId(friendId, userId);

        log.info("친구 관계 삭제 완료: {} <-> {}", userId, friendId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                String channel = CHANNEL_PREFIX + friendId;
                redisPubSubManager.unsubscribe(channel, listeners);
                log.info("친구 채널 특정 리스너 구독 해제 완료: channel={}, listenerCount={}", channel, listeners.size());
            }
        });
    }

    /**
     * 사용자의 친구 ID 목록을 조회한다.
     *
     * @param userId 사용자 ID
     * @return 친구 ID 목록
     */
    @Transactional(readOnly = true)
    public List<String> getFriends(String userId) {
        return friendshipRepository.findByUserId(userId).stream()
                .map(Friendship::getFriendId)
                .toList();
    }

    /**
     * 사용자의 친구 목록을 위치/온라인 상태와 함께 조회한다.
     *
     * @param userId 사용자 ID
     * @return 친구 정보 목록 (위치, 온라인 상태 포함)
     */
    @Transactional(readOnly = true)
    public List<FriendInfo> getFriendsWithLocation(String userId) {
        List<String> friendIds = getFriends(userId);
        Map<String, Location> friendLocations = locationCacheService.getLocations(friendIds);

        return friendIds.stream()
                .map(friendId -> {
                    Location loc = friendLocations.get(friendId);
                    if (loc != null) {
                        return new FriendInfo(friendId, true, loc.x(), loc.y());
                    }
                    return new FriendInfo(friendId, false, null, null);
                })
                .toList();
    }

    /**
     * 양방향 친구 관계를 DB에만 저장한다. (Pub/Sub 구독 없음)
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     */
    @Transactional
    public void addFriendDbOnly(String userId, String friendId) {
        validateNotSelf(userId, friendId);
        validateNotAlreadyFriends(userId, friendId);

        friendshipRepository.save(new Friendship(userId, friendId));
        friendshipRepository.save(new Friendship(friendId, userId));

        log.info("친구 관계 생성 완료 (DB only): {} <-> {}", userId, friendId);
    }

    /**
     * 양방향 친구 관계를 DB에서만 삭제한다. (Pub/Sub 해제 없음)
     *
     * @param userId 요청 사용자 ID
     * @param friendId 친구 사용자 ID
     */
    @Transactional
    public void removeFriendDbOnly(String userId, String friendId) {
        friendshipRepository.deleteByUserIdAndFriendId(userId, friendId);
        friendshipRepository.deleteByUserIdAndFriendId(friendId, userId);

        log.info("친구 관계 삭제 완료 (DB only): {} <-> {}", userId, friendId);
    }

    private void validateNotSelf(String userId, String friendId) {
        if (userId.equals(friendId)) {
            throw new IllegalArgumentException("자기 자신을 친구로 추가할 수 없습니다: " + userId);
        }
    }

    private void validateNotAlreadyFriends(String userId, String friendId) {
        if (friendshipRepository.findByUserIdAndFriendId(userId, friendId).isPresent()) {
            throw new IllegalStateException(
                    "이미 친구 관계가 존재합니다: " + userId + " <-> " + friendId);
        }
    }
}
