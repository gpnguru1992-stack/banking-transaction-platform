package com.bank.analytics;

import com.bank.common.BankingCommonAutoConfiguration;
import com.bank.common.security.ResourceServerSecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Lakehouse sink: no database and no public API, so the shared JPA/outbox/security auto-configuration is off.
 * Only event types and the Kafka error handling are reused from banking-common.
 */
@SpringBootApplication(exclude = {
        BankingCommonAutoConfiguration.class,
        ResourceServerSecurityConfig.class,
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
