# File-Upload Validation (H3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add server-side validation (size cap + MIME allowlist + magic-byte sniff + configurable virus-scan hook) to all 5 file-upload endpoints, raise the Spring multipart caps (fixing the silent 1 MB→500 bug), and add a 413 handler. Closes backlog `file-upload-validation` (P1).

**Architecture:** A single reusable `FileUploadValidator` (`@Component`) lives in **cia-common** (not cia-storage — cia-storage lacks spring-web, so `MultipartFile` isn't available there; cia-common already has spring-webmvc). Each upload site builds a `FileUploadPolicy` (allowed content types + max size) and calls `validator.validate(file, policy)` **before** streaming to `DocumentStorageService`. Validation failures throw `FileValidationException` (extends `CiaException` → HTTP 422, handled by the existing `GlobalExceptionHandler`). The servlet-level hard cap rejects truly-huge uploads with `MaxUploadSizeExceededException` → a new 413 handler. A `FileScanService` SPI (no-op default, pluggable later) satisfies the CLAUDE.md "virus scan (configurable)" standard.

**Tech Stack:** Java 21, Spring Boot 3.5.14, JUnit 5 + AssertJ + Mockito (cia-common already has `spring-boot-starter-test` — it hosts `GlobalExceptionHandlerMvcTest`), Spring `MockMultipartFile` for tests, Testcontainers for the representative end-to-end IT.

---

## Status / size model (read first)

Two independent size limits, by design:

- **Servlet hard cap** (`spring.servlet.multipart.max-file-size = 15MB`, `max-request-size = 75MB`): the outer safety net. A request exceeding it never reaches a controller — Spring throws `MaxUploadSizeExceededException`. Mapped to **413 PAYLOAD_TOO_LARGE**.
- **Per-policy cap** (e.g. KYC 5 MB, claim docs 10 MB, templates 5 MB): a business rule checked inside `FileUploadValidator`. Exceeding it throws `FileValidationException("FILE_TOO_LARGE", …)` → **422** with a human message ("max 5 MB for KYC documents"). The servlet cap (15 MB) sits above every policy cap so policy violations always surface as a clean 422, and 413 only fires for genuinely abusive uploads.

Allowed-type policies (content shape, not domain-named, to avoid coupling cia-common to domain knowledge):

| Preset | Content types | Magic-byte checked? |
|---|---|---|
| `imagesAndPdf` | `application/pdf`, `image/jpeg`, `image/png` | yes (all three) |
| `htmlAndPdf` | `text/html`, `application/pdf` | pdf yes; html has no signature → content-type only |

Endpoint → policy:

| Endpoint | Policy | Max |
|---|---|---|
| `POST /customers/individual` (idDocument) | imagesAndPdf | 5 MB |
| `POST /customers/corporate` (cacCertificate + each director doc) | imagesAndPdf | 5 MB |
| `PUT /customers/{id}` (idDocument + director docs) | imagesAndPdf | 5 MB |
| `POST /claims/{claimId}/documents` (file) | imagesAndPdf | 10 MB |
| `POST /document-templates` (file) | htmlAndPdf | 5 MB |

---

## File Structure

**New (all in `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/`):**
- `upload/FileUploadPolicy.java` — value object: allowed content types + max bytes + label, with `imagesAndPdf(label, maxBytes)` / `htmlAndPdf(label, maxBytes)` factories.
- `upload/FileSignatures.java` — content-type → magic-byte prefix(es); `matches(contentType, head)`.
- `upload/FileUploadValidator.java` — `@Component`; orchestrates empty → size → allowlist → magic-byte → scan.
- `upload/FileScanService.java` — SPI: `void scan(MultipartFile file)`.
- `upload/NoOpFileScanService.java` — `@Component @ConditionalOnProperty(... matchIfMissing=true)` default.
- `exception/FileValidationException.java` — extends `CiaException` (422).
- `upload/FileUploadValidatorTest.java` (test) — exhaustive unit tests.

**Modified:**
- `cia-common/.../exception/GlobalExceptionHandler.java` — add `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 413.
- `cia-common/.../exception/GlobalExceptionHandlerMvcTest.java` (test) — add a 413 case.
- `cia-api/src/main/resources/application.yml` — add `spring.servlet.multipart`.
- `cia-customer/.../CustomerService.java` — inject validator; validate at the 3 upload sites.
- `cia-claims/.../ClaimDocumentService.java` — inject validator; validate.
- `cia-documents/.../DocumentTemplateService.java` — inject validator; validate.
- `cia-api/.../<representative multipart IT>` — one end-to-end IT (claim-document upload).
- `CLAUDE.md` — §8 + the env-var table (scan provider).
- `cia-log.md` — slice entry + backlog reconciliation.

---

### Task 1: `FileUploadPolicy` + `FileSignatures`

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileUploadPolicy.java`
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileSignatures.java`

- [ ] **Step 1: Write `FileUploadPolicy`**

```java
package com.nubeero.cia.common.upload;

import java.util.Set;

/**
 * Per-upload-site validation policy: the allowed content types and the maximum size.
 * {@code label} is used in 422 messages (e.g. "KYC document").
 */
public record FileUploadPolicy(String label, Set<String> allowedContentTypes, long maxSizeBytes) {

    private static final Set<String> IMAGES_AND_PDF =
            Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> HTML_AND_PDF =
            Set.of("text/html", "application/pdf");

    public static FileUploadPolicy imagesAndPdf(String label, long maxSizeBytes) {
        return new FileUploadPolicy(label, IMAGES_AND_PDF, maxSizeBytes);
    }

    public static FileUploadPolicy htmlAndPdf(String label, long maxSizeBytes) {
        return new FileUploadPolicy(label, HTML_AND_PDF, maxSizeBytes);
    }

    public static long mb(long n) {
        return n * 1024 * 1024;
    }
}
```

- [ ] **Step 2: Write `FileSignatures`**

```java
package com.nubeero.cia.common.upload;

import java.util.List;
import java.util.Map;

/**
 * Magic-byte signatures for the binary content types we accept, so a spoofed
 * {@code Content-Type} cannot smuggle a different (e.g. executable) payload past the
 * allowlist. Types with no reliable signature (text/html) are absent and skip the check.
 */
public final class FileSignatures {

    private FileSignatures() {}

    // Each value is the list of acceptable leading-byte prefixes for that content type.
    private static final Map<String, List<byte[]>> SIGNATURES = Map.of(
            "application/pdf", List.of(new byte[] {0x25, 0x50, 0x44, 0x46}),               // %PDF
            "image/jpeg",      List.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "image/png",       List.of(new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));

    /** Longest prefix we ever need to read from the stream head. */
    public static final int MAX_PREFIX = 8;

    /**
     * @return true if {@code contentType} has no known signature (nothing to check) OR
     *         {@code head} starts with one of its signatures. false only when a known
     *         signature exists and {@code head} matches none of them.
     */
    public static boolean matches(String contentType, byte[] head) {
        List<byte[]> sigs = SIGNATURES.get(contentType);
        if (sigs == null) return true; // no signature for this type (e.g. text/html)
        for (byte[] sig : sigs) {
            if (startsWith(head, sig)) return true;
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileUploadPolicy.java \
        cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileSignatures.java
git commit -m "feat(upload): FileUploadPolicy + FileSignatures (magic-byte map)"
```

---

### Task 2: `FileValidationException`

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/exception/FileValidationException.java`

- [ ] **Step 1: Write the exception** (mirrors `BusinessRuleException`, → 422)

```java
package com.nubeero.cia.common.exception;

import org.springframework.http.HttpStatus;

/** Server-side file-upload validation failure (bad type, too large, signature mismatch, scan reject). */
public class FileValidationException extends CiaException {

    public FileValidationException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/exception/FileValidationException.java
git commit -m "feat(upload): FileValidationException (422)"
```

---

### Task 3: `FileScanService` SPI + no-op default

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileScanService.java`
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/NoOpFileScanService.java`

- [ ] **Step 1: Write the SPI**

```java
package com.nubeero.cia.common.upload;

import org.springframework.web.multipart.MultipartFile;

/**
 * Pluggable virus/malware scan hook (CLAUDE.md "virus scan on upload (configurable)").
 * Implementations throw {@link com.nubeero.cia.common.exception.FileValidationException}
 * on a positive detection. Active impl selected by {@code cia.upload.scan.provider}.
 */
public interface FileScanService {
    void scan(MultipartFile file);
}
```

- [ ] **Step 2: Write the no-op default**

```java
package com.nubeero.cia.common.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Default scan impl — does nothing (clean). A real ClamAV/API impl registers under a
 * different {@code cia.upload.scan.provider} value. matchIfMissing so uploads work
 * out-of-the-box with no scanner configured.
 */
@Component
@ConditionalOnProperty(name = "cia.upload.scan.provider", havingValue = "none", matchIfMissing = true)
public class NoOpFileScanService implements FileScanService {
    @Override
    public void scan(MultipartFile file) {
        // no-op
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileScanService.java \
        cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/NoOpFileScanService.java
git commit -m "feat(upload): FileScanService SPI + no-op default"
```

---

### Task 4: `FileUploadValidator` (TDD — the core)

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileUploadValidator.java`
- Test: `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/upload/FileUploadValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubeero.cia.common.upload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
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
```

- [ ] **Step 2: Run — verify it fails** (`FileUploadValidator` doesn't exist)

Run: `mvn -pl cia-common test -Dtest=FileUploadValidatorTest`
Expected: COMPILATION ERROR / FAIL.

- [ ] **Step 3: Write `FileUploadValidator`**

```java
package com.nubeero.cia.common.upload;

import com.nubeero.cia.common.exception.FileValidationException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Server-side upload guard, applied at every upload site BEFORE streaming to storage:
 * not-empty → size ≤ policy cap → declared content-type in allowlist → magic-byte sniff
 * (spoof defence) → pluggable scan. Throws {@link FileValidationException} (422) on any
 * violation. The servlet-level {@code max-file-size} (→ 413) is a separate outer net.
 */
@Component
public class FileUploadValidator {

    private final FileScanService fileScanService;

    public FileUploadValidator(FileScanService fileScanService) {
        this.fileScanService = fileScanService;
    }

    public void validate(MultipartFile file, FileUploadPolicy policy) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("EMPTY_FILE",
                    "The uploaded " + policy.label() + " is empty");
        }
        if (file.getSize() > policy.maxSizeBytes()) {
            throw new FileValidationException("FILE_TOO_LARGE",
                    policy.label() + " exceeds the " + (policy.maxSizeBytes() / (1024 * 1024))
                            + " MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !policy.allowedContentTypes().contains(contentType)) {
            throw new FileValidationException("UNSUPPORTED_FILE_TYPE",
                    policy.label() + " type '" + contentType + "' is not allowed (accepted: "
                            + String.join(", ", policy.allowedContentTypes()) + ")");
        }
        byte[] head = readHead(file);
        if (!FileSignatures.matches(contentType, head)) {
            throw new FileValidationException("FILE_CONTENT_MISMATCH",
                    policy.label() + " content does not match its declared type '" + contentType + "'");
        }
        fileScanService.scan(file);
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[FileSignatures.MAX_PREFIX];
            int read = in.readNBytes(buf, 0, buf.length);
            if (read == buf.length) return buf;
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        } catch (IOException e) {
            throw new FileValidationException("FILE_UNREADABLE",
                    "Could not read the uploaded " + policy(file));
        }
    }

    private String policy(MultipartFile file) {
        return file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
    }
}
```

- [ ] **Step 4: Run — verify green**

Run: `mvn -pl cia-common test -Dtest=FileUploadValidatorTest`
Expected: Tests run: 7, Failures: 0.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/upload/FileUploadValidator.java \
        cia-backend/cia-common/src/test/java/com/nubeero/cia/common/upload/FileUploadValidatorTest.java
git commit -m "feat(upload): FileUploadValidator (size + MIME allowlist + magic-byte + scan) with unit tests"
```

---

### Task 5: 413 handler for the servlet hard cap

**Files:**
- Modify: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/exception/GlobalExceptionHandler.java`
- Test: `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/exception/GlobalExceptionHandlerMvcTest.java`

- [ ] **Step 1: Add the handler** (place above the catch-all `handleUnexpected`)

```java
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded servlet max size: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("PAYLOAD_TOO_LARGE",
                        "The uploaded file exceeds the maximum allowed size"));
    }
