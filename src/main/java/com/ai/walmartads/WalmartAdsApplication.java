package com.ai.walmartads;

import com.ai.walmartads.config.WalmartAdsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WalmartAdsProperties.class)
public class WalmartAdsApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalmartAdsApplication.class, args);
    }
}
