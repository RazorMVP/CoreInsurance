package com.nubeero.cia.endorsement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class EndorsementNumberService {

    private final EndorsementCounterRepository counterRepository;

    public EndorsementNumberService(EndorsementCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        int year = LocalDate.now().getYear();
        EndorsementCounter counter = counterRepository.findByYearForUpdate(year)
                .orElseGet(() -> counterRepository.save(new EndorsementCounter(year)));
        counter.setLastSequence(counter.getLastSequence() + 1);
        counterRepository.save(counter);
        return String.format("END-%d-%06d", year, counter.getLastSequence());
    }
}
