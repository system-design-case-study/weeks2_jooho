package com.nearbyfreinds.service;

import com.nearbyfreinds.domain.Friendship;
import com.nearbyfreinds.pubsub.RedisPubSubManager;
import com.nearbyfreinds.repository.jpa.FriendshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendServiceTest {

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private RedisPubSubManager redisPubSubManager;

    @Mock
    private LocationCacheService locationCacheService;

    private FriendService friendService;

    @BeforeEach
    void setUp() {
        friendService = new FriendService(friendshipRepository, redisPubSubManager, locationCacheService);
        TransactionSynchronizationManager.initSynchronization();
    }

    void tearDownSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("친구 추가 시 양방향 레코드(A->B, B->A)가 모두 생성된다")
    void addFriend_createsBidirectionalRecords() {
        // given
        String userId = "user-1";
        String friendId = "user-2";
        MessageListener listener = mock(MessageListener.class);
        when(friendshipRepository.findByUserIdAndFriendId(userId, friendId))
                .thenReturn(Optional.empty());

        // when
        friendService.addFriend(userId, friendId, listener);

        // then
        ArgumentCaptor<Friendship> captor = ArgumentCaptor.forClass(Friendship.class);
        verify(friendshipRepository, times(2)).save(captor.capture());

        List<Friendship> saved = captor.getAllValues();
        assertThat(saved.get(0).getUserId()).isEqualTo(userId);
        assertThat(saved.get(0).getFriendId()).isEqualTo(friendId);
        assertThat(saved.get(1).getUserId()).isEqualTo(friendId);
        assertThat(saved.get(1).getFriendId()).isEqualTo(userId);

        tearDownSynchronization();
    }

    @Test
    @DisplayName("친구 추가 후 트랜잭션 커밋 시 RedisPubSubManager.subscribe가 호출된다")
    void addFriend_subscribesAfterCommit() {
        // given
        String userId = "user-1";
        String friendId = "user-2";
        MessageListener listener = mock(MessageListener.class);
        when(friendshipRepository.findByUserIdAndFriendId(userId, friendId))
                .thenReturn(Optional.empty());

        // when
        friendService.addFriend(userId, friendId, listener);

        verify(redisPubSubManager, never()).subscribe(any(), any());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        // then
        verify(redisPubSubManager).subscribe(eq("user:" + friendId), eq(listener));

        tearDownSynchronization();
    }

    @Test
    @DisplayName("친구 제거 시 양방향 레코드가 모두 삭제된다")
    void removeFriend_deletesBidirectionalRecords() {
        // given
        String userId = "user-1";
        String friendId = "user-2";

        // when
        friendService.removeFriend(userId, friendId);

        // then
        verify(friendshipRepository).deleteByUserIdAndFriendId(userId, friendId);
        verify(friendshipRepository).deleteByUserIdAndFriendId(friendId, userId);

        tearDownSynchronization();
    }

    @Test
    @DisplayName("친구 제거 후 트랜잭션 커밋 시 RedisPubSubManager.unsubscribe가 호출된다")
    void removeFriend_unsubscribesAfterCommit() {
        // given
        String userId = "user-1";
        String friendId = "user-2";

        // when
        friendService.removeFriend(userId, friendId);

        verify(redisPubSubManager, never()).unsubscribe(any());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        // then
        verify(redisPubSubManager).unsubscribe("user:" + friendId);

        tearDownSynchronization();
    }

    @Test
    @DisplayName("친구 목록 조회 시 친구 ID 목록이 반환된다")
    void getFriends_returnsFriendIds() {
        // given
        String userId = "user-1";
        when(friendshipRepository.findByUserId(userId)).thenReturn(List.of(
                new Friendship(userId, "user-2"),
                new Friendship(userId, "user-3"),
                new Friendship(userId, "user-5")
        ));

        // when
        List<String> friends = friendService.getFriends(userId);

        // then
        assertThat(friends).containsExactly("user-2", "user-3", "user-5");

        tearDownSynchronization();
    }

    @Test
    @DisplayName("중복 친구 추가 시 IllegalStateException이 발생한다")
    void addFriend_duplicateThrowsException() {
        // given
        String userId = "user-1";
        String friendId = "user-2";
        MessageListener listener = mock(MessageListener.class);
        when(friendshipRepository.findByUserIdAndFriendId(userId, friendId))
                .thenReturn(Optional.of(new Friendship(userId, friendId)));

        // when & then
        assertThatThrownBy(() -> friendService.addFriend(userId, friendId, listener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 친구 관계가 존재합니다");

        verify(friendshipRepository, never()).save(any());

        tearDownSynchronization();
    }

    @Test
    @DisplayName("자기 자신을 친구로 추가 시 IllegalArgumentException이 발생한다")
    void addFriend_selfThrowsException() {
        // given
        String userId = "user-1";
        MessageListener listener = mock(MessageListener.class);

        // when & then
        assertThatThrownBy(() -> friendService.addFriend(userId, userId, listener))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신을 친구로 추가할 수 없습니다");

        verify(friendshipRepository, never()).save(any());

        tearDownSynchronization();
    }

    @Test
    @DisplayName("친구가 없는 사용자의 친구 목록 조회 시 빈 리스트 반환")
    void getFriends_emptyList() {
        // given
        String userId = "user-1";
        when(friendshipRepository.findByUserId(userId)).thenReturn(List.of());

        // when
        List<String> friends = friendService.getFriends(userId);

        // then
        assertThat(friends).isEmpty();

        tearDownSynchronization();
    }
}
