package com.nubeero.cia.finance.naicom;

/**
 * Rendered artifact format for a {@link NaicomSubmission}.
 *
 * <p>{@link #PDF} is the auditor-canonical, signed, immutable form.
 * {@link #CSV} is the NAICOM e-portal-friendly machine-readable form
 * (RFC 4180 streaming). {@link #JSON} / {@link #XML} are reserved for
 * future NAICOM API ingestion formats once the real API surface lands.
 */
public enum ArtifactFormat {
    PDF,
    CSV,
    JSON,
    XML
}
