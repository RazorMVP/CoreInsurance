package com.nubeero.cia.quotation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuoteCounterRepository extends JpaRepository<QuoteCounter, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM QuoteCounter c WHERE c.year = :year")
    Optional<QuoteCounter> findByYearForUpdate(@Param("year") int year);
}
