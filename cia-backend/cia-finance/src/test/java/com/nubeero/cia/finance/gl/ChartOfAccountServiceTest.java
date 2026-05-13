package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link ChartOfAccountService}. Verifies the contract
 * downstream slices (1.4 JournalEntryService, 1.5 SubledgerPostingService,
 * 2.x measurement modules) will depend on, without bringing up a Spring or
 * database context.
 * <p>
 * Cache behaviour is intentionally not exercised here — {@code @Cacheable}
 * requires a Spring context to weave the interceptor. Caching is verified at
 * the repository integration level in {@link ChartOfAccountRepositoryIT}.
 */
@ExtendWith(MockitoExtension.class)
class ChartOfAccountServiceTest {

    @Mock
    private ChartOfAccountRepository repository;

    @InjectMocks
    private ChartOfAccountService service;

    private ChartOfAccount assetsClass;
    private ChartOfAccount liabilitiesClass;
    private ChartOfAccount investmentsGroup;
    private ChartOfAccount insuranceLiabGroup;
    private ChartOfAccount fvplEquity;
    private ChartOfAccount fvplDebt;
    private ChartOfAccount lrcBel;
    private ChartOfAccount lrcLc;

    @BeforeEach
    void seed() {
        assetsClass        = account("1000", "Assets",          AccountType.ASSET,     null, null, null);
        liabilitiesClass   = account("2000", "Liabilities",     AccountType.LIABILITY, null, null, null);
        investmentsGroup   = account("1200", "Investments",     AccountType.ASSET,     assetsClass, null, null);
        insuranceLiabGroup = account("2100", "Insurance liabs", AccountType.LIABILITY, liabilitiesClass, null, null);
        fvplEquity         = account("1210", "FVPL Equity",     AccountType.ASSET,     investmentsGroup, null, Ifrs9Role.FVPL);
        fvplDebt           = account("1220", "FVPL Debt",       AccountType.ASSET,     investmentsGroup, null, Ifrs9Role.FVPL);
        lrcBel             = account("2110", "LRC BEL",         AccountType.LIABILITY, insuranceLiabGroup, Ifrs17Role.LRC_BEL, null);
        lrcLc              = account("2130", "LRC LC",          AccountType.LIABILITY, insuranceLiabGroup, Ifrs17Role.LRC_LC, null);
    }

    @Test
    @DisplayName("findByCode returns the account for a known code")
    void findByCodeHit() {
        when(repository.findByCodeAndDeletedAtIsNull("2110")).thenReturn(Optional.of(lrcBel));
        assertThat(service.findByCode("2110")).isSameAs(lrcBel);
    }

    @Test
    @DisplayName("findByCode throws ChartOfAccountNotFoundException for unknown code")
    void findByCodeMiss() {
        when(repository.findByCodeAndDeletedAtIsNull("9999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByCode("9999"))
            .isInstanceOf(ChartOfAccountNotFoundException.class)
            .hasMessageContaining("9999");
    }

    @Test
    @DisplayName("findByIfrs17Role delegates to repository finder")
    void findByIfrs17Role() {
        when(repository.findByIfrs17RoleAndDeletedAtIsNullOrderByCodeAsc(Ifrs17Role.LRC_BEL))
            .thenReturn(List.of(lrcBel));
        assertThat(service.findByIfrs17Role(Ifrs17Role.LRC_BEL)).containsExactly(lrcBel);
    }

    @Test
    @DisplayName("findByIfrs17Role rejects null role")
    void findByIfrs17RoleNullRejected() {
        assertThatThrownBy(() -> service.findByIfrs17Role(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("findByIfrs9Role returns all accounts in the bucket")
    void findByIfrs9Role() {
        when(repository.findByIfrs9RoleAndDeletedAtIsNullOrderByCodeAsc(Ifrs9Role.FVPL))
            .thenReturn(List.of(fvplEquity, fvplDebt));
        assertThat(service.findByIfrs9Role(Ifrs9Role.FVPL))
            .extracting(ChartOfAccount::getCode)
            .containsExactly("1210", "1220");
    }

    @Test
    @DisplayName("getTree builds a nested hierarchy rooted at the account-type classes")
    void getTree() {
        List<ChartOfAccount> all = List.of(
            assetsClass, investmentsGroup, fvplEquity, fvplDebt,
            liabilitiesClass, insuranceLiabGroup, lrcBel, lrcLc);
        when(repository.findByDeletedAtIsNullOrderByCodeAsc()).thenReturn(all);

        List<ChartOfAccountNode> tree = service.getTree();

        assertThat(tree).extracting(ChartOfAccountNode::code)
            .containsExactly("1000", "2000");

        ChartOfAccountNode assetsNode = tree.get(0);
        assertThat(assetsNode.children()).extracting(ChartOfAccountNode::code)
            .containsExactly("1200");
        assertThat(assetsNode.children().get(0).children())
            .extracting(ChartOfAccountNode::code)
            .containsExactly("1210", "1220");

        ChartOfAccountNode liabNode = tree.get(1);
        assertThat(liabNode.children().get(0).children())
            .extracting(ChartOfAccountNode::code, ChartOfAccountNode::ifrs17Role)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple("2110", Ifrs17Role.LRC_BEL),
                org.assertj.core.api.Assertions.tuple("2130", Ifrs17Role.LRC_LC));
    }

    @Test
    @DisplayName("getTree returns an empty list when the COA is empty")
    void getTreeEmpty() {
        when(repository.findByDeletedAtIsNullOrderByCodeAsc()).thenReturn(List.of());
        assertThat(service.getTree()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ChartOfAccount account(
            String code, String name, AccountType type,
            ChartOfAccount parent, Ifrs17Role ifrs17, Ifrs9Role ifrs9) {
        ChartOfAccount a = new ChartOfAccount();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        a.setParent(parent);
        a.setIfrs17Role(ifrs17);
        a.setIfrs9Role(ifrs9);
        a.setActive(true);
        return a;
    }
}
