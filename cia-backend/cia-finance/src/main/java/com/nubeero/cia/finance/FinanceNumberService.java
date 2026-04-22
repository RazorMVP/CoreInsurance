package com.nubeero.cia.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class FinanceNumberService {

    private final FinanceCounterRepository counterRepository;

    public FinanceNumberService(FinanceCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextDebitNoteNumber() {
        return next("DN", "DN");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextReceiptNumber() {
        return next("RN", "RN");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextCreditNoteNumber() {
        return next("CN", "CN");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextPaymentNumber() {
        return next("PN", "PN");
    }

    private String next(String type, String prefix) {
        int year = LocalDate.now().getYear();
        FinanceCounter counter = counterRepository.findByTypeAndYearForUpdate(type, year)
                .orElseGet(() -> counterRepository.save(new FinanceCounter(type, year)));
        counter.setLastSequence(counter.getLastSequence() + 1);
        counterRepository.save(counter);
        return String.format("%s-%d-%06d", prefix, year, counter.getLastSequence());
    }
}
