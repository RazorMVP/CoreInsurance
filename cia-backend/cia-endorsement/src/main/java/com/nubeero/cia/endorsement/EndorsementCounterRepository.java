package com.nubeero.cia.endorsement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EndorsementCounterRepository extends JpaRepository<EndorsementCounter, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EndorsementCounter c WHERE c.year = :year")
    Optional<EndorsementCounter> findByYearForUpdate(@Param("year") int year);
}
