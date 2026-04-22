package com.nubeero.cia.workflow.niid;

import com.nubeero.cia.integrations.niid.NiidUploadResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface NiidUploadActivity {

    String fetchPolicyPayload(String policyId, String tenantId);

    NiidUploadResult uploadToNiid(String policyId, String tenantId, String policyJson);

    void updatePolicyNiidRef(String policyId, String tenantId, String niidRef);
}