```

- [ ] **Step 2: Add an MVC test case** mirroring the existing `GlobalExceptionHandlerMvcTest` style: a stub controller method that throws `new MaxUploadSizeExceededException(1L)`, asserts `status().isPayloadTooLarge()` and `jsonPath("$.errors[0].code").value("PAYLOAD_TOO_LARGE")`. (Read the existing test first to match its harness — it already wires a test controller + the advice.)

- [ ] **Step 3: Run**

Run: `mvn -pl cia-common test -Dtest=GlobalExceptionHandlerMvcTest`
Expected: green (existing + new case).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/exception/GlobalExceptionHandler.java \
        cia-backend/cia-common/src/test/java/com/nubeero/cia/common/exception/GlobalExceptionHandlerMvcTest.java
git commit -m "feat(upload): 413 handler for MaxUploadSizeExceededException"
```

---

### Task 6: Spring multipart caps

**Files:**
- Modify: `cia-backend/cia-api/src/main/resources/application.yml`

- [ ] **Step 1: Add under the existing `spring:` block** (e.g. after `application:`):

```yaml
  servlet:
    multipart:
      # Outer hard cap (the per-endpoint FileUploadValidator policy caps — 5/10 MB —
      # are below this, so a policy violation surfaces as 422; this 413s only abusive
      # uploads). Fixes the silent 1 MB default that 500'd the 5 MB KYC upload.
      max-file-size: 15MB
      max-request-size: 75MB
```

