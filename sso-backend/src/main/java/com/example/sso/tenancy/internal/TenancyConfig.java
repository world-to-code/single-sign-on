package com.example.sso.tenancy.internal;

import com.example.sso.tenancy.OrgContext;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the auto-configured DataSource with an {@link OrgAwareDataSource} so every connection carries
 * the tenant RLS context. The real {@link HikariDataSource} stays a bean (so {@code spring.datasource.hikari.*}
 * binds to it and Boot's pool metrics still register); the {@link Primary} DataSource is the wrapper that
 * Flyway and JPA resolve.
 */
@Configuration
public class TenancyConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    DataSource dataSource(HikariDataSource hikariDataSource, OrgContext orgContext) {
        return new OrgAwareDataSource(hikariDataSource, orgContext);
    }
}
