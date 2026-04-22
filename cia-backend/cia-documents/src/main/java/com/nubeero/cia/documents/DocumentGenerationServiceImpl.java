package com.nubeero.cia.documents;

import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.entry;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentGenerationServiceImpl implements DocumentGenerationService {

    private final DocumentTemplateRepository templateRepository;
    private final DocumentStorageService storageService;
    private final HtmlToPdfConverter pdfConverter;
    @Qualifier("documentTemplateEngine")
    private final TemplateEngine templateEngine;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // ─── Public API ───────────────────────────────────────────────────────

    @Override
    public String generatePolicyDocument(PolicyDocumentContext ctx) {
        try {
            String html = resolveAndRender(
                    DocumentTemplateType.POLICY, ctx.productId(), ctx.classOfBusinessId(),
                    Map.ofEntries(
                            entry("policyNumber",       ctx.policyNumber()),
                            entry("customerName",       ctx.customerName()),
                            entry("productName",        ctx.productName()),
                            entry("classOfBusinessName",ctx.classOfBusinessName()),
                            entry("policyStartDate",    fmt(ctx.policyStartDate())),
                            entry("policyEndDate",      fmt(ctx.policyEndDate())),
                            entry("totalSumInsured",    ctx.totalSumInsured().toPlainString()),
                            entry("netPremium",         ctx.netPremium().toPlainString()),
                            entry("currencyCode",       ctx.currencyCode()),
                            entry("approvedBy",         ctx.approvedBy()),
                            entry("approvedDate",       fmt(ctx.approvedDate())),
                            entry("notes",              ctx.notes() != null ? ctx.notes() : "")
                    ));
            byte[] pdf = pdfConverter.convert(html);
            String path = "documents/policies/" + ctx.policyId() + "/policy-certificate.pdf";
            store(path, pdf);
            return path;
        } catch (Exception ex) {
            log.error("Policy document generation failed for {}: {}", ctx.policyNumber(), ex.getMessage(), ex);
            return null;
        }
    }

    @Override
    public String generateEndorsementDocument(EndorsementDocumentContext ctx) {
        try {
            String html = resolveAndRender(
                    DocumentTemplateType.ENDORSEMENT, ctx.productId(), ctx.classOfBusinessId(),
                    Map.ofEntries(
                            entry("endorsementNumber",  ctx.endorsementNumber()),
                            entry("policyNumber",       ctx.policyNumber()),
                            entry("customerName",       ctx.customerName()),
                            entry("endorsementType",    ctx.endorsementType()),
                            entry("effectiveDate",      fmt(ctx.effectiveDate())),
                            entry("policyEndDate",      fmt(ctx.policyEndDate())),
                            entry("oldSumInsured",      ctx.oldSumInsured().toPlainString()),
                            entry("newSumInsured",      ctx.newSumInsured().toPlainString()),
                            entry("premiumAdjustment",  ctx.premiumAdjustment().toPlainString()),
                            entry("currencyCode",       ctx.currencyCode()),
                            entry("description",        ctx.description() != null ? ctx.description() : ""),
                            entry("approvedBy",         ctx.approvedBy()),
                            entry("approvedDate",       fmt(ctx.approvedDate()))
                    ));
            byte[] pdf = pdfConverter.convert(html);
            String path = "documents/endorsements/" + ctx.endorsementId() + "/endorsement.pdf";
            store(path, pdf);
            return path;
        } catch (Exception ex) {
            log.error("Endorsement document generation failed for {}: {}", ctx.endorsementNumber(), ex.getMessage(), ex);
            return null;
        }
    }

    @Override
    public String generateClaimDv(ClaimDvContext ctx) {
        try {
            String html = resolveAndRender(
                    DocumentTemplateType.CLAIM_DV, ctx.productId(), ctx.classOfBusinessId(),
                    Map.of(
                            "claimNumber",    ctx.claimNumber(),
                            "policyNumber",   ctx.policyNumber(),
                            "customerName",   ctx.customerName(),
                            "incidentDate",   fmt(ctx.incidentDate()),
                            "approvedAmount", ctx.approvedAmount().toPlainString(),
                            "currencyCode",   ctx.currencyCode(),
                            "description",    ctx.description() != null ? ctx.description() : "",
                            "approvedBy",     ctx.approvedBy(),
                            "approvedDate",   fmt(ctx.approvedDate())
                    ));
            byte[] pdf = pdfConverter.convert(html);
            String path = "documents/claims/" + ctx.claimId() + "/discharge-voucher.pdf";
            store(path, pdf);
            return path;
        } catch (Exception ex) {
            log.error("Claim DV generation failed for {}: {}", ctx.claimNumber(), ex.getMessage(), ex);
            return null;
        }
    }

    // ─── Template resolution ──────────────────────────────────────────────

    private String resolveAndRender(DocumentTemplateType type, UUID productId,
                                     UUID classOfBusinessId, Map<String, String> vars) throws Exception {
        String templateHtml = loadTemplate(type, productId, classOfBusinessId);
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        return templateEngine.process(templateHtml, ctx);
    }

    private String loadTemplate(DocumentTemplateType type, UUID productId, UUID classOfBusinessId) {
        // Prefer tenant-specific template from MinIO
        List<DocumentTemplate> matches = templateRepository.findBestMatch(type, productId, classOfBusinessId);
        if (!matches.isEmpty()) {
            try {
                String tenantId = TenantContext.getTenantId();
                try (InputStream is = storageService.download(tenantId, matches.get(0).getStoragePath())) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ex) {
                log.warn("Could not load tenant template for type {}; falling back to default: {}", type, ex.getMessage());
            }
        }
        // Fall back to bundled classpath default
        return loadClasspathDefault(type);
    }

    private String loadClasspathDefault(DocumentTemplateType type) {
        String filename = switch (type) {
            case POLICY             -> "policy-default.html";
            case ENDORSEMENT        -> "endorsement-default.html";
            case CLAIM_DV           -> "claim-dv-default.html";
            case NAICOM_CERTIFICATE -> "policy-default.html"; // reuse policy template as fallback
        };
        try {
            ClassPathResource resource = new ClassPathResource("document-templates/" + filename);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            log.error("Default template not found on classpath: {}", filename);
            return "<html><body><h1>Document</h1></body></html>";
        }
    }

    // ─── Storage ──────────────────────────────────────────────────────────

    private void store(String path, byte[] pdfBytes) throws Exception {
        String tenantId = TenantContext.getTenantId();
        storageService.upload(tenantId, path, new ByteArrayInputStream(pdfBytes), "application/pdf");
    }

    private static String fmt(LocalDate date) {
        return date != null ? date.format(DATE_FMT) : "";
    }
}