- [ ] **Step 2: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/application.yml
git commit -m "fix(upload): raise multipart caps to 15MB/75MB (was silent 1MB default)"
```

---

### Task 7: Wire into cia-customer (3 sites)

**Files:**
- Modify: `cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java`

- [ ] **Step 1: Inject the validator** — add to the `@RequiredArgsConstructor` field list (after `documentStorageService`):

```java
    private final com.nubeero.cia.common.upload.FileUploadValidator fileUploadValidator;
```

- [ ] **Step 2: Add a policy constant** (class-level):

```java
    private static final com.nubeero.cia.common.upload.FileUploadPolicy KYC_POLICY =
            com.nubeero.cia.common.upload.FileUploadPolicy.imagesAndPdf(
                    "KYC document", com.nubeero.cia.common.upload.FileUploadPolicy.mb(5));
```

- [ ] **Step 3: Validate in `uploadKycDocument`** — after the empty guard, before building the path. Because `createIndividual`/`createCorporate`/`update` all funnel uploads through `uploadKycDocument`, validating there covers all three endpoints in one place:

```java
    private String uploadKycDocument(MultipartFile file, UUID customerId, String docKey) {
        if (file == null || file.isEmpty()) return null;
        fileUploadValidator.validate(file, KYC_POLICY);
        String tenantId = ...; // unchanged below
```

Note: the corporate director loop already null/empty-checks each `dirDoc` before calling `uploadKycDocument`, so optional missing docs stay skipped; present ones get validated.

- [ ] **Step 4: Run the customer module build** to confirm it compiles.

Run: `mvn -pl cia-customer test -Dtest='Customer*'` (or `-q compile` if no unit tests target this)
Expected: compiles; existing tests unaffected.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-customer/src/main/java/com/nubeero/cia/customer/CustomerService.java
git commit -m "feat(upload): validate KYC/CAC/director uploads in CustomerService"
```

---

### Task 8: Wire into cia-claims

**Files:**
- Modify: `cia-backend/cia-claims/src/main/java/com/nubeero/cia/claims/ClaimDocumentService.java`

- [ ] **Step 1: Inject + policy constant** (claim docs 10 MB):

```java
    private final com.nubeero.cia.common.upload.FileUploadValidator fileUploadValidator;

    private static final com.nubeero.cia.common.upload.FileUploadPolicy CLAIM_DOC_POLICY =
            com.nubeero.cia.common.upload.FileUploadPolicy.imagesAndPdf(
                    "claim document", com.nubeero.cia.common.upload.FileUploadPolicy.mb(10));
```

- [ ] **Step 2: Validate in `upload`** — replace the bare empty-check with the validator call (validator covers empty + size + type + sniff). Keep the existing claim-status guard after it:

```java
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded file is empty");
        }
        fileUploadValidator.validate(file, CLAIM_DOC_POLICY);
        Claim claim = ...; // unchanged
```

- [ ] **Step 3: Run**

Run: `mvn -pl cia-claims test -Dtest='ClaimDocument*'` (or compile)
Expected: compiles; existing tests unaffected.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-claims/src/main/java/com/nubeero/cia/claims/ClaimDocumentService.java
git commit -m "feat(upload): validate claim-document uploads"
```

---

### Task 9: Wire into cia-documents

**Files:**
- Modify: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/DocumentTemplateService.java`

- [ ] **Step 1: Inject + policy constant** (htmlAndPdf, 5 MB):

```java
    private final com.nubeero.cia.common.upload.FileUploadValidator fileUploadValidator;

    private static final com.nubeero.cia.common.upload.FileUploadPolicy TEMPLATE_POLICY =
            com.nubeero.cia.common.upload.FileUploadPolicy.htmlAndPdf(
                    "document template", com.nubeero.cia.common.upload.FileUploadPolicy.mb(5));
```

(Confirm the class is `@RequiredArgsConstructor`/has a constructor for `storageService` + `repository`; add the field consistently.)

- [ ] **Step 2: Validate at the top of `upload`** (before the deactivate-existing logic):

```java
            MultipartFile file) throws IOException {
        fileUploadValidator.validate(file, TEMPLATE_POLICY);
        // Deactivate any existing active templates ... (unchanged)
```

Note: the service hardcodes `"text/html"` to storage; validation uses the *declared* `file.getContentType()` against the htmlAndPdf allowlist, so a real PDF template also passes (and is still stored — storage mime is a separate concern, out of scope).

- [ ] **Step 3: Run**

Run: `mvn -pl cia-documents test` (or compile)
Expected: compiles; existing tests unaffected.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/DocumentTemplateService.java
git commit -m "feat(upload): validate document-template uploads"
```

---

### Task 10: Representative end-to-end multipart IT

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/upload/ClaimDocumentUploadValidationIT.java`

Pick claim-document upload as the representative endpoint (single MultipartFile, clear setup). Reuse the existing full-context MockMvc harness pattern (`FinanceWebItSupport` or the claims IT support if one exists — read the cia-claims/cia-api claim ITs first to find the lightest harness that can seed a claim).

- [ ] **Step 1: Write the IT** with three `multipart(...)` cases against `POST /api/v1/claims/{claimId}/documents`:
  - valid PDF (`%PDF` bytes, `application/pdf`) → **201**;
  - spoofed (`image/png` declared, PDF bytes) → **422** `FILE_CONTENT_MISMATCH`;
  - disallowed type (`application/zip`) → **422** `UNSUPPORTED_FILE_TYPE`.

  Use `MockMvcRequestBuilders.multipart(...).file(new MockMultipartFile("file", name, contentType, bytes)).param("documentType", "INCIDENT_REPORT")` with a `@WithMockUser(authorities = "ROLE_CLAIMS_CREATE")` (or the harness's jwt() post-processor). Seed a claim via the harness fixtures so `claimId` resolves.

- [ ] **Step 2: Run**

Run: `mvn -pl cia-api test-compile failsafe:integration-test -Dit.test=ClaimDocumentUploadValidationIT`
Expected: Tests run: 3, Failures: 0.

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/upload/ClaimDocumentUploadValidationIT.java
git commit -m "test(upload): end-to-end multipart validation IT (claim docs: 201/422 spoof/422 type)"
```

---

### Task 11: Docs + cia-log + backlog reconciliation

**Files:**
- Modify: `CLAUDE.md` (§8 Security — a file-upload-validation paragraph; env-var table — `CIA_UPLOAD_SCAN_PROVIDER`).
- Modify: `cia-log.md` (slice entry + remove `file-upload-validation` from the backlog table).

- [ ] **Step 1: CLAUDE.md** — add a §8 paragraph describing `FileUploadValidator` (cia-common), the policy/allowlist/magic-byte/scan model, the 413-vs-422 split, and the multipart caps; add `CIA_UPLOAD_SCAN_PROVIDER` (default `none`) to the env-var table.

- [ ] **Step 2: cia-log.md** — add a `## 2026-06-18 — Slice H3: file-upload validation — COMPLETE` entry (files, the 413/422 model, the magic-byte spoof defence, the no-op scan SPI). Remove the `file-upload-validation` row from the backlog table; "Known follow-ups": **added** a P3 row for the live virus-scan impl (`upload-virus-scan-impl` — ClamAV/API on top of the SPI), and note the `kind-smoke-job-name` P3 drift from H2 if not already logged.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(upload): CLAUDE.md §8 + env var + cia-log H3 entry + backlog reconciliation"
```

---

## Self-Review

**Spec coverage:** size cap (Task 4/6), MIME allowlist (Task 4), magic-byte sniff (Task 1/4), virus-scan hook (Task 3), 413 handler (Task 5), 1 MB-bug fix (Task 6), all 5 endpoints wired (Tasks 7-9, 3 via the shared `uploadKycDocument`), end-to-end proof (Task 10), docs/backlog (Task 11). ✓

**Placeholder scan:** every code step has complete code except Task 5/10 test bodies, which give exact assertions + the instruction to match the existing harness (read-first) — acceptable because the harness shape must be read live, not guessed.

**Type consistency:** `FileUploadValidator(FileScanService)` constructor matches the test + the `@Component` wiring; `FileUploadPolicy.imagesAndPdf/htmlAndPdf/mb` used consistently across Tasks 1/4/7/8/9; `FileValidationException(code, msg)` signature consistent; error codes (`EMPTY_FILE`, `FILE_TOO_LARGE`, `UNSUPPORTED_FILE_TYPE`, `FILE_CONTENT_MISMATCH`, `PAYLOAD_TOO_LARGE`) consistent between validator, tests, and IT.

**Known risk (flagged):** `FileUploadValidator.readHead` opens `file.getInputStream()`; the call sites then open it again for upload. Spring's `StandardMultipartFile` (Tomcat, buffered) supports multiple `getInputStream()` calls, and Task 10's valid-upload case proves the round-trip end-to-end. If the IT's 201 case fails on a consumed stream, switch `readHead` + the upload to read `file.getBytes()` once and stream from a `ByteArrayInputStream`.
