package com.medsol.patient;

import java.time.LocalDate;
import java.time.Period;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record Patient(UUID id, long numero, String nom, String prenom, LocalDate dateNaissance, String sexe,
                      String telephone, String email, String identifiantNational, String adresse,
                      String situationFamiliale, String profession, String couverture, String traitementAvant) {

    public String fullName() { return prenom + " " + nom; }

    /** « NOM Prénom », comme le bureau. */
    public String nomComplet() { return (nom == null ? "" : nom.toUpperCase()) + " " + (prenom == null ? "" : prenom); }

    public String initiales() {
        return ((prenom == null || prenom.isEmpty() ? "" : prenom.substring(0, 1))
                + (nom == null || nom.isEmpty() ? "" : nom.substring(0, 1))).toUpperCase();
    }

    public Integer age() {
        return dateNaissance == null ? null : Period.between(dateNaissance, LocalDate.now()).getYears();
    }

    /** Traitement en cours, un médicament par ligne. */
    public List<String> traitementLignes() {
        return traitementAvant == null ? List.of()
                : Arrays.stream(traitementAvant.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
