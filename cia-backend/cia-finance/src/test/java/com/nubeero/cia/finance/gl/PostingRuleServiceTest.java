package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link PostingRuleService}. Caching is intentionally
 * not exercised here — {@code @Cacheable} requires a Spring context to weave
 * the interceptor. Cache wiring is covered at the IT level alongside the
 * full SubledgerPostingService flow.
 */
@ExtendWith(MockitoExtension.class)
class PostingRuleServiceTest {

    @Mock
    private PostingRuleRepository repository;

    @InjectMocks
    private PostingRuleService service;

    @Test
    @DisplayName("findByEventType returns the active rule for a known event type")
    void findByEventTypeHit() {
        PostingRule rule = newRule("POLICY_APPROVED", "1310", "2110");
        when(repository.findBySourceEventTypeAndIsActiveTrueAndDeletedAtIsNull("POLICY_APPROVED"))
            .thenReturn(Optional.of(rule));

        PostingRule resolved = service.findByEventType("POLICY_APPROVED");
        assertThat(resolved.getDebitAccountCode()).isEqualTo("1310");
        assertThat(resolved.getCreditAccountCode()).isEqualTo("2110");
    }

    @Test
    @DisplayName("findByEventType throws PostingRuleNotFoundException for unknown event type")
    void findByEventTypeMiss() {
        when(repository.findBySourceEventTypeAndIsActiveTrueAndDeletedAtIsNull("UNKNOWN_EVENT"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByEventType("UNKNOWN_EVENT"))
            .isInstanceOf(PostingRuleNotFoundException.class)
            .hasMessageContaining("UNKNOWN_EVENT");
    }

    @Test
    @DisplayName("findByEventType treats inactive rules as absent (repository contract)")
    void findByEventTypeInactiveTreatedAsAbsent() {
        // The repository's finder filters on is_active=TRUE, so an inactive rule
        // never returns from the Optional. Verify the service still surfaces a
        // clean miss rather than a partial result.
        when(repository.findBySourceEventTypeAndIsActiveTrueAndDeletedAtIsNull("DEACTIVATED"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByEventType("DEACTIVATED"))
            .isInstanceOf(PostingRuleNotFoundException.class);
    }

    private static PostingRule newRule(String eventType, String debit, String credit) {
        PostingRule rule = new PostingRule();
        rule.setSourceEventType(eventType);
        rule.setDebitAccountCode(debit);
        rule.setCreditAccountCode(credit);
        rule.setActive(true);
        return rule;
    }
}
