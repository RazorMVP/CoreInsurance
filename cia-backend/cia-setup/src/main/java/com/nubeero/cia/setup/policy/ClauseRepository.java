package com.nubeero.cia.setup.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClauseRepository extends JpaRepository<Clause, UUID> {

    Page<Clause> findAllByDeletedAtIsNull(Pageable pageable);

    /** Used by the snapshot resolver to map selected IDs → frozen text. */
    List<Clause> findAllByDeletedAtIsNull();
}
