package com.nubeero.cia.integrations.kyc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class MockKycServiceTest {

    private final MockKycService service = new MockKycService();

    @Test
    void verifyIndividualDoesNotLogIdNumber(CapturedOutput output) {
        service.verifyIndividual(IndividualKycRequest.builder()
                .idType("NIN")
                .idNumber("12345678901")
                .firstName("Ada")
                .lastName("Okafor")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .build());

        assertThat(output).contains("[MOCK KYC] verifyIndividual idType=NIN");
        assertThat(output).doesNotContain("12345678901");
    }

    @Test
    void verifyCorporateDoesNotLogRegistrationNumber(CapturedOutput output) {
        service.verifyCorporate(CorporateKycRequest.builder()
                .rcNumber("RC123456")
                .companyName("Acme Insurance Buyer")
                .build());

        assertThat(output).contains("[MOCK KYC] verifyCorporate companyName=Acme Insurance Buyer");
        assertThat(output).doesNotContain("RC123456");
    }

    @Test
    void verifyDirectorDoesNotLogIdNumber(CapturedOutput output) {
        service.verifyDirector(DirectorKycRequest.builder()
                .idType("PASSPORT")
                .idNumber("A12345678")
                .firstName("Ada")
                .lastName("Okafor")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .build());

        assertThat(output).contains("[MOCK KYC] verifyDirector idType=PASSPORT");
        assertThat(output).doesNotContain("A12345678");
    }
}
