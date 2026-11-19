package com.medsol.dossier;

import com.medsol.dossier.ReglesPrescription.Niveau;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;

/**
 * Alertes de l'ordonnance d'une consultation : allergies du patient (directes et de famille), doublons de substance
 * ou de famille, interactions majeures, précautions liées à l'âge, poids de l'enfant. Avertissements, jamais blocage :
 * le médecin décide. Règles : {@link ReglesPrescription}. Textes dans la langue de l'utilisateur (clés rx.*), le
 * texte français du code servant de secours.
 */
@Service
public class AlertesPrescription {

    public record Alerte(Niveau niveau, String titre, String detail, List<String> medicaments) {
        public String css() {
            return switch (niveau) {
                case CONTRE_INDICATION -> "urgent";
                case DECONSEILLE -> "ambre";
                case PRECAUTION -> "bleu";
                case INFO -> "gris";
            };
        }
    }

    /** Textes des alertes : clé de traduction, texte français de secours (format MessageFormat), paramètres. */
    @FunctionalInterface
    interface Textes {
        String t(String cle, String francais, Object... args);

        /** Le français du code, sans fichier de traduction (tests des règles). */
        Textes FRANCAIS = (cle, francais, args) -> formater(francais, args);
    }

    /** Sans paramètre, le texte tel quel ; sinon MessageFormat, les apostrophes droites protégées (« l'INR »). */
    static String formater(String texte, Object[] args) {
        return args == null || args.length == 0 ? texte : MessageFormat.format(texte.replace("'", "''"), args);
    }

    /** Médicament vu par les règles. */
    record Produit(String libelle, String dci, String nom, Set<String> familles, Set<String> substances) {
        static Produit de(String nom, String forme, String dci) {
            return new Produit(nom + (forme == null || forme.isBlank() ? "" : " " + forme), dci, nom,
                    ReglesPrescription.familles(dci, nom), ReglesPrescription.substances(dci, nom));
        }
    }

    record Patient(LocalDate naissance, List<AllergiesRepository.Allergie> allergies, BigDecimal poids, LocalDate datePoids) {
        Integer age(LocalDate jour) { return naissance == null ? null : Period.between(naissance, jour).getYears(); }
    }

    private final JdbcTemplate jdbc;
    private final AllergiesRepository allergies;
    private final OrdonnanceRepository ordonnances;
    private final MessageSource messages;

    public AlertesPrescription(JdbcTemplate jdbc, AllergiesRepository allergies, OrdonnanceRepository ordonnances, MessageSource messages) {
        this.jdbc = jdbc;
        this.allergies = allergies;
        this.ordonnances = ordonnances;
        this.messages = messages;
    }

    /** Toutes les alertes de l'ordonnance de la consultation. */
    public List<Alerte> ordonnance(UUID consultation) {
        Optional<Contexte> ctx = contexte(consultation);
        if (ctx.isEmpty()) return List.of();
        List<Produit> produits = ordonnances.lignes(consultation).stream()
                .map(l -> Produit.de(l.medicament(), l.forme(), l.dci())).toList();
        return analyser(ctx.get().patient(), ctx.get().jour(), produits, null, textes());
    }

    /** Alertes qu'entraînerait l'ajout de ce médicament (avant de le prescrire). */
    public List<Alerte> avantAjout(UUID consultation, String nom, String forme, String dci) {
        Optional<Contexte> ctx = contexte(consultation);
        if (ctx.isEmpty()) return List.of();
        List<Produit> produits = new ArrayList<>(ordonnances.lignes(consultation).stream()
                .map(l -> Produit.de(l.medicament(), l.forme(), l.dci())).toList());
        Produit nouveau = Produit.de(nom, forme, dci);
        produits.add(nouveau);
        return analyser(ctx.get().patient(), ctx.get().jour(), produits, nouveau, textes());
    }

