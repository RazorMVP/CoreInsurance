package com.nubeero.cia.finance.gl;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PeriodLockInterceptor} as the SessionFactory-scoped
 * Hibernate interceptor so every persisted entity flows through the lock
 * check.
 *
 * <h2>Why a separate customizer (not folded into HibernateMultiTenantConfig)</h2>
 * <p>{@code cia-common} cannot depend on {@code cia-finance} (it would
 * invert the module DAG — finance depends on common). Keeping each
 * cross-cutting concern (multi-tenancy in common, period lock in finance)
 * as its own {@link HibernatePropertiesCustomizer} bean lets Spring merge
 * them into the final Hibernate properties map without either module
 * knowing about the other.
 *
 * <h2>ObjectProvider for lazy resolution</h2>
 * <p>The interceptor itself depends (lazily) on {@code PeriodLockService},
 * which depends on JPA repositories that need the EntityManagerFactory.
 * Asking Spring to inject the interceptor directly into this customizer
 * would create a circular dependency at startup. {@link ObjectProvider}
 * defers the bean lookup until the customizer is invoked — by which point
 * the full context is being assembled and the lazy proxy can resolve.
 *
 * @since Module 12, Slice 1.7
 */
@Configuration
public class PeriodLockInterceptorConfig {

    @Bean
    public HibernatePropertiesCustomizer periodLockHibernateCustomizer(
        ObjectProvider<PeriodLockInterceptor> interceptorProvider) {
        return props -> props.put(AvailableSettings.INTERCEPTOR, interceptorProvider.getObject());
    }
}
