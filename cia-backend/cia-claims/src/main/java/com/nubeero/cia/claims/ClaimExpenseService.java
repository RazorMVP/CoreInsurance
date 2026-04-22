package com.nubeero.cia.claims;

import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.claims.dto.AddExpenseRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimExpenseService {

    private final ClaimExpenseRepository expenseRepository;
    private final ClaimRepository claimRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Page<ClaimExpense> findByClaimId(UUID claimId, Pageable pageable) {
        return expenseRepository.findAllByClaim_IdAndDeletedAtIsNull(claimId, pageable);
    }

    public ClaimExpense findOrThrow(UUID id) {
        return expenseRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimExpense", id));
    }

    @Transactional
    public ClaimExpense add(UUID claimId, AddExpenseRequest req) {
        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        if (claim.getStatus() == ClaimStatus.WITHDRAWN
                || claim.getStatus() == ClaimStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot add expense to a " + claim.getStatus() + " claim");
        }

        ClaimExpense expense = ClaimExpense.builder()
                .claim(claim)
                .expenseType(req.expenseType())
                .vendorId(req.vendorId())
                .vendorName(req.vendorName())
                .amount(req.amount())
                .description(req.description())
                .build();

        return expenseRepository.save(expense);
    }

    @Transactional
    public ClaimExpense approve(UUID expenseId) {
        ClaimExpense expense = findOrThrow(expenseId);
        if (expense.getStatus() != ClaimExpenseStatus.PENDING) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only PENDING expenses can be approved");
        }
        String approver = currentUser();
        expense.setStatus(ClaimExpenseStatus.APPROVED);
        expense.setApprovedBy(approver);
        expense.setApprovedAt(Instant.now());
        ClaimExpense saved = expenseRepository.save(expense);

        Claim claim = saved.getClaim();
        eventPublisher.publishEvent(new ClaimExpenseApprovedEvent(
                saved.getId(),
                claim.getClaimNumber() + "-EXP-" + saved.getId().toString().substring(0, 8),
                claim.getId(),
                claim.getClaimNumber(),
                saved.getVendorId(),
                saved.getVendorName(),
                saved.getExpenseType().name(),
                saved.getAmount(),
                claim.getCurrencyCode()
        ));

        return saved;
    }

    @Transactional
    public ClaimExpense cancel(UUID expenseId, String reason) {
        ClaimExpense expense = findOrThrow(expenseId);
        if (expense.getStatus() == ClaimExpenseStatus.APPROVED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot cancel an approved expense");
        }
        expense.setStatus(ClaimExpenseStatus.CANCELLED);
        expense.setCancelledBy(currentUser());
        expense.setCancelledAt(Instant.now());
        expense.setCancellationReason(reason);
        return expenseRepository.save(expense);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
