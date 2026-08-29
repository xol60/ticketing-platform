package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.City;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Integer> {
    Optional<City> findByCanonicalName(String canonicalName);
}
