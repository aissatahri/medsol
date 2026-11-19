package com.medsol.queue;

import java.time.Instant;
import java.util.UUID;

/** Passage en salle d'attente (« Passage » de SGCM-Cardio). */
public record QueueEntry(UUID id, UUID patientId, String patientName, int numero, String statut, String motif,
                         Instant arriveLe, Instant debutLe, String etat, String sexe) {

    public boolean prioritaire() { return etat != null && !"NORMAL".equals(etat); }

    public boolean ouvert() { return "EN_ATTENTE".equals(statut) || "EN_CONSULTATION".equals(statut); }

    public String etatLibelle() {
        return switch (etat == null ? "" : etat) {
            case "URGENT" -> "Urgent";
            case "AGE" -> "Âgé";
            case "GRAVE" -> "Grave";
            case "AUTRE" -> "Prioritaire";
            default -> "Normal";
        };
    }

    public String statutLibelle() {
        return switch (statut) {
            case "EN_CONSULTATION" -> "En consultation";
            case "TERMINE" -> "Terminé";
            case "ABSENT" -> "Retiré";
            default -> "En attente";
        };
    }
}