    private Textes textes() {
        Locale langue = LocaleContextHolder.getLocale();
        // pas de « message par défaut » de Spring : il serait formaté une seconde fois (apostrophes du nom d'allergie perdues)
        return (cle, francais, args) -> {
            try {
                return messages.getMessage(cle, args, langue);
            } catch (org.springframework.context.NoSuchMessageException e) {
                return formater(francais, args);
            }
        };
    }

    record Contexte(Patient patient, LocalDate jour) {}

    private Optional<Contexte> contexte(UUID consultation) {
        return jdbc.query("""
                SELECT c.patient_id, c.date_consult, p.date_naissance FROM consultation c JOIN patient p ON p.id = c.patient_id
                WHERE c.id = ?""", (rs, i) -> {
            UUID patient = rs.getObject(1, UUID.class);
            LocalDate jour = rs.getObject(2, LocalDate.class);
            LocalDate naissance = rs.getObject(3, LocalDate.class);
            // dernier poids des 90 jours précédant la consultation
            List<Object[]> poids = jdbc.query("""
                    SELECT cc.valeur, c.date_consult FROM consultation_constante cc
                    JOIN consultation c ON c.id = cc.consultation_id JOIN type_constante t ON t.id = cc.type_id
                    WHERE c.patient_id = ? AND t.nom = 'POIDS' AND c.supprimee_le IS NULL AND cc.valeur <> ''
                      AND c.date_consult BETWEEN ?::date - 90 AND ?::date
                    ORDER BY c.date_consult DESC LIMIT 1""",
                    (r, k) -> new Object[]{r.getString(1), r.getObject(2, LocalDate.class)}, patient, jour, jour);
            BigDecimal kg = null;
            LocalDate date = null;
            if (!poids.isEmpty()) {
                try {
                    kg = new BigDecimal(((String) poids.get(0)[0]).strip().replace(',', '.'));
                    date = (LocalDate) poids.get(0)[1];
                } catch (NumberFormatException ignore) { /* poids illisible : traité comme absent */ }
            }
            return new Contexte(new Patient(naissance, allergies.liste(patient), kg, date), jour);
        }, consultation).stream().findFirst();
    }

    /** Nom d'une famille de médicaments dans la langue de l'utilisateur. */
    private static String famille(Textes x, String code) {
        return x.t("rx.fam." + code, ReglesPrescription.famille(code).libelle());
    }

