package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, UUID> {

    Optional<ChartOfAccount> findByCodeAndDeletedAtIsNull(String code);

    List<ChartOfAccount> findByIfrs17RoleAndDeletedAtIsNullOrderByCodeAsc(Ifrs17Role ifrs17Role);

    List<ChartOfAccount> findByIfrs9RoleAndDeletedAtIsNullOrderByCodeAsc(Ifrs9Role ifrs9Role);

    List<ChartOfAccount> findByDeletedAtIsNullOrderByCodeAsc();
}
