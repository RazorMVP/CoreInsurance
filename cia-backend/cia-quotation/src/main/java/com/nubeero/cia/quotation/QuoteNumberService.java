package com.nubeero.cia.quotation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class QuoteNumberService {

    private final QuoteCounterRepository counterRepository;

    // Runs in its own transaction so the counter row is committed immediately,
    // releasing the pessimistic lock before the parent transaction saves the quote.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextQuoteNumber() {
        int year = LocalDate.now().getYear();
        QuoteCounter counter = counterRepository.findByYearForUpdate(year)
                .orElseGet(() -> counterRepository.save(new QuoteCounter(year)));
        counter.setLastSequence(counter.getLastSequence() + 1);
        counterRepository.save(counter);
        return String.format("QUO-%d-%06d", year, counter.getLastSequence());
    }
}
