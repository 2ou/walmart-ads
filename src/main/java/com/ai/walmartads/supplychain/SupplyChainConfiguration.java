package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.ai.walmartads.connector.DataConnectorSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupplyChainConfiguration {

    @Bean
    SupplyChainClient supplyChainClient(
            SupplyChainProperties properties,
            ObjectMapper objectMapper,
            DataConnectorSessionService connectorSessions
    ) {
        return new SupplyChainClient(properties, objectMapper, connectorSessions);
    }
}
