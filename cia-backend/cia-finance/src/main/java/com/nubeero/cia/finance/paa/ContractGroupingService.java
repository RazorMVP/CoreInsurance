package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Assigns each approved policy — and, as of the FAC / IFRS-17 PAA workstream
 * Task 2, each accepted inward FAC cover and each ceded outward FAC cover —
 * to an IFRS 17 group of contracts at initial recognition (§22). Module 12
 * Phase 2 Slice 2.2; generalised to facultative reinsurance in Task 2.
 *
 * <p>On every {@code PolicyApprovedEvent} / {@code RiFacInwardAcceptedEvent}
 * / {@code FacPremiumCededEvent}, {@link #assign} does the same three things
 * for whichever {@link ContractType} the event represents:
 * <ol>
 *   <li>resolves the {@link Portfolio} for the contract's class-of-business
 *       <em>and</em> {@link ContractNature} (lazy-creates one if absent —
 *       first-contract-of-(class, nature) bootstraps the portfolio);</li>
 *   <li>resolves the {@link GroupOfContracts} for
 *       {@code (portfolio, cohort_year, onerousness)} (lazy-creates if
 *       absent);</li>
 *   <li>writes a {@link ContractGroupAssignment} row linking the contract to
 *       the group.</li>
 * </ol>
 *
 * <p>{@link ContractType} (the assignment discriminator: POLICY /
 * FAC_INWARD / FAC_OUTWARD) and {@link ContractNature} (the portfolio
 * dimension: DIRECT / FAC_INWARD / FAC_OUTWARD) are deliberately distinct —
 * see their javadoc. A direct policy is always {@code ContractType.POLICY}
 * inside a {@code ContractNature.DIRECT} portfolio; an accepted inward FAC
 * is {@code ContractType.FAC_INWARD} inside a {@code ContractNature.FAC_INWARD}
 * portfolio; a ceded outward FAC is {@code ContractType.FAC_OUTWARD} inside a
 * {@code ContractNature.FAC_OUTWARD} portfolio.
 *
 * <h2>Execution model</h2>
 * <p>Mirrors {@code SubledgerPostingService}: {@link EventListener} +
 * {@link Transactional} on the class. Each listener joins the publisher's
 * transaction (REQUIRED propagation) — if grouping fails, the triggering
 * approval/acceptance/cession rolls back too. Atomicity is non-negotiable:
 * a contract that is "approved but not assigned to a group" is impossible
 * to measure under IFRS 17.
 *
 * <h2>Idempotency</h2>
 * <p>{@code contract_group_assignment (contract_type, contract_id)} carries
 * a UNIQUE constraint (V77; generalised from V37's policy-only
 * {@code UNIQUE(policy_id)}), so a duplicate event re-fire produces a
 * constraint violation rather than a duplicate assignment. As the fast path
 * {@link #assign} checks
 * {@link ContractGroupAssignmentRepository#findByContractTypeAndContractIdAndDeletedAtIsNull}
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
 * two threads racing to create the first portfolio for the same
 * (class-of-business, nature) won't both succeed — one rolls back on the
 * UNIQUE constraint and the triggering transaction surfaces a retryable
 * error. In practice this race window is the very first contract of a given
 * nature approved per class-of-business per tenant; tenants with seeded
 * portfolios never hit it.
 *
 * <h2>Cohort year for FAC contracts</h2>
 * <p>Neither {@code RiFacInwardAcceptedEvent} nor {@code FacPremiumCededEvent}
 * carries a coverage-start date, so {@link #inwardCoverStartYear} /
 * {@link #outwardCoverStartYear} resolve it via a scalar native-SQL read
 * against {@code ri_fac_inwards.cover_from} / {@code ri_fac_covers.cover_from}
 * — mirroring {@code LrcEngine.loadPolicyPricing}'s native-SQL pattern (list
 * query, empty-safe, no {@code queryForObject} exception path) to avoid
 * pulling cia-reinsurance entities into cia-finance's persistence context
 * (the same loose-coupling {@link PolicyClassResolver} already establishes
 * for {@code policies}).
 *
 * <p><strong>Same-transaction visibility.</strong> {@code RiFacInwardService.create()}
 * / {@code FacCoverService}'s ceding path {@code repository.save(...)} the
 * FAC row (Hibernate buffers the INSERT — {@code BaseEntity.id} is
 * client-assigned {@link jakarta.persistence.GenerationType#UUID}, so nothing
 * forces an immediate flush) and then publish the accepted/ceded event
 * <em>synchronously in the same open transaction</em>. A raw JDBC read run at
 * that point can miss the not-yet-flushed row. Both cohort-year helpers
 * therefore call {@link EntityManager#flush()} before reading — deterministic,
 * and not reliant on some unrelated listener happening to flush first.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class ContractGroupingService {

    /** Portfolio code prefixes for lazy-created portfolios, keyed by {@link ContractNature}. Keeps user-created codes from colliding with auto-codes and segregates natures. */
    private static final Map<ContractNature, String> AUTO_PORTFOLIO_PREFIX = Map.of(
        ContractNature.DIRECT, "COB-",
        ContractNature.FAC_INWARD, "FIN-",
        ContractNature.FAC_OUTWARD, "FOU-"
    );

    /** Fallback portfolio code (DIRECT nature) used when a policy event arrives without a class-of-business or with an unknown one. Preserved unprefixed for backward compatibility with pre-Task-2 data. */
    private static final String UNCLASSIFIED_PORTFOLIO_CODE = "UNCLASSIFIED";

    /** {@link Portfolio#getCode()} max length per V36 schema. */
    private static final int PORTFOLIO_CODE_MAX_LENGTH = 20;

    private final PortfolioRepository portfolioRepository;
    private final GroupOfContractsRepository groupRepository;
    private final ContractGroupAssignmentRepository assignmentRepository;
    private final ClassOfBusinessRepository classOfBusinessRepository;
    private final PolicyClassResolver policyClassResolver;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
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
    public ContractGroupAssignment replayPolicyApproved(PolicyApprovedEvent event) {
        return assign(ContractType.POLICY, event.policyId(), event.classOfBusinessId(),
            event.policyStartDate().getYear(), ContractNature.DIRECT);
    }

    /**
     * An inward FAC cover accepted (create/renew) or extended — grouped into
     * a {@link ContractNature#FAC_INWARD} portfolio under the ceding cover's
     * own class-of-business. Cohort year = coverage-start year.
     *
     * @since FAC / IFRS-17 PAA workstream Task 2
     */
    @EventListener
    @Transactional
    public void onFacInwardAccepted(RiFacInwardAcceptedEvent event) {
        assign(ContractType.FAC_INWARD, event.facInwardId(), event.classOfBusinessId(),
            inwardCoverStartYear(event.facInwardId()), ContractNature.FAC_INWARD);
    }

    /**
     * An outward FAC premium ceded — grouped into a
     * {@link ContractNature#FAC_OUTWARD} portfolio under the class-of-business
     * of the policy the ceded risk originates from (resolved via
     * {@link PolicyClassResolver}, the same loose-coupling seam
     * {@code SubledgerPostingService} uses for outward-COB resolution).
     * Cohort year = coverage-start year.
     *
     * @since FAC / IFRS-17 PAA workstream Task 2
     */
    @EventListener
    @Transactional
    public void onFacPremiumCeded(FacPremiumCededEvent event) {
        UUID cob = policyClassResolver.findClassByPolicyId(event.policyId());
        assign(ContractType.FAC_OUTWARD, event.facCoverId(), cob,
            outwardCoverStartYear(event.facCoverId()), ContractNature.FAC_OUTWARD);
    }

    /**
     * Common assignment body shared by the direct-policy and both FAC entry
     * points: idempotency short-circuit, portfolio resolution, group
     * resolution, assignment write.
     *
     * @return the (existing or newly-created) assignment row
     */
    private ContractGroupAssignment assign(ContractType type, UUID contractId, UUID classOfBusinessId,
                                            int cohortYear, ContractNature nature) {
        Optional<ContractGroupAssignment> existing =
            assignmentRepository.findByContractTypeAndContractIdAndDeletedAtIsNull(type, contractId);
        if (existing.isPresent()) {
            log.debug("{} {} already assigned to group {}; skipping",
                type, contractId, existing.get().getGroup().getId());
            return existing.get();
        }

        Portfolio portfolio = resolveOrCreatePortfolio(classOfBusinessId, nature);
        Onerousness onerousness = assessOnerousness();
        GroupOfContracts group = resolveOrCreateGroup(portfolio, cohortYear, onerousness);

        ContractGroupAssignment assignment = new ContractGroupAssignment();
        assignment.setContractType(type);
        assignment.setContractId(contractId);
        assignment.setGroup(group);
        assignment.setAssignedAt(Instant.now(clock));
        ContractGroupAssignment saved = assignmentRepository.save(assignment);

        log.info("Assigned {} {} to group {}/{}/{} (group id {})",
            type, contractId, portfolio.getCode(), cohortYear, onerousness, group.getId());

        return saved;
    }

    private Portfolio resolveOrCreatePortfolio(UUID classOfBusinessId, ContractNature nature) {
        if (classOfBusinessId == null) {
            return findOrCreatePortfolioByCode(unclassifiedPortfolioCode(nature), "Unclassified", null, nature);
        }

        List<Portfolio> existing = portfolioRepository
            .findByClassOfBusinessIdAndContractNatureAndDeletedAtIsNullOrderByCodeAsc(classOfBusinessId, nature);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Optional<ClassOfBusiness> cob = classOfBusinessRepository.findById(classOfBusinessId);
        if (cob.isEmpty()) {
            log.warn("class_of_business {} referenced by a {} grouping event but not found; "
                + "assigning contract to UNCLASSIFIED portfolio", classOfBusinessId, nature);
            return findOrCreatePortfolioByCode(unclassifiedPortfolioCode(nature), "Unclassified", null, nature);
        }

        String autoCode = autoPortfolioCode(cob.get().getCode(), nature);
        return findOrCreatePortfolioByCode(autoCode, cob.get().getName(), classOfBusinessId, nature);
    }

    /**
     * Builds a portfolio code from a class-of-business code, prefixing with
     * the {@link #AUTO_PORTFOLIO_PREFIX} entry for the given nature to keep
     * auto-created portfolios from colliding across natures (or with
     * user-created ones), and truncating the suffix so the result fits the
     * {@code VARCHAR(20)} portfolio.code column.
     *
     * <p><strong>Truncation collision.</strong> Two distinct class-of-business
     * codes that share their first {@code 20 - prefix.length()} characters
     * truncate to the identical portfolio code. This method deliberately does
     * <em>not</em> disambiguate — the {@code COB-<code>} scheme (and its FAC
     * siblings) must stay byte-identical to what already-provisioned tenant
     * data expects, so short codes are never affected and the truncation rule
     * itself never changes shape. Instead, {@link #findOrCreatePortfolioByCode}
     * detects the collision when it actually happens (a differing
     * {@code classOfBusinessId} reusing the same code) and fails loudly rather
     * than silently mis-grouping the second class-of-business's contracts.
     */
    private static String autoPortfolioCode(String cobCode, ContractNature nature) {
        String prefix = prefixFor(nature);
        int suffixBudget = PORTFOLIO_CODE_MAX_LENGTH - prefix.length();
        String suffix = cobCode.length() <= suffixBudget ? cobCode : cobCode.substring(0, suffixBudget);
        return prefix + suffix;
    }

    /**
     * DIRECT keeps the historical unprefixed {@link #UNCLASSIFIED_PORTFOLIO_CODE}
     * (pre-Task-2 data + {@code ContractGroupingServiceIT} depend on the exact
     * literal). FAC natures get their own nature-prefixed unclassified code so
     * an inward/outward fallback never collides with — or gets swept into —
     * the direct-policy UNCLASSIFIED portfolio.
     */
    private static String unclassifiedPortfolioCode(ContractNature nature) {
        return nature == ContractNature.DIRECT
            ? UNCLASSIFIED_PORTFOLIO_CODE
            : prefixFor(nature) + UNCLASSIFIED_PORTFOLIO_CODE;
    }

    /**
     * {@link #AUTO_PORTFOLIO_PREFIX} lookup that fails loudly instead of
     * NPE-ing if a future {@link ContractNature} value is added without a
     * matching map entry.
     */
    private static String prefixFor(ContractNature nature) {
        String prefix = AUTO_PORTFOLIO_PREFIX.get(nature);
        if (prefix == null) {
            throw new IllegalStateException("unmapped ContractNature: " + nature);
        }
        return prefix;
    }

    /**
     * Resolves the portfolio for {@code code}, creating it if absent.
     *
     * <p>When a portfolio with this exact code already exists, its
     * {@code classOfBusinessId} MUST match the one the caller is resolving
     * for — otherwise this is a truncation collision (see
     * {@link #autoPortfolioCode}: two different class-of-business codes
     * produced the same 20-char auto-code) and reusing the existing portfolio
     * would silently group the second class-of-business's contracts under
     * the first's. The unclassified-fallback callers always pass a
     * {@code null classOfBusinessId} on both sides, so this check never
     * fires for them — it fires only for the auto-code collision case.
     */
    private Portfolio findOrCreatePortfolioByCode(String code, String name, UUID classOfBusinessId, ContractNature nature) {
        Optional<Portfolio> existing = portfolioRepository.findByCodeAndDeletedAtIsNull(code);
        if (existing.isPresent()) {
            Portfolio p = existing.get();
            if (!Objects.equals(p.getClassOfBusinessId(), classOfBusinessId)) {
                throw new IllegalStateException(
                    "Portfolio code collision: auto-generated code '" + code + "' for class_of_business "
                        + classOfBusinessId + " (" + nature + ") already belongs to a different class_of_business "
                        + p.getClassOfBusinessId() + " — two class-of-business codes truncate to the same "
                        + "VARCHAR(20) portfolio code. Rename one class-of-business code, or seed a portfolio "
                        + "for it manually with a distinct code, to disambiguate.");
            }
            return p;
        }

        Portfolio p = new Portfolio();
        p.setCode(code);
        p.setName(name);
        p.setClassOfBusinessId(classOfBusinessId);
        p.setActive(true);
        p.setContractNature(nature);
        Portfolio saved = portfolioRepository.save(p);
        log.info("Auto-created {} portfolio {} ({}) for class_of_business {}", nature, code, name, classOfBusinessId);
        return saved;
    }

    /**
     * Scalar native-SQL read of {@code ri_fac_inwards.cover_from} — mirrors
     * {@code LrcEngine.loadPolicyPricing}'s pattern (list query, empty-safe),
     * avoiding a cia-reinsurance entity dependency from cia-finance.
     *
     * <p>Flushes first: {@code RiFacInwardService.create()}/{@code renew()}/
     * {@code extend()} save the {@code RiFacInward} row and publish
     * {@link RiFacInwardAcceptedEvent} synchronously in the same open
     * transaction, without an intervening flush — a raw JDBC read here could
     * otherwise miss the not-yet-flushed INSERT.
     */
    private int inwardCoverStartYear(UUID facInwardId) {
        entityManager.flush();
        List<LocalDate> rows = jdbcTemplate.query(
            "SELECT cover_from FROM ri_fac_inwards WHERE id = ? AND deleted_at IS NULL",
            (rs, rowNum) -> rs.getDate("cover_from").toLocalDate(),
            facInwardId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                "ri_fac_inwards " + facInwardId + " not found (or soft-deleted) when resolving cohort year "
                    + "for grouping — data integrity error: the row must exist and be visible by the time "
                    + "RiFacInwardAcceptedEvent fires");
        }
        return rows.get(0).getYear();
    }

    /**
     * Scalar native-SQL read of {@code ri_fac_covers.cover_from} — mirrors
     * {@code LrcEngine.loadPolicyPricing}'s pattern (list query, empty-safe),
     * avoiding a cia-reinsurance entity dependency from cia-finance. Flushes
     * first for the same same-transaction-visibility reason as
     * {@link #inwardCoverStartYear} (kept symmetric even though the current
     * outward save path is not known to race).
     */
    private int outwardCoverStartYear(UUID facCoverId) {
        entityManager.flush();
        List<LocalDate> rows = jdbcTemplate.query(
            "SELECT cover_from FROM ri_fac_covers WHERE id = ? AND deleted_at IS NULL",
            (rs, rowNum) -> rs.getDate("cover_from").toLocalDate(),
            facCoverId);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                "ri_fac_covers " + facCoverId + " not found (or soft-deleted) when resolving cohort year "
                    + "for grouping — data integrity error: the row must exist and be visible by the time "
                    + "FacPremiumCededEvent fires");
        }
        return rows.get(0).getYear();
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
     *
     * <p>Shared across all three {@link ContractType}s — v1 makes no
     * distinction between direct policies and FAC contracts here.
     */
    private Onerousness assessOnerousness() {
        return Onerousness.NOT_ONEROUS;
    }
}
