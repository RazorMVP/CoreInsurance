package com.nubeero.cia.claims;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class ClaimNumberService {

    private final ClaimCounterRepository counterRepository;

    public ClaimNumberService(ClaimCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        int year = LocalDate.now().getYear();
        ClaimCounter counter = counterRepository.findByYearForUpdate(year)
                .orElseGet(() -> counterRepository.save(new ClaimCounter(year)));
        counter.setLastSequence(counter.getLastSequence() + 1);
        counterRepository.save(counter);
        return String.format("CLM-%d-%06d", year, counter.getLastSequence());
    }
}
