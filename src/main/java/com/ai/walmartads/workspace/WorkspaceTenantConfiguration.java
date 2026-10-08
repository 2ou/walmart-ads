package com.ai.walmartads.workspace;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkspaceTenantConfiguration {

    @Bean
    CurrentTenantIdentifierResolver<Long> workspaceTenantIdentifierResolver() {
        return new CurrentTenantIdentifierResolver<>() {
            @Override
            public Long resolveCurrentTenantIdentifier() {
                return WorkspaceContext.current().map(WorkspacePrincipal::workspaceId).orElse(1L);
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return true;
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer workspaceTenantHibernateCustomizer(
            CurrentTenantIdentifierResolver<Long> resolver
    ) {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
