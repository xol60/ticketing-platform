package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.CityAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CityAliasRepository extends JpaRepository<CityAlias, String> {

    /**
     * Idempotent alias registration. Ingest generates the same aliases every
     * time it sees a city, so the common case is a no-op — expressed as
     * {@code DO NOTHING} rather than a read-then-write, which would race under
     * concurrent ingest and is a pointless round trip besides.
     */
    @Modifying
    @Query(value = """
            INSERT INTO city_alias (alias, city_id) VALUES (:alias, :cityId)
            ON CONFLICT (alias) DO NOTHING
            """, nativeQuery = true)
    void registerAlias(@Param("alias") String alias, @Param("cityId") Integer cityId);
}
