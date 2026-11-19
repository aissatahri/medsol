package com.medsol.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tâches planifiées (rappels de rendez-vous, rappels de fin d'abonnement). Désactivables par medsol.taches=false
 * (tests : les tâches y sont appelées directement).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "medsol.taches", havingValue = "true", matchIfMissing = true)
public class TachesConfig {
}
