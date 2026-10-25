package com.otectus.otetv.smoketest;

import net.minecraftforge.fml.common.Mod;

/**
 * Test-only companion mod used by {@code runClient -PsmokeTest}. It does nothing unless the system property
 * {@code otetv.smoketest.output} names a directory for its result file. Never shipped.
 */
@Mod(SmokeTestAgent.MOD_ID)
public class SmokeTestAgent {
    public static final String MOD_ID = "otetv_smoketest";
    public static final String OUTPUT_PROPERTY = "otetv.smoketest.output";

    public SmokeTestAgent() {
    }
}
