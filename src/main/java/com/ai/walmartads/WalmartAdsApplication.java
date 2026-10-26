package com.ai.walmartads;

import com.ai.walmartads.config.KieAiProperties;
import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.config.SupplyChainProperties;
import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.config.WalmartAdsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        WalmartAdsProperties.class,
        KieAiProperties.class,
        LingXingProperties.class,
        SupplyChainProperties.class,
        DataConnectorProperties.class
})
public class WalmartAdsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalmartAdsApplication.class, args);
    }
}
