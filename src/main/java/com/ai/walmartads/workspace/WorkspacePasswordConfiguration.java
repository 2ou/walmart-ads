package com.ai.walmartads.workspace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class WorkspacePasswordConfiguration {

    @Bean
    PasswordEncoder workspacePasswordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 15, 3);
    }
}
