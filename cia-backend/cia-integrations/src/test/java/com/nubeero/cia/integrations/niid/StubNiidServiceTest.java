package com.nubeero.cia.integrations.niid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class StubNiidServiceTest {

    @Test
    void uploadPolicyDoesNotLogPolicyVehicleOrPayload(CapturedOutput output) {
        StubNiidService service = new StubNiidService();

        NiidUploadResult result = service.uploadPolicy(NiidUploadRequest.builder()
                .tenantId("tenant-alpha")
                .policyNumber("POL-SECRET-001")
                .vehicleRegNumber("ABC-123XY")
                .policyJson("{\"chassisNumber\":\"CHS123456\"}")
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(output).contains("[NIID STUB] Uploaded policy using stub adapter");
        assertThat(output).doesNotContain("POL-SECRET-001");
        assertThat(output).doesNotContain("ABC-123XY");
        assertThat(output).doesNotContain("CHS123456");
    }
}
