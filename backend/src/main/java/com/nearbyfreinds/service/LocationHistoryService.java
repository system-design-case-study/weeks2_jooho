package com.nearbyfreinds.service;

import com.nearbyfreinds.domain.cassandra.LocationHistory;
import com.nearbyfreinds.repository.cassandra.LocationHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class LocationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(LocationHistoryService.class);
    private static final int DEFAULT_LIMIT = 50;

    private final LocationHistoryRepository locationHistoryRepository;

    public LocationHistoryService(LocationHistoryRepository locationHistoryRepository) {
        this.locationHistoryRepository = locationHistoryRepository;
    }

    @Async("locationHistoryExecutor")
    public CompletableFuture<Void> saveLocation(String userId, int x, int y, String wsServer) {
        try {
            LocationHistory history = new LocationHistory(userId, Instant.now(), x, y, wsServer);
            locationHistoryRepository.save(history);
            log.debug("위치 이력 저장 완료: userId={}, x={}, y={}, wsServer={}", userId, x, y, wsServer);
        } catch (Exception e) {
            log.error("위치 이력 저장 실패: userId={}, x={}, y={}", userId, x, y, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    public List<LocationHistory> getHistory(String userId, int limit) {
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        return locationHistoryRepository.findByUserId(userId, effectiveLimit);
    }
}
