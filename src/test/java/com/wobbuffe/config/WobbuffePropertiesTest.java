package com.wobbuffe.config;

import com.wobbuffe.core.IntegrationPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WobbuffePropertiesTest {
    @Test
    void appliesDefaultsAndPerIntegrationOverrides() {
        WobbuffeProperties properties = new WobbuffeProperties();
        assertEquals(3, properties.policyFor("catalog").maxAttempts());
        WobbuffeProperties.Integration integration = new WobbuffeProperties.Integration();
        integration.setMaxAttempts(1);
        integration.setAsyncEligible(true);
        properties.getIntegrations().put("catalog", integration);
        IntegrationPolicy policy = properties.policyFor("catalog");
        assertEquals(1, policy.maxAttempts());
        assertTrue(policy.asyncEligible());
        assertFalse(properties.getRouting().isEnabled());
    }
}
