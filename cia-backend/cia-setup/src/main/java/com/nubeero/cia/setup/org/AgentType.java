package com.nubeero.cia.setup.org;

/**
 * Insurance agent legal form. NAICOM-licensed agents are either
 * INDIVIDUAL (a single licensed person) or CORPORATE (a licensed agency
 * firm). This is the agent-side analogue of {@link AdjusterType}, which
 * carries the INTERNAL / EXTERNAL distinction relevant to adjusters.
 */
public enum AgentType {
    INDIVIDUAL, CORPORATE
}
