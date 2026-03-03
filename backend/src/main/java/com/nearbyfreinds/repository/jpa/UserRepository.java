package com.nearbyfreinds.repository.jpa;

import com.nearbyfreinds.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
}
