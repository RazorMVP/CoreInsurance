package com.nubeero.cia.api.finance.gl;

import com.nubeero.cia.finance.gl.ChartOfAccount;
import com.nubeero.cia.finance.gl.ChartOfAccountNode;
import com.nubeero.cia.finance.gl.ChartOfAccountNotFoundException;
import com.nubeero.cia.finance.gl.ChartOfAccountRepository;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.Ifrs17Role;
import com.nubeero.cia.finance.gl.Ifrs9Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link ChartOfAccountService} against a real Postgres
 * container with all Flyway migrations through V32 applied. Verifies the
 * service contract end-to-end:
 * <ul>
 *   <li>tree structure (5 roots, 27 groups, 97 leaves, 129 total nodes)</li>
 *   <li>{@code findByCode} resolves seeded codes and throws for unknown codes</li>
 *   <li>{@code findByIfrs17Role} returns the expected accounts for each role</li>
 *   <li>{@code findByIfrs9Role} returns the expected accounts for each role</li>
 *   <li>{@code @Cacheable} is wired: cache region contains the value after first call</li>
 * </ul>
 *
 * <p>Uses {@code @DataJpaTest} for a lightweight JPA-only context, plus a
 * {@link TestCachingConfig} that supplies {@code @EnableCaching} and a
 * {@link ConcurrentMapCacheManager}. Multi-tenancy falls through to the
 * default {@code public} schema because {@code TenantContext} is never set.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    ChartOfAccountServiceIT.TestCachingConfig.class
})
class ChartOfAccountServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway runs through every migration; V32 seeds the COA.
        registry.add("spring.flyway.target", () -> "49");
        // Disable Hibernate's multi-tenant pieces in this slice test — JPA
        // talks to the default public schema where Flyway runs.
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    private ChartOfAccountService service;

    @Autowired
    private ChartOfAccountRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @org.junit.jupiter.api.BeforeEach
    void seedTenantContext() {
        // ChartOfAccountService's @Cacheable methods use a SpEL key that calls
        // TenantContext.getTenantId(). With no HTTP filter running in this
        // test slice, the ThreadLocal is null and Spring rejects the cache
        // operation with "Null key returned for cache operation". Setting an
        // explicit tenant id here matches what TenantContextFilter would do
        // for every production request.
        com.nubeero.cia.common.tenant.TenantContext.setTenantId("test-tenant");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContextAndCaches() {
        com.nubeero.cia.common.tenant.TenantContext.clear();
        cacheManager.getCacheNames().forEach(n -> {
            var cache = cacheManager.getCache(n);
            if (cache != null) cache.clear();
        });
    }

    @Test
    @DisplayName("V32 seed delivers 129 rows visible to the repository")
    void seedRowCount() {
        assertThat(repository.findByDeletedAtIsNullOrderByCodeAsc()).hasSize(129);
    }

    @Test
    @DisplayName("getTree returns 5 root classes ordered by code")
    void treeRoots() {
        List<ChartOfAccountNode> tree = service.getTree();
        assertThat(tree).extracting(ChartOfAccountNode::code)
            .containsExactly("1000", "2000", "3000", "4000", "5000");
    }

    @Test
    @DisplayName("getTree produces exactly 27 group nodes (Level 2)")
    void treeGroupCount() {
        int groups = service.getTree().stream()
            .mapToInt(root -> root.children().size())
            .sum();
        assertThat(groups).isEqualTo(27);
    }

    @Test
    @DisplayName("getTree produces exactly 97 leaf nodes (Level 3)")
    void treeLeafCount() {
        int leaves = service.getTree().stream()
            .flatMap(root -> root.children().stream())
            .mapToInt(group -> group.children().size())
            .sum();
        assertThat(leaves).isEqualTo(97);
    }

    @Test
    @DisplayName("findByCode resolves a known leaf and exposes the role tag")
    void findByCodeKnown() {
        ChartOfAccount account = service.findByCode("2110");
        assertThat(account.getName()).isEqualTo("LRC - Best estimate of liabilities");
        assertThat(account.getIfrs17Role()).isEqualTo(Ifrs17Role.LRC_BEL);
        assertThat(account.getIfrs9Role()).isNull();
    }

    @Test
    @DisplayName("findByCode throws ChartOfAccountNotFoundException for unknown code")
    void findByCodeUnknown() {
        assertThatThrownBy(() -> service.findByCode("9999"))
            .isInstanceOf(ChartOfAccountNotFoundException.class)
            .hasMessageContaining("9999");
    }

    @Test
    @DisplayName("findByIfrs17Role(LRC_BEL) returns both issued and inward FAC accounts")
    void ifrs17LrcBel() {
        assertThat(service.findByIfrs17Role(Ifrs17Role.LRC_BEL))
            .extracting(ChartOfAccount::getCode)
            .containsExactly("2110", "2210");
    }

    @Test
    @DisplayName("findByIfrs17Role(INSURANCE_FINANCE_OCI) returns the OCI reserve")
    void ifrs17InsuranceFinanceOci() {
        assertThat(service.findByIfrs17Role(Ifrs17Role.INSURANCE_FINANCE_OCI))
            .extracting(ChartOfAccount::getCode)
            .containsExactly("3430");
    }

    @Test
    @DisplayName("findByIfrs9Role(FVPL) returns the two FVPL leaves")
    void ifrs9Fvpl() {
        assertThat(service.findByIfrs9Role(Ifrs9Role.FVPL))
            .extracting(ChartOfAccount::getCode)
            .containsExactly("1210", "1220");
    }

    @Test
    @DisplayName("findByIfrs9Role(ECL_EXPENSE) returns the two ECL P&L lines")
    void ifrs9EclExpense() {
        assertThat(service.findByIfrs9Role(Ifrs9Role.ECL_EXPENSE))
            .extracting(ChartOfAccount::getCode)
            .containsExactly("5340", "5350");
    }

    @Test
    @DisplayName("@Cacheable populates the coa-by-code region after first call")
    void cachedFindByCode() {
        Cache cache = cacheManager.getCache(ChartOfAccountService.CACHE_BY_CODE);
        assertThat(cache).isNotNull();
        cache.clear();

        service.findByCode("2110");
        // Tenant prefix is "test-tenant:CODE" because @BeforeEach now sets the
        // TenantContext (otherwise the @Cacheable SpEL key resolves to null,
        // which Spring rejects with "Null key returned for cache operation").
        Cache.ValueWrapper hit = cache.get("test-tenant:2110");
        assertThat(hit).as("cache miss after first call — @Cacheable not wired")
            .isNotNull();
        assertThat(((ChartOfAccount) hit.get()).getCode()).isEqualTo("2110");
    }

    @Test
    @DisplayName("@Cacheable populates the coa-tree region after first call")
    void cachedGetTree() {
        Cache cache = cacheManager.getCache(ChartOfAccountService.CACHE_TREE);
        assertThat(cache).isNotNull();
        cache.clear();

        service.getTree();
        Cache.ValueWrapper hit = cache.get("test-tenant");
        assertThat(hit).as("tree cache miss after first call — @Cacheable not wired")
            .isNotNull();
    }

    /**
     * Spring slice tests need explicit caching configuration — {@code @DataJpaTest}
     * does not include {@code @EnableCaching}. A {@link ConcurrentMapCacheManager}
     * pre-creates the four cache regions used by {@link ChartOfAccountService}.
     */
    @TestConfiguration
    @EnableCaching
    static class TestCachingConfig {

        @Bean
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                ChartOfAccountService.CACHE_BY_CODE,
                ChartOfAccountService.CACHE_BY_IFRS17,
                ChartOfAccountService.CACHE_BY_IFRS9,
                ChartOfAccountService.CACHE_TREE);
        }
    }
}
