package com.vandrae.patchnotes.users.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findBySteamId(Long steamId);
}
