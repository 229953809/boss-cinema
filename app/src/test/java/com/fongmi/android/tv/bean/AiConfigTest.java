package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiConfigTest {

    @Test
    public void objectFrom_usesSafeDefaultsAndRequiresExplicitEnable() {
        AiConfig config = AiConfig.objectFrom("");

        assertEquals(AiConfig.DEFAULT_ENDPOINT, config.getEndpoint());
        assertEquals(AiConfig.DEFAULT_MODEL, config.getModel());
        assertFalse(config.isReady());
    }

    @Test
    public void isReady_requiresEnabledEndpointKeyAndModel() {
        AiConfig config = AiConfig.objectFrom("{\"enabled\":true,\"endpoint\":\"https://api.openai.com/v1/responses\",\"apiKey\":\"sk-test\",\"model\":\"gpt-4.1-mini\"}");

        assertTrue(config.isReady());
    }
}
