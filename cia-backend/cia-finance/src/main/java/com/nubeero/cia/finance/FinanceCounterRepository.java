package com.nubeero.cia.finance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FinanceCounterRepository extends JpaRepository<FinanceCounter, FinanceCounterKey> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM FinanceCounter c WHERE c.counterType = :type AND c.year = :year")
    Optional<FinanceCounter> findByTypeAndYearForUpdate(@Param("type") String type,
                                                        @Param("year") int year);
}
