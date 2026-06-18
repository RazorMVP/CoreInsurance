package com.nubeero.cia.common.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nubeero.cia.common.exception.FileValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FileUploadValidatorTest {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31}; // %PDF-1
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final FileScanService scan = mock(FileScanService.class);
    private final FileUploadValidator validator = new FileUploadValidator(scan);
    private final FileUploadPolicy policy =
            FileUploadPolicy.imagesAndPdf("KYC document", FileUploadPolicy.mb(5));

    @Test
    void acceptsAllowedTypeWithMatchingMagicBytes_andCallsScan() {
        MockMultipartFile f = new MockMultipartFile("file", "id.pdf", "application/pdf", PDF);
        assertThatCode(() -> validator.validate(f, policy)).doesNotThrowAnyException();
        verify(scan).scan(f);
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile f = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> validator.validate(f, policy))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsDisallowedContentType() {
        MockMultipartFile f = new MockMultipartFile("file", "x.exe",
                "application/x-msdownload", PDF);
        assertThatThrownBy(() -> validator.validate(f, policy))
                .isInstanceOf(FileValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void rejectsOversizeFileWithPolicyCap() {
        byte[] big = new byte[(int) FileUploadPolicy.mb(6)];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        MockMultipartFile f = new MockMultipartFile("file", "big.pdf", "application/pdf", big);
        assertThatThrownBy(() -> validator.validate(f, policy))
                .isInstanceOf(FileValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "FILE_TOO_LARGE");
    }

    @Test
    void rejectsSpoofedContentType_magicByteMismatch() {
        // Declares image/png but the bytes are a PDF — signature mismatch.
        MockMultipartFile f = new MockMultipartFile("file", "fake.png", "image/png", PDF);
        assertThatThrownBy(() -> validator.validate(f, policy))
                .isInstanceOf(FileValidationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "FILE_CONTENT_MISMATCH");
    }

    @Test
    void acceptsPngWithRealPngBytes() {
        MockMultipartFile f = new MockMultipartFile("file", "real.png", "image/png", PNG);
        assertThatCode(() -> validator.validate(f, policy)).doesNotThrowAnyException();
    }

    @Test
    void htmlPolicy_acceptsHtml_noSignatureCheck() {
        FileUploadPolicy tpl = FileUploadPolicy.htmlAndPdf("template", FileUploadPolicy.mb(5));
        MockMultipartFile f = new MockMultipartFile("file", "t.html", "text/html",
                "<html></html>".getBytes());
        assertThatCode(() -> validator.validate(f, tpl)).doesNotThrowAnyException();
    }
}
