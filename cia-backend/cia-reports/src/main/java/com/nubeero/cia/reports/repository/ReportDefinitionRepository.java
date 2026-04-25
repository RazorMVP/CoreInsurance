package com.nubeero.cia.reports.repository;

import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ReportDefinitionRepository
        extends JpaRepository<ReportDefinition, UUID>,
                JpaSpecificationExecutor<ReportDefinition> {

    List<ReportDefinition> findByActiveTrueOrderByNameAsc();

    List<ReportDefinition> findByCategoryAndActiveTrueOrderByNameAsc(ReportCategory category);

    boolean existsByTypeAndName(ReportType type, String name);
}
