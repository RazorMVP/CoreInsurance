package com.nubeero.cia.api.upload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof that {@link com.nubeero.cia.common.upload.FileUploadValidator} is wired into a
 * real upload endpoint through the live security + MVC chain. Uses document-template upload
 * (`POST /api/v1/document-templates`) — the lightest of the 5 upload endpoints (no entity FKs to
 * seed; storage is the harness's no-op mock) — but the valid-201 path still calls
 * {@code file.getInputStream()} for the upload <em>after</em> the validator read the head, so this
 * also exercises the documented double-{@code getInputStream()} concern.
 *
 * <p>Template policy is htmlAndPdf/5 MB, so: a real PDF or HTML → 201; a PDF-declared file with
 * non-PDF bytes → 422 FILE_CONTENT_MISMATCH (spoof caught); an unlisted type → 422
 * UNSUPPORTED_FILE_TYPE.
 */
@WithMockUser(roles = "SETUP_UPDATE")
class DocumentTemplateUploadValidationIT extends FinanceWebItSupport {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // %PDF-1.4

    @Autowired MockMvc mvc;

    @Test
    void validPdf_returns201() throws Exception {
        mvc.perform(multipart("/api/v1/document-templates")
                        .file(new MockMultipartFile("file", "t.pdf", "application/pdf", PDF))
                        .param("templateType", "POLICY"))
                .andExpect(status().isCreated());
    }

    @Test
    void validHtml_returns201() throws Exception {
        mvc.perform(multipart("/api/v1/document-templates")
                        .file(new MockMultipartFile("file", "t.html", "text/html",
                                "<html><body>hi</body></html>".getBytes()))
                        .param("templateType", "ENDORSEMENT"))
                .andExpect(status().isCreated());
    }

    @Test
    void spoofedPdf_magicByteMismatch_returns422() throws Exception {
        mvc.perform(multipart("/api/v1/document-templates")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf",
                                "this is not a pdf".getBytes()))
                        .param("templateType", "POLICY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("FILE_CONTENT_MISMATCH"));
    }

    @Test
    void disallowedType_returns422() throws Exception {
        mvc.perform(multipart("/api/v1/document-templates")
                        .file(new MockMultipartFile("file", "x.zip", "application/zip", PDF))
                        .param("templateType", "POLICY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED_FILE_TYPE"));
    }
}
