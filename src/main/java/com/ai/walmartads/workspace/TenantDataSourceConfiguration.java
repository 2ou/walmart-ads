package com.ai.walmartads.workspace;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

import javax.sql.DataSource;

@Configuration
public class TenantDataSourceConfiguration {

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new TenantAwareDataSourceBeanPostProcessor(new WorkspaceSqlGuard());
    }

    private static final class TenantAwareDataSourceBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {
        private final WorkspaceSqlGuard guard;

        private TenantAwareDataSourceBeanPostProcessor(WorkspaceSqlGuard guard) {
            this.guard = guard;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                return new TenantAwareDataSource(dataSource, guard);
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 50;
        }
    }
}
