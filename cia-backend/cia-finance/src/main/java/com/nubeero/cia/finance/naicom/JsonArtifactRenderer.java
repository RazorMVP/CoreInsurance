package com.nubeero.cia.finance.naicom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Renders a submission's payload as pretty-printed UTF-8 JSON.
 *
 * <p>Module 12 Phase 4 Slice 4.10. The canonical machine-readable
 * artifact. Auditors and NAICOM e-portal both consume this format
 * directly; the PDF / CSV variants are derivations.
 *
 * <h2>Determinism</h2>
 * <p>The renderer uses a Jackson {@link ObjectMapper} configured with
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} disabled —
 * {@link java.util.LinkedHashMap} (which every engine emits) preserves
 * insertion order, and we rely on that order for byte-stable output.
 * {@link com.fasterxml.jackson.databind.SerializationFeature#WRITE_DATES_AS_TIMESTAMPS}
 * is also disabled so {@code Instant} / {@code LocalDate} values render
 * as ISO-8601 strings — same shape as the engine output, no double
 * encoding.
 */
@Component
public class JsonArtifactRenderer implements NaicomArtifactRenderer {

    private final ObjectMapper objectMapper;

    public JsonArtifactRenderer() {
        this.objectMapper = new ObjectMapper()
            .findAndRegisterModules()  // picks up jsr310 (Instant / LocalDate)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public ArtifactFormat format() {
        return ArtifactFormat.JSON;
    }

    @Override
    public String mimeType() {
        return "application/json";
    }

    @Override
    public String fileExtension() {
        return "json";
    }

    @Override
    public byte[] render(NaicomSubmission submission) {
        try {
            return objectMapper.writeValueAsBytes(submission.getPayload());
        } catch (JsonProcessingException e) {
            // Engine payloads are LinkedHashMap<String,Object> trees of
            // BigDecimal / String / List / Map — Jackson never fails on
            // these. If it does, it's a programmer error worth surfacing.
            throw new IllegalStateException(
                "JSON serialization of payload for submission "
                + submission.getId() + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Charset used by the renderer for documentation / tests. UTF-8 is
     * Jackson's default and is hard-coded.
     */
    public static java.nio.charset.Charset charset() {
        return StandardCharsets.UTF_8;
    }
}
