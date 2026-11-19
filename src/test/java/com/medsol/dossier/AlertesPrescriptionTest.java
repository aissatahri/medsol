package com.medsol.dossier;

import com.medsol.dossier.AlertesPrescription.Alerte;
import com.medsol.dossier.AlertesPrescription.Patient;
import com.medsol.dossier.AlertesPrescription.Produit;
import com.medsol.dossier.ReglesPrescription.Niveau;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cas cliniques types des alertes de prescription (règles pures, sans base). */
class AlertesPrescriptionTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 11, 15);

    private static Patient adulte(String... allergies) { return patient(40, null, allergies); }

    private static Patient patient(int age, BigDecimal poids, String... allergies) {
        return new Patient(JOUR.minusYears(age).minusDays(10),
                java.util.Arrays.stream(allergies).map(a -> new AllergiesRepository.Allergie(UUID.randomUUID(), a, null)).toList(),
                poids, poids == null ? null : JOUR.minusDays(5));
    }

    private static Produit p(String nom, String dci) { return Produit.de(nom, null, dci); }

    private static List<Alerte> alertes(Patient patient, Produit... produits) {
        return AlertesPrescription.analyser(patient, JOUR, List.of(produits), null, AlertesPrescription.Textes.FRANCAIS);
    }

    private static boolean contient(List<Alerte> l, Niveau n, String texte) {
        return l.stream().anyMatch(a -> a.niveau() == n && (a.titre() + " " + a.detail()).contains(texte));
    }

    @Test
    void allergyToAFamilyFlagsEveryMemberAndCrossReactions() {
        List<Alerte> l = alertes(adulte("Pénicilline"), p("AUGMENTIN", "AMOXICILLINE + ACIDE CLAVULANIQUE"));
        assertThat(contient(l, Niveau.CONTRE_INDICATION, "appartient aux pénicillines")).isTrue();
        assertThat(contient(alertes(adulte("allergie aux pénicillines"), p("OROKEN", "CEFIXIME")), Niveau.PRECAUTION, "céphalosporines")).isTrue();
        assertThat(contient(alertes(adulte("Aspirine"), p("ADVIL", "IBUPROFENE")), Niveau.DECONSEILLE, "Intolérance croisée")).isTrue();
        // « ainsi » n'est pas « AINS »
        assertThat(alertes(adulte("Poussière ainsi que pollen"), p("ADVIL", "IBUPROFENE"))).isEmpty();
    }

    @Test
    void duplicatesOfSubstanceOrFamilyAreFlagged() {
        assertThat(contient(alertes(adulte(), p("DOLIPRANE", "PARACETAMOL"), p("CODOLIPRANE", "PARACETAMOL + CODEINE")),
                Niveau.DECONSEILLE, "PARACETAMOL : risque de surdosage")).isTrue();
        assertThat(contient(alertes(adulte(), p("ADVIL", "IBUPROFENE"), p("PROFENID", "KETOPROFENE")),
                Niveau.DECONSEILLE, "Deux anti-inflammatoires")).isTrue();
    }

    @Test
    void majorInteractionsAreFlaggedOncePerPair() {
        List<Alerte> l = alertes(adulte(), p("VIAGRA", "SILDENAFIL"), p("NATISPRAY", "TRINITRINE"));
        assertThat(contient(l, Niveau.CONTRE_INDICATION, "hypotension")).isTrue();
        assertThat(l).hasSize(1);
        assertThat(contient(alertes(adulte(), p("ZECLAR", "CLARITHROMYCINE"), p("ZOCOR", "SIMVASTATINE")), Niveau.CONTRE_INDICATION, "rhabdomyolyse")).isTrue();
        assertThat(contient(alertes(adulte(), p("COUMADINE", "WARFARINE"), p("ADVIL", "IBUPROFENE")), Niveau.DECONSEILLE, "hémorragique")).isTrue();
        assertThat(contient(alertes(adulte(), p("COVERSYL", "PERINDOPRIL"), p("COZAAR", "LOSARTAN")), Niveau.DECONSEILLE, "Double blocage")).isTrue();
        // sans rapport : rien
        assertThat(alertes(adulte(), p("AMLOR", "AMLODIPINE"), p("DOLIPRANE", "PARACETAMOL"))).isEmpty();
    }

    @Test
    void ageAndWeightRulesForChildrenAndElderly() {
        assertThat(contient(alertes(patient(6, null), p("DOXY", "DOXYCYCLINE")), Niveau.CONTRE_INDICATION, "avant 8 ans")).isTrue();
        assertThat(contient(alertes(patient(10, null), p("CODOLIPRANE", "PARACETAMOL + CODEINE")), Niveau.CONTRE_INDICATION, "avant 12 ans")).isTrue();
        assertThat(contient(alertes(patient(10, null), p("ASPEGIC", "ACETYLSALICYLATE DE LYSINE")), Niveau.DECONSEILLE, "Reye")).isTrue();
        assertThat(contient(alertes(patient(4, null), p("DOLIPRANE", "PARACETAMOL")), Niveau.INFO, "Poids récent non renseigné")).isTrue();
        assertThat(contient(alertes(patient(4, new BigDecimal("16.50")), p("DOLIPRANE", "PARACETAMOL")), Niveau.INFO, "Poids : 16.5 kg")).isTrue();
        assertThat(contient(alertes(patient(80, null), p("ADVIL", "IBUPROFENE")), Niveau.PRECAUTION, "Sujet âgé")).isTrue();
        assertThat(alertes(patient(30, null), p("DOXY", "DOXYCYCLINE"))).isEmpty();
    }

    @Test
    void previewKeepsOnlyAlertsAboutTheNewMedicine() {
        Produit advil = p("ADVIL", "IBUPROFENE");
        Produit coumadine = p("COUMADINE", "WARFARINE");
        Produit amlor = p("AMLOR", "AMLODIPINE");
        assertThat(AlertesPrescription.analyser(adulte(), JOUR, List.of(advil, coumadine, amlor), amlor, AlertesPrescription.Textes.FRANCAIS)).isEmpty();
        Produit profenid = p("PROFENID", "KETOPROFENE");
        List<Alerte> l = AlertesPrescription.analyser(adulte(), JOUR, List.of(advil, coumadine, profenid), profenid, AlertesPrescription.Textes.FRANCAIS);
        assertThat(l).allMatch(a -> a.medicaments().contains("PROFENID"));
        assertThat(contient(l, Niveau.DECONSEILLE, "hémorragique")).isTrue();
        // les plus graves d'abord
        assertThat(alertes(adulte("Pénicilline"), p("ADVIL", "IBUPROFENE"), p("PROFENID", "KETOPROFENE"), p("CLAMOXYL", "AMOXICILLINE"))
                .get(0).niveau()).isEqualTo(Niveau.CONTRE_INDICATION);
    }
}
