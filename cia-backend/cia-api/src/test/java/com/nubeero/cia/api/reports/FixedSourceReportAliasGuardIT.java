package com.nubeero.cia.api.reports;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.reports.domain.DataSource;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportField;
import com.nubeero.cia.reports.domain.ReportType;
import com.nubeero.cia.reports.repository.ReportDefinitionRepository;
import com.nubeero.cia.reports.service.ReportQueryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-regression guard: every fixed-source (non-business, {@code BASE_QUERIES}) SYSTEM
 * report's declared non-computed field key must resolve to a SELECT column alias in its
 * data source's base query. {@link ReportQueryBuilder#execute} maps fixed-source rows by
 * alias ({@code reprojectByAlias}) since the fixed-source-report-columns fix (Task 1) — that
 * mapping is only correct if every declared field key has a matching alias. This is the
 * structural check that was missing (no test ever asserted alias coverage), which is exactly
 * how the original column-misalignment bug rotted undetected since V44.
 *
 * @since closures-fixed-source-report-columns Task 2
 */
class FixedSourceReportAliasGuardIT extends FinanceWebItSupport {

    @Autowired ReportDefinitionRepository reportDefinitionRepository;
    @Autowired ReportQueryBuilder reportQueryBuilder;

    @Test
    void everyFixedSourceReportFieldKeyResolvesToASelectAlias() {
        List<ReportDefinition> defs = reportDefinitionRepository.findAll().stream()
                .filter(d -> d.getType() == ReportType.SYSTEM)                       // match SystemReportSmokeIT's filter
                .filter(d -> !reportQueryBuilder.isBusinessSource(d.getDataSource())) // fixed sources only
                .toList();
        assertThat(defs).as("15+ fixed-source SYSTEM reports").hasSizeGreaterThanOrEqualTo(15);

        Map<DataSource, List<String>> labelCache = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (ReportDefinition def : defs) {
            List<String> labels = labelCache.computeIfAbsent(def.getDataSource(),
                    reportQueryBuilder::fixedSourceColumnLabels);
            for (ReportField f : def.getConfig().getFields()) {
                if (f.isComputed()) continue;
                if (!labels.contains(f.getKey().toLowerCase(Locale.ROOT))) {
                    problems.add(def.getName() + " [" + def.getDataSource() + "] field '" + f.getKey()
                            + "' has no matching SELECT alias " + labels);
                }
            }
        }
        assertThat(problems).as("fixed-source field keys must all resolve to a SELECT alias").isEmpty();
    }

    /**
     * {@code reprojectByAlias} builds a lowercased-label → value map per row; a fixed source
     * whose SELECT emits two columns under the same lowercased alias would silently collapse
     * to "last write wins", corrupting whichever declared field maps to the earlier of the two
     * without any signal. No fixed source has a collision today — this pins that invariant so a
     * future SELECT edit that introduces one fails loudly instead of rotting undetected.
     */
    @Test
    void noFixedSourceHasADuplicateLowercasedColumnLabel() {
        List<ReportDefinition> defs = reportDefinitionRepository.findAll().stream()
                .filter(d -> d.getType() == ReportType.SYSTEM)
                .filter(d -> !reportQueryBuilder.isBusinessSource(d.getDataSource()))
                .toList();
        assertThat(defs).as("15+ fixed-source SYSTEM reports").hasSizeGreaterThanOrEqualTo(15);

        Map<DataSource, List<String>> labelCache = new HashMap<>();
        for (ReportDefinition def : defs) {
            labelCache.computeIfAbsent(def.getDataSource(), reportQueryBuilder::fixedSourceColumnLabels);
        }

        List<String> problems = new ArrayList<>();
        for (Map.Entry<DataSource, List<String>> entry : labelCache.entrySet()) {
            List<String> labels = entry.getValue();
            if (labels.size() == new HashSet<>(labels).size()) continue;
            Set<String> seen = new HashSet<>();
            Set<String> duplicates = new HashSet<>();
            for (String label : labels) {
                if (!seen.add(label)) duplicates.add(label);
            }
            problems.add(entry.getKey() + " has duplicate lowercased SELECT column label(s) " + duplicates
                    + " in " + labels);
        }
        assertThat(problems).as("no fixed source may emit two SELECT columns under the same lowercased alias")
                .isEmpty();
    }
}
