package com.nubeero.cia.workflow.naicom;

import com.nubeero.cia.integrations.naicom.NaicomUploadResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface NaicomUploadActivity {

    String fetchPolicyPayload(String policyId, String tenantId);

    NaicomUploadResult uploadToNaicom(String policyId, String tenantId, String policyJson);

    void updatePolicyCertificate(String policyId, String tenantId, String naicomUid);
}
