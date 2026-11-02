package com.vium.global;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class MonitoringEndpointExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void metricsEndpointsRemainUnexposedInProduction() throws Exception {
        for (String endpoint : new String[]{"metrics", "prometheus"}) {
            mockMvc.perform(get("/actuator/" + endpoint).with(jwt()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
