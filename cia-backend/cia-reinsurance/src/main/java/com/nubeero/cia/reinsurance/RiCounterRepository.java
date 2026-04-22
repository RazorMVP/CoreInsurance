package com.nubeero.cia.reinsurance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RiCounterRepository extends JpaRepository<RiCounter, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM RiCounter c WHERE c.year = :year")
    Optional<RiCounter> findByYearForUpdate(@Param("year") int year);
}
