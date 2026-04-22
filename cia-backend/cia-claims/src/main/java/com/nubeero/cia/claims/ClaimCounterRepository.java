package com.nubeero.cia.claims;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClaimCounterRepository extends JpaRepository<ClaimCounter, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ClaimCounter c WHERE c.year = :year")
    Optional<ClaimCounter> findByYearForUpdate(@Param("year") int year);
}
