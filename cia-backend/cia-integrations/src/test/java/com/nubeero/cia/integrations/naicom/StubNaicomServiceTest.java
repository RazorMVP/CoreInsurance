package com.nubeero.cia.integrations.naicom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class StubNaicomServiceTest {

    @Test
    void uploadPolicyDoesNotLogPolicyNumberOrPayload(CapturedOutput output) {
        StubNaicomService service = new StubNaicomService();

        NaicomUploadResult result = service.uploadPolicy(NaicomUploadRequest.builder()
                .tenantId("tenant-alpha")
                .policyNumber("POL-SECRET-001")
                .policyJson("{\"customerTin\":\"12345678\"}")
                .build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(output).contains("[NAICOM STUB] Uploaded policy using stub adapter");
        assertThat(output).doesNotContain("POL-SECRET-001");
        assertThat(output).doesNotContain("12345678");
    }
}
