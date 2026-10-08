package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupplyChainConfiguration {

    @Bean
    SupplyChainClient supplyChainClient(SupplyChainProperties properties, ObjectMapper objectMapper) {
        return new SupplyChainClient(properties, objectMapper);
    }
}
