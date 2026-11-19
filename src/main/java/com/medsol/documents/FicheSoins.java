package com.medsol.documents;

import java.util.List;

/**
 * Contenu de la fiche de soins (repris de FicheSoins de SGCM-Cardio) : le patient tel qu'il est arrivé au cabinet,
 * puis un bloc par consultation (constantes en grille, champs cliniques de la spécialité, traitement, examens, RDV).
 */
public record FicheSoins(String dateEdition, String datePremiere, List<Champ> identite, String motif,
                         String antecedents, String traitementEnCours, List<Bloc> blocs) {

    public record Champ(String label, String valeur) {}

    /** Rubrique d'un bloc ; {@code grille} non vide pour les constantes. Rouge : examen ; bleu : le reste. */
    public record Section(String label, String texte, boolean rouge, List<Champ> grille) {
        public boolean estGrille() { return grille != null && !grille.isEmpty(); }
    }

    public record Bloc(String titre, List<Section> sections) {}
}
