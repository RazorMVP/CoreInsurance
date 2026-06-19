package com.nubeero.cia.setup.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.clause.ClauseSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the novel {@link ClauseService#snapshot} resolver (order + skip-unknown). */
class ClauseServiceSnapshotTest {

    private final ClauseRepository repository = mock(ClauseRepository.class);
    private final ClauseService service = new ClauseService(repository, mock(AuditService.class));

    private Clause clause(UUID id, String title, String text) {
        Clause c = Clause.builder()
                .title(title).text(text)
                .type(ClauseType.STANDARD).applicability(ClauseApplicability.OPTIONAL)
                .build();
        c.setId(id);
        return c;
    }

    @Test
    void resolvesInRequestedOrder_skippingUnknownIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findAllByDeletedAtIsNull()).thenReturn(List.of(
                clause(a, "Alpha", "Alpha text"),
                clause(b, "Beta", "Beta text")));

        // Request order b, missing, a — the result keeps that order and drops the unknown id.
        List<ClauseSnapshot> result = service.snapshot(
                List.of(b.toString(), "00000000-0000-0000-0000-000000000999", a.toString()));

        assertThat(result).extracting(ClauseSnapshot::title).containsExactly("Beta", "Alpha");
        assertThat(result).extracting(ClauseSnapshot::id).containsExactly(b.toString(), a.toString());
        assertThat(result.get(0).text()).isEqualTo("Beta text");
        assertThat(result.get(0).type()).isEqualTo("STANDARD");
    }

    @Test
    void emptyOrNullIds_returnEmpty() {
        assertThat(service.snapshot(List.of())).isEmpty();
        assertThat(service.snapshot(null)).isEmpty();
    }
}
