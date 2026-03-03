package com.nearbyfreinds.service;

import com.nearbyfreinds.domain.cassandra.LocationHistory;
import com.nearbyfreinds.domain.cassandra.LocationHistoryKey;
import com.nearbyfreinds.repository.cassandra.LocationHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LocationHistoryServiceTest {

    @Mock
    private LocationHistoryRepository locationHistoryRepository;

    @InjectMocks
    private LocationHistoryService locationHistoryService;

    @Test
    @DisplayName("saveLocation 호출 시 올바른 데이터로 Repository.save 호출")
    void saveLocationCallsRepositoryWithCorrectData() throws Exception {
        // given
        String userId = "user-1";
        int x = 100;
        int y = 200;
        String wsServer = "ws-1";
        ArgumentCaptor<LocationHistory> captor = ArgumentCaptor.forClass(LocationHistory.class);

        // when
        CompletableFuture<Void> future = locationHistoryService.saveLocation(userId, x, y, wsServer);
        future.get();

        // then
        then(locationHistoryRepository).should().save(captor.capture());
        LocationHistory saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getX()).isEqualTo(x);
        assertThat(saved.getY()).isEqualTo(y);
        assertThat(saved.getWsServer()).isEqualTo(wsServer);
        assertThat(saved.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("saveLocation의 반환값이 CompletableFuture<Void>이고 즉시 반환")
    void saveLocationReturnsCompletableFuture() throws Exception {
        // when
        CompletableFuture<Void> future = locationHistoryService.saveLocation("user-1", 50, 60, "ws-2");

        // then
        assertThat(future).isNotNull();
        assertThat(future.get()).isNull();
    }

    @Test
    @DisplayName("saveLocation 시 ws_server 필드에 올바른 인스턴스 ID가 저장")
    void saveLocationStoresCorrectWsServer() throws Exception {
        // given
        String wsServer = "ws-2";
        ArgumentCaptor<LocationHistory> captor = ArgumentCaptor.forClass(LocationHistory.class);

        // when
        locationHistoryService.saveLocation("user-3", 300, 400, wsServer).get();

        // then
        then(locationHistoryRepository).should().save(captor.capture());
        assertThat(captor.getValue().getWsServer()).isEqualTo(wsServer);
    }

    @Test
    @DisplayName("getHistory 호출 시 사용자별 이력을 limit만큼 조회")
    void getHistoryReturnsLimitedResults() {
        // given
        String userId = "user-1";
        int limit = 10;
        List<LocationHistory> expected = List.of(
                new LocationHistory(userId, Instant.now(), 100, 200, "ws-1"),
                new LocationHistory(userId, Instant.now().minusSeconds(1), 110, 210, "ws-1")
        );
        given(locationHistoryRepository.findByUserId(userId, limit)).willReturn(expected);

        // when
        List<LocationHistory> result = locationHistoryService.getHistory(userId, limit);

        // then
        assertThat(result).hasSize(2);
        then(locationHistoryRepository).should().findByUserId(userId, limit);
    }

    @Test
    @DisplayName("getHistory에 limit 0 전달 시 기본값 50 사용")
    void getHistoryUsesDefaultLimitWhenZero() {
        // given
        String userId = "user-1";
        given(locationHistoryRepository.findByUserId(userId, 50)).willReturn(List.of());

        // when
        locationHistoryService.getHistory(userId, 0);

        // then
        then(locationHistoryRepository).should().findByUserId(userId, 50);
    }

    @Test
    @DisplayName("getHistory에 음수 limit 전달 시 기본값 50 사용")
    void getHistoryUsesDefaultLimitWhenNegative() {
        // given
        String userId = "user-2";
        given(locationHistoryRepository.findByUserId(userId, 50)).willReturn(List.of());

        // when
        locationHistoryService.getHistory(userId, -1);

        // then
        then(locationHistoryRepository).should().findByUserId(userId, 50);
    }

    @Test
    @DisplayName("여러 위치 저장 후 getHistory로 timestamp DESC 정렬 조회")
    void getHistoryReturnsInDescendingTimestampOrder() {
        // given
        String userId = "user-1";
        Instant now = Instant.now();
        Instant older = now.minusSeconds(10);
        Instant oldest = now.minusSeconds(20);

        List<LocationHistory> orderedHistory = List.of(
                new LocationHistory(userId, now, 300, 400, "ws-1"),
                new LocationHistory(userId, older, 200, 300, "ws-1"),
                new LocationHistory(userId, oldest, 100, 200, "ws-1")
        );
        given(locationHistoryRepository.findByUserId(userId, 10)).willReturn(orderedHistory);

        // when
        List<LocationHistory> result = locationHistoryService.getHistory(userId, 10);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getTimestamp()).isAfter(result.get(1).getTimestamp());
        assertThat(result.get(1).getTimestamp()).isAfter(result.get(2).getTimestamp());
    }

    @Test
    @DisplayName("Repository 저장 실패 시 예외가 전파되지 않고 CompletableFuture 정상 반환")
    void saveLocationHandlesRepositoryException() throws Exception {
        // given
        given(locationHistoryRepository.save(any(LocationHistory.class)))
                .willThrow(new RuntimeException("Cassandra 연결 실패"));

        // when
        CompletableFuture<Void> future = locationHistoryService.saveLocation("user-1", 100, 200, "ws-1");

        // then
        assertThat(future).isNotNull();
        assertThat(future.get()).isNull();
    }
}