    /** {@code nouveau} : si présent, seules les alertes qui le concernent sont gardées. */
    static List<Alerte> analyser(Patient patient, LocalDate jour, List<Produit> produits, Produit nouveau, Textes x) {
        List<Alerte> r = new ArrayList<>();
        Integer age = patient.age(jour);

        // 1. Allergies : mot de l'allergie dans le médicament, ou famille visée par l'allergie
        for (Produit p : produits) {
            for (AllergiesRepository.Allergie a : patient.allergies()) {
                Set<String> famillesAllergie = ReglesPrescription.famillesAllergie(a.nom());
                boolean direct = !AllergiesRepository.concernees(List.of(a), p.nom(), p.dci()).isEmpty();
                Optional<String> famille = p.familles().stream().filter(famillesAllergie::contains).findFirst();
                if (direct || famille.isPresent()) {
                    r.add(new Alerte(Niveau.CONTRE_INDICATION, x.t("rx.allergy.title", "Allergie connue : {0}", a.nom()),
                            famille.map(f -> x.t("rx.allergy.family", "{0} appartient aux {1}.", p.libelle(), famille(x, f)))
                                    .orElseGet(() -> x.t("rx.allergy.direct", "{0} correspond à l’allergie notée dans le dossier.", p.libelle())),
                            List.of(p.libelle())));
                    continue;
                }
                for (ReglesPrescription.Interaction c : ReglesPrescription.ALLERGIES_CROISEES) {
                    if (famillesAllergie.contains(c.a()) && p.familles().contains(c.b())) {
                        r.add(new Alerte(c.niveau(), x.t("rx.allergy.cross.title", "Allergie : {0}", a.nom()),
                                p.libelle() + " — " + x.t("rx.cross." + c.a() + "." + c.b(), c.message()), List.of(p.libelle())));
                    }
                }
            }
        }

        // 2. Doublons : même substance (surdosage), puis même famille
        for (int i = 0; i < produits.size(); i++) {
            for (int j = i + 1; j < produits.size(); j++) {
                Produit a = produits.get(i), b = produits.get(j);
                Set<String> communes = new LinkedHashSet<>(a.substances());
                communes.retainAll(b.substances());
                if (!communes.isEmpty()) {
                    r.add(new Alerte(Niveau.DECONSEILLE, x.t("rx.dup.substance.title", "Même substance prescrite deux fois"),
                            x.t("rx.dup.substance", "{0} : risque de surdosage.", String.join(", ", communes)), List.of(a.libelle(), b.libelle())));
                    continue;
                }
                for (String f : a.familles()) {
                    if (ReglesPrescription.famille(f).doublon() && b.familles().contains(f)) {
                        r.add(new Alerte(Niveau.DECONSEILLE, x.t("rx.dup.family.title", "Deux {0}", famille(x, f)),
                                x.t("rx.dup.family", "Association de deux médicaments de la même famille."), List.of(a.libelle(), b.libelle())));
                        break;
                    }
                }
            }
        }

        // 3. Interactions entre deux lignes (chaque paire une fois, dans les deux sens)
        for (int i = 0; i < produits.size(); i++) {
            for (int j = i + 1; j < produits.size(); j++) {
                Produit a = produits.get(i), b = produits.get(j);
                for (ReglesPrescription.Interaction it : ReglesPrescription.INTERACTIONS) {
                    boolean sens1 = a.familles().contains(it.a()) && b.familles().contains(it.b());
                    boolean sens2 = b.familles().contains(it.a()) && a.familles().contains(it.b());
                    if (sens1 || sens2) {
                        r.add(new Alerte(it.niveau(), x.t("rx.int.title", "Interaction : {0} + {1}", famille(x, it.a()), famille(x, it.b())),
                                x.t("rx.int." + it.a() + "." + it.b(), it.message()), List.of(a.libelle(), b.libelle())));
                    }
                }
            }
        }

        // 4. Âge
        if (age != null) {
            for (Produit p : produits) {
                for (ReglesPrescription.Age g : ReglesPrescription.AGES) {
                    boolean concerne = p.familles().contains(g.famille())
                            && (g.moinsDe() == null || age < g.moinsDe()) && (g.auMoins() == null || age >= g.auMoins());
                    if (concerne) {
                        r.add(new Alerte(g.niveau(), x.t("rx.age.title", "Patient de {0,choice,0#{0} an|1#{0} an|1<{0} ans}", age),
                                p.libelle() + " — " + x.t("rx.age." + g.famille(), g.message()), List.of(p.libelle())));
                    }
                }
            }
        }

        // 5. Enfant : la posologie se calcule au poids
        if (age != null && age < 15 && !produits.isEmpty()) {
            if (patient.poids() == null) {
                r.add(new Alerte(Niveau.INFO, x.t("rx.weight.missing.title", "Poids récent non renseigné"),
                        x.t("rx.weight.missing", "Enfant de {0,choice,0#{0} an|1#{0} an|1<{0} ans} : notez le poids dans les constantes pour adapter la posologie.", age),
                        List.of()));
            } else {
                r.add(new Alerte(Niveau.INFO, x.t("rx.weight.title", "Poids : {0} kg", patient.poids().stripTrailingZeros().toPlainString()),
                        x.t("rx.weight", "Relevé le {0} : posologie pédiatrique à adapter au poids.", patient.datePoids().toString()), List.of()));
            }
        }

        // doublons d'alerte retirés, les plus graves d'abord
        List<Alerte> uniques = new ArrayList<>(new LinkedHashSet<>(r));
        if (nouveau != null) {
            uniques.removeIf(a -> !a.medicaments().contains(nouveau.libelle()) && a.niveau() != Niveau.INFO);
            uniques.removeIf(a -> a.niveau() == Niveau.INFO);
        }
        uniques.sort(Comparator.comparing(Alerte::niveau));
        return uniques;
    }
}
