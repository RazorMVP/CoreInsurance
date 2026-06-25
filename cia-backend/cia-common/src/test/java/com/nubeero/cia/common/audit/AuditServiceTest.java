package com.nubeero.cia.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link AuditService}'s JSON serialisation — the path behind the
 * {@code policy-number-format-audit-json} bug: auditing an entity whose lazy
 * Hibernate proxy couldn't serialise made the old fallback write a non-JSON
 * {@code toString()} into the {@code jsonb} column → "invalid input syntax for
 * type json" → 500.
 *
 * <p>Invariant under test: <b>the audit value is ALWAYS valid JSON</b>, whatever
 * is thrown at it.
 */
class AuditServiceTest {

    private final ObjectMapper primaryMapper = new ObjectMapper();
    private AuditLogRepository repository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        when(repository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuditService(repository, primaryMapper, mock(ApplicationEventPublisher.class));
        service.configureAuditMapper();   // @PostConstruct — invoked by Spring in prod
    }

    private AuditLog loggedNewValue(Object newValue) {
        service.log("Thing", "id-1", AuditAction.CREATE, null, newValue);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void serialisesANormalValueWithItsFields() throws Exception {
        AuditLog logged = loggedNewValue(new Sample("POL", 6));
        var node = primaryMapper.readTree(logged.getNewValue());
        assertThat(node.get("prefix").asText()).isEqualTo("POL");
        assertThat(node.get("sequenceLength").asInt()).isEqualTo(6);
    }

    @Test
    void emitsValidJsonEvenWhenSerialisationFails() throws Exception {
        // A getter that throws forces Jackson to fail. The OLD code returned a raw
        // toString() here (non-JSON) which 500'd the JSONB insert. The fallback must
        // now emit valid JSON.
        AuditLog logged = loggedNewValue(new ExplodingGetter());
        assertThat(logged.getNewValue()).isNotNull();
        assertThatCode(() -> primaryMapper.readTree(logged.getNewValue()))
                .as("audit value must always be valid JSON, even on serialisation failure")
                .doesNotThrowAnyException();
    }

    @Test
    void auditMapperRegistersTheHibernateModule() {
        // The dedicated mapper must carry the Hibernate module so an uninitialized
        // lazy proxy serialises as null instead of throwing (the root fix).
        assertThat(service.auditMapper.getRegisteredModuleIds())
                .anyMatch(id -> id.toString().toLowerCase().contains("hibernate"));
    }

    @Test
    void initToleratesNullPrimaryMapper() {
        // A couple of IT stubs do `new AuditService(null, null, null)` + override
        // log(); @PostConstruct must not NPE on the null mapper at bean init.
        AuditService stub = new AuditService(null, null, mock(ApplicationEventPublisher.class));
        assertThatCode(stub::configureAuditMapper).doesNotThrowAnyException();
        assertThat(stub.auditMapper).isNotNull();
    }

    record Sample(String prefix, int sequenceLength) {
    }

    /** Serialising this throws (the getter blows up) — exercises the fallback. */
    static class ExplodingGetter {
        public String getBoom() {
            throw new IllegalStateException("cannot read");
        }
    }
}
