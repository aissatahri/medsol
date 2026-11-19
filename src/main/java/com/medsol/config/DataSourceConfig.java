package com.medsol.config;

import com.medsol.tenant.TenantDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    /** Connexions des cabinets : chacune bascule sur medsol_app (RLS) et porte le cabinet courant. */
    @Bean
    @Primary
    public DataSource dataSource(@Value("${medsol.db.url}") String url,
                                 @Value("${medsol.db.user}") String user,
                                 @Value("${medsol.db.password}") String password) {
        return new TenantDataSource(pool("medsol", url, user, password, 10));
    }

    /** JdbcTemplate de l'application (cabinets, RLS) : déclaré ici car le second ci-dessous désactive celui de Spring Boot. */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Espace administrateur de la plateforme et rappels de Stripe : connexions du propriétaire du schéma, non soumises
     * à la RLS, dans un pool séparé (jamais mélangées avec celles des cabinets). Réservé à PlateformeService.
     */
    @Bean
    @Qualifier("plateforme")
    public JdbcTemplate plateformeJdbc(@Value("${medsol.db.url}") String url,
                                       @Value("${medsol.db.user}") String user,
                                       @Value("${medsol.db.password}") String password) {
        return new JdbcTemplate(pool("medsol-plateforme", url, user, password, 2));
    }

    private static HikariDataSource pool(String nom, String url, String user, String password, int taille) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(taille);
        cfg.setPoolName(nom);
        return new HikariDataSource(cfg);
    }
}
