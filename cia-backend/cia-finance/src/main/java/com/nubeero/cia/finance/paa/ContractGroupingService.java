package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assigns each approved policy to an IFRS 17 group of contracts at initial
 * recognition (§22). Module 12 Phase 2 Slice 2.2.
 *
 * <p>On every {@code PolicyApprovedEvent}, this service:
 * <ol>
 *   <li>resolves the {@link Portfolio} for the policy's class-of-business
 *       (lazy-creates one if absent — first-policy-of-class bootstraps the
 *       portfolio);</li>
 *   <li>resolves the {@link GroupOfContracts} for
 *       {@code (portfolio, cohort_year, onerousness)} (lazy-creates if
 *       absent);</li>
 *   <li>writes a {@link PolicyGroupAssignment} row linking the policy to
 *       the group.</li>
 * </ol>
 *
 * <h2>Execution model</h2>
 * <p>Mirrors {@code SubledgerPostingService}: {@link EventListener} +
 * {@link Transactional} on the class. The listener joins the publisher's
 * transaction (REQUIRED propagation) — if grouping fails, the policy
 * approval rolls back too. Atomicity is non-negotiable: a policy that is
 * "approved but not assigned to a group" is impossible to measure under
 * IFRS 17.
 *
 * <h2>Idempotency</h2>
 * <p>{@code policy_group_assignment.policy_id} carries a UNIQUE constraint
 * (V37), so a duplicate {@code PolicyApprovedEvent} re-fire produces a
 * constraint violation rather than a duplicate assignment. As the fast
 * path the service checks {@link PolicyGroupAssignmentRepository#existsByPolicyIdAndDeletedAtIsNull}
 * and short-circuits before any further work.
 *
 * <h2>Onerousness</h2>
 * <p>v1 (this slice) assigns every contract to {@link Onerousness#NOT_ONEROUS}
 * at initial recognition. This is the standard PAA simplification — IFRS 17
 * §22 makes the assignment permanent, and subsequent measurement deterioration
 * is recognised as a loss component <em>on the assigned group</em> (handled by
 * Slice 2.7's onerous contract test). The {@link #assessOnerousness} method
 * is the single seam where a future slice can plug in product-level onerousness
 * flags or expected-loss-ratio computation without touching the rest of the
 * grouping logic.
 *
 * <h2>Portfolio bootstrap race</h2>
 * <p>The portfolio code uniqueness ({@code uq_portfolio_code} in V36) means
 * two threads racing to create the first portfolio for the same class of
 * business won't both succeed — one rolls back on the UNIQUE constraint
 * and the policy-approval transaction surfaces a retryable error. In
 * practice this race window is the very first policy approved per
 * class-of-business per tenant; tenants with seeded portfolios never hit it.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class ContractGroupingService {

    /** Portfolio code prefix for lazy-created portfolios. Keeps user-created codes from colliding with auto-codes. */
    private static final String AUTO_PORTFOLIO_PREFIX = "COB-";

    /** Fallback portfolio code used when a policy event arrives without a class-of-business or with an unknown one. */
    private static final String UNCLASSIFIED_PORTFOLIO_CODE = "UNCLASSIFIED";

    /** {@link Portfolio#getCode()} max length per V36 schema. */
    private static final int PORTFOLIO_CODE_MAX_LENGTH = 20;

    private final PortfolioRepository portfolioRepository;
    private final GroupOfContractsRepository groupRepository;
    private final PolicyGroupAssignmentRepository assignmentRepository;
    private final ClassOfBusinessRepository classOfBusinessRepository;
    private final Clock clock;

    @EventListener
    public void onPolicyApproved(PolicyApprovedEvent event) {
        replayPolicyApproved(event);
    }

    /**
     * Public replay entry — invoked by the {@code @EventListener} for live
     * events and (later) by the retroactive backfill workflow to assign
     * pre-existing policies that pre-date Phase 2.
     *
     * @return the (existing or newly-created) assignment row
     */
    public PolicyGroupAssignment replayPolicyApproved(PolicyApprovedEvent event) {
        Optional<PolicyGroupAssignment> existing =
            assignmentRepository.findByPolicyIdAndDeletedAtIsNull(event.policyId());
        if (existing.isPresent()) {
            log.debug("Policy {} already assigned to group {}; skipping",
                event.policyNumber(), existing.get().getGroup().getId());
            return existing.get();
        }

        Portfolio portfolio = resolveOrCreatePortfolio(event.classOfBusinessId());
        int cohortYear = event.policyStartDate().getYear();
        Onerousness onerousness = assessOnerousness(event);
        GroupOfContracts group = resolveOrCreateGroup(portfolio, cohortYear, onerousness);

        PolicyGroupAssignment assignment = new PolicyGroupAssignment();
        assignment.setPolicyId(event.policyId());
        assignment.setGroup(group);
        assignment.setAssignedAt(Instant.now(clock));
        PolicyGroupAssignment saved = assignmentRepository.save(assignment);

        log.info("Assigned policy {} ({}) to group {}/{}/{} (group id {})",
            event.policyNumber(), event.policyId(),
            portfolio.getCode(), cohortYear, onerousness, group.getId());

        return saved;
    }

    private Portfolio resolveOrCreatePortfolio(UUID classOfBusinessId) {
        if (classOfBusinessId == null) {
            return findOrCreatePortfolioByCode(UNCLASSIFIED_PORTFOLIO_CODE, "Unclassified", null);
        }

        List<Portfolio> existing =
            portfolioRepository.findByClassOfBusinessIdAndDeletedAtIsNullOrderByCodeAsc(classOfBusinessId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Optional<ClassOfBusiness> cob = classOfBusinessRepository.findById(classOfBusinessId);
        if (cob.isEmpty()) {
            log.warn("class_of_business {} referenced by PolicyApprovedEvent but not found; "
                + "assigning policy to UNCLASSIFIED portfolio", classOfBusinessId);
            return findOrCreatePortfolioByCode(UNCLASSIFIED_PORTFOLIO_CODE, "Unclassified", null);
        }

        String autoCode = autoPortfolioCode(cob.get().getCode());
        return findOrCreatePortfolioByCode(autoCode, cob.get().getName(), classOfBusinessId);
    }

    /**
     * Builds a portfolio code from a class-of-business code, prefixing with
     * {@link #AUTO_PORTFOLIO_PREFIX} to keep auto-created portfolios from
     * colliding with user-created ones, and truncating the suffix so the
     * result fits the {@code VARCHAR(20)} portfolio.code column.
     */
    private static String autoPortfolioCode(String cobCode) {
        int suffixBudget = PORTFOLIO_CODE_MAX_LENGTH - AUTO_PORTFOLIO_PREFIX.length();
        String suffix = cobCode.length() <= suffixBudget ? cobCode : cobCode.substring(0, suffixBudget);
        return AUTO_PORTFOLIO_PREFIX + suffix;
    }

    private Portfolio findOrCreatePortfolioByCode(String code, String name, UUID classOfBusinessId) {
        return portfolioRepository.findByCodeAndDeletedAtIsNull(code).orElseGet(() -> {
            Portfolio p = new Portfolio();
            p.setCode(code);
            p.setName(name);
            p.setClassOfBusinessId(classOfBusinessId);
            p.setActive(true);
            Portfolio saved = portfolioRepository.save(p);
            log.info("Auto-created portfolio {} ({}) for class_of_business {}", code, name, classOfBusinessId);
            return saved;
        });
    }

    private GroupOfContracts resolveOrCreateGroup(Portfolio portfolio, int cohortYear, Onerousness onerousness) {
        return groupRepository
            .findByPortfolioIdAndCohortYearAndOnerousnessAndDeletedAtIsNull(
                portfolio.getId(), cohortYear, onerousness)
            .orElseGet(() -> {
                GroupOfContracts g = new GroupOfContracts();
                g.setPortfolio(portfolio);
                g.setCohortYear(cohortYear);
                g.setOnerousness(onerousness);
                g.setStatus(GroupStatus.OPEN);
                GroupOfContracts saved = groupRepository.save(g);
                log.info("Auto-created group {}/{}/{} (id {})",
                    portfolio.getCode(), cohortYear, onerousness, saved.getId());
                return saved;
            });
    }

    /**
     * Onerousness assessment seam. v1: always {@link Onerousness#NOT_ONEROUS}.
     *
     * <p>Future slices may plug in:
     * <ul>
     *   <li>A product-level onerousness flag (administrator-set at product
     *       setup) — overrides default;</li>
     *   <li>An expected-loss-ratio computation from the product's pricing
     *       data — auto-classifies as ONEROUS if expected loss + acquisition
     *       > expected premium per §18;</li>
     *   <li>A risk-quality flag from underwriting that drives
     *       {@link Onerousness#NO_SIGNIFICANT_POSSIBILITY}.</li>
     * </ul>
     *
     * <p>Per IFRS 17 §22 the assignment is permanent; later deterioration is
     * recognised as a loss component on the assigned group (Slice 2.7).
     */
    private Onerousness assessOnerousness(PolicyApprovedEvent event) {
        return Onerousness.NOT_ONEROUS;
    }
}
