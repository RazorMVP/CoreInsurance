package com.nubeero.cia.reinsurance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RiFacCounterRepository extends JpaRepository<RiFacCounter, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM RiFacCounter c WHERE c.year = :year")
    Optional<RiFacCounter> findByYearForUpdate(@Param("year") int year);
}
