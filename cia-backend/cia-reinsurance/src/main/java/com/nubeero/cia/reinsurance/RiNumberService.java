package com.nubeero.cia.reinsurance;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;

@Service
@RequiredArgsConstructor
public class RiNumberService {

    private final RiCounterRepository riCounterRepository;
    private final RiFacCounterRepository riFacCounterRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextAllocationNumber() {
        int year = Year.now().getValue();
        RiCounter counter = riCounterRepository.findByYearForUpdate(year)
                .orElseGet(() -> {
                    RiCounter c = new RiCounter();
                    c.setYear(year);
                    c.setLastSequence(0L);
                    return c;
                });
        counter.setLastSequence(counter.getLastSequence() + 1);
        riCounterRepository.save(counter);
        return String.format("RIA-%d-%06d", year, counter.getLastSequence());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextFacReference() {
        int year = Year.now().getValue();
        RiFacCounter counter = riFacCounterRepository.findByYearForUpdate(year)
                .orElseGet(() -> {
                    RiFacCounter c = new RiFacCounter();
                    c.setYear(year);
                    c.setLastSequence(0L);
                    return c;
                });
        counter.setLastSequence(counter.getLastSequence() + 1);
        riFacCounterRepository.save(counter);
        return String.format("FAC-%d-%06d", year, counter.getLastSequence());
    }
}
