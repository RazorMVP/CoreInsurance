package com.nubeero.cia.compliance.purge;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface CompliancePurgeActivities {
    /** Active tenant schemas from public.tenants (runs with no tenant context → resolver = public). */
    @ActivityMethod
    List<String> listActiveTenants();

    /** Window-gate + (if matched) purge one tenant. */
    @ActivityMethod
    PurgeTenantResult purgeTenant(String schema);
}
