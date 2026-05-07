package com.nubeero.cia.common.upload;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadSecurityPolicyTest {

    @Test
    void acceptsAllowedFileWithinCategoryLimit() {
        UploadSecurityPolicy policy = policyWithLimits(MalwareScanResult.cleanResult(), 10, 10);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf".getBytes());

        ValidatedUpload upload = policy.validate(UploadCategory.CLAIM_DOCUMENT, file);

        assertThat(upload.originalFilename()).isEqualTo("report.pdf");
        assertThat(upload.safeFilename()).isEqualTo("report.pdf");
        assertThat(upload.contentType()).isEqualTo("application/pdf");
        assertThat(upload.size()).isEqualTo(3);
    }

    @Test
    void rejectsOversizedKycUpload() {
        UploadSecurityPolicy policy = policyWithLimits(MalwareScanResult.cleanResult(), 2, 10);
        MockMultipartFile file = new MockMultipartFile(
                "file", "id.pdf", "application/pdf", "pdf".getBytes());

        assertThatThrownBy(() -> policy.validate(UploadCategory.KYC_DOCUMENT, file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsMismatchedExtensionAndContentType() {
        UploadSecurityPolicy policy = policyWithLimits(MalwareScanResult.cleanResult(), 10, 10);
        MockMultipartFile file = new MockMultipartFile(
                "file", "id.exe", "application/pdf", "pdf".getBytes());

        assertThatThrownBy(() -> policy.validate(UploadCategory.KYC_DOCUMENT, file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("extension");
    }

    @Test
    void rejectsFileWhenScannerReportsMalware() {
        UploadSecurityPolicy policy = policyWithLimits(MalwareScanResult.infectedResult("malware signature"), 10, 10);
        MockMultipartFile file = new MockMultipartFile(
                "file", "id.png", "image/png", "png".getBytes());

        assertThatThrownBy(() -> policy.validate(UploadCategory.KYC_DOCUMENT, file))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("malware signature");
    }

    private UploadSecurityPolicy policyWithLimits(MalwareScanResult scanResult, long kycMax, long claimMax) {
        UploadSecurityPolicy policy = new UploadSecurityPolicy((filename, contentType, content) -> scanResult);
        ReflectionTestUtils.setField(policy, "kycMaxBytes", kycMax);
        ReflectionTestUtils.setField(policy, "claimMaxBytes", claimMax);
        return policy;
    }
}
