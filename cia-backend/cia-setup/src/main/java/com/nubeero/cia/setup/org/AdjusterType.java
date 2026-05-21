package com.nubeero.cia.setup.org;

/**
 * Loss-adjuster engagement model. Mirrors {@link SurveyorType} since adjusters
 * follow the same internal-vs-external distinction as surveyors — INTERNAL
 * adjusters are claims-team staff, EXTERNAL are NAICOM-licensed firms.
 */
public enum AdjusterType {
    INTERNAL, EXTERNAL
}
