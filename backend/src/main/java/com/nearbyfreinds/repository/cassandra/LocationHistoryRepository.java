package com.nearbyfreinds.repository.cassandra;

import com.nearbyfreinds.domain.cassandra.LocationHistory;
import com.nearbyfreinds.domain.cassandra.LocationHistoryKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;

import java.util.List;

public interface LocationHistoryRepository extends CassandraRepository<LocationHistory, LocationHistoryKey> {

    @Query("SELECT * FROM location_history WHERE user_id = ?0 LIMIT ?1")
    List<LocationHistory> findByUserId(String userId, int limit);
}
