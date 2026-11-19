package com.medsol.config;

import com.medsol.cabinet.RegistrationForm;
import com.medsol.cabinet.RegistrationService;
import com.medsol.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Données de démonstration (MEDSOL_DEMO=true) : trois cabinets dans trois pays, chacun avec un médecin, une
 * assistante, des patients, des consultations passées (constantes, champs cliniques, ordonnances, analyses),
 * des rendez-vous et une salle d'attente du jour. Ne fait rien si les comptes existent déjà.
 * Jamais en production : noms et numéros inventés, mots de passe connus.
 */
@Component
@ConditionalOnProperty(name = "medsol.demo", havingValue = "true")
@org.springframework.core.annotation.Order(100)   // avant ComptesPlateforme, qui marque le cabinet de l'administrateur
public class DemoData implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoData.class);

    record Compte(String role, String nom, String email, String motDePasse) {}

    record Cabinet(String nom, String pays, String fuseau, String devise, String specialite, String adresse, String tel,
                   Compte medecin, Compte assistante, String[] prenomsH, String[] prenomsF, String[] noms,
                   String prefixeTel, String[] medicamentsLocaux) {}

    private static final List<Cabinet> CABINETS = List.of(
            new Cabinet("Cabinet de cardiologie Dr Benali", "MA", "Africa/Casablanca", "MAD", "CARDIO",
                    "12 boulevard Mohammed V, Oujda", "0536 00 00 01",
                    new Compte("MEDECIN", "Karim Benali", "medecin.cardio@demo.medsol", "Cardio-Demo-2026"),
                    new Compte("SECRETAIRE", "Salma Idrissi", "assistante.cardio@demo.medsol", "Assist-Cardio-2026"),
                    new String[]{"Mohamed", "Youssef", "Ahmed", "Omar", "Hamza", "Rachid", "Said", "Mustapha", "Abdellah", "Karim"},
                    new String[]{"Fatima", "Khadija", "Aicha", "Meryem", "Salma", "Nadia", "Laila", "Imane", "Zineb", "Hanane"},
                    new String[]{"El Amrani", "Benjelloun", "Tazi", "Alaoui", "Bennani", "Chraibi", "Lahlou", "Berrada", "Idrissi", "Ouazzani", "Fassi", "Belkadi"},
                    "06", new String[0]),
            new Cabinet("Cabinet de médecine générale Dr Martin", "FR", "Europe/Paris", "EUR", "GENERAL",
                    "8 rue de la République, Lyon", "04 72 00 00 01",
                    new Compte("MEDECIN", "Claire Martin", "medecin.general@demo.medsol", "General-Demo-2026"),
                    new Compte("SECRETAIRE", "Julie Moreau", "assistante.general@demo.medsol", "Assist-General-2026"),
                    new String[]{"Thomas", "Nicolas", "Julien", "Pierre", "Antoine", "Lucas", "Hugo", "Louis", "Paul", "Arthur"},
                    new String[]{"Marie", "Camille", "Léa", "Manon", "Chloé", "Emma", "Sarah", "Julie", "Laura", "Inès"},
                    new String[]{"Dubois", "Durand", "Lefebvre", "Leroy", "Moreau", "Simon", "Laurent", "Michel", "Garcia", "Roux", "Fournier", "Girard"},
                    "06", new String[]{"DOLIPRANE|COMPRIME à 1000 MG|PARACETAMOL", "AMOXICILLINE BIOGARAN|GELULE à 500 MG|AMOXICILLINE",
                    "SPASFON|COMPRIME à 80 MG|PHLOROGLUCINOL", "VENTOLINE|SUSPENSION POUR INHALATION 100 µG|SALBUTAMOL"}),
            new Cabinet("Cabinet de pédiatrie Dr Diop", "SN", "Africa/Dakar", "XOF", "PEDIATRIE",
                    "Avenue Cheikh Anta Diop, Dakar", "33 800 00 01",
                    new Compte("MEDECIN", "Awa Diop", "medecin.pediatrie@demo.medsol", "Pediatrie-Demo-2026"),
                    new Compte("SECRETAIRE", "Fatou Ndiaye", "assistante.pediatrie@demo.medsol", "Assist-Pediatrie-2026"),
                    new String[]{"Moussa", "Ibrahima", "Mamadou", "Cheikh", "Ousmane", "Abdoulaye", "Modou", "Babacar", "Alioune", "Pape"},
                    new String[]{"Aminata", "Fatou", "Mariama", "Khady", "Astou", "Ndeye", "Coumba", "Awa", "Bineta", "Sokhna"},
                    new String[]{"Diop", "Ndiaye", "Fall", "Sow", "Ba", "Diallo", "Gueye", "Sarr", "Faye", "Mbaye", "Cisse", "Seck"},
                    "77", new String[]{"EFFERALGAN PEDIATRIQUE|SOLUTION BUVABLE 3 %|PARACETAMOL", "CLAMOXYL|POUDRE POUR SUSPENSION 250 MG/5 ML|AMOXICILLINE",
                    "SRO|SACHET|SELS DE REHYDRATATION ORALE", "ZINC|COMPRIME DISPERSIBLE à 20 MG|ZINC"}));

    private static final String[] MOTIFS = {"Contrôle", "Douleur thoracique", "Fièvre", "Toux", "Céphalées", "Fatigue",
            "Suivi tension", "Bilan annuel", "Essoufflement", "Palpitations", "Douleurs abdominales", "Vaccination"};
    private static final String[] DIAGNOSTICS = {"HTA équilibrée", "Rhinopharyngite", "Angine", "Gastro-entérite",
            "Asthme intermittent", "Diabète de type 2", "Bronchite aiguë", "Examen normal"};
    private static final String[] POSOLOGIES = {"1 cp le matin", "1 cp matin et soir", "1 cp 3 fois par jour", "2 cp le soir"};
    private static final String[] DUREES = {"7 jours", "10 jours", "1 mois", "3 mois"};
    private static final String[] CATALOGUE_MA = {"AMLOR", "GLUCOPHAGE", "ASPEGIC", "DOLIPRANE", "AUGMENTIN", "LASILIX", "CARDENSIEL", "TAHOR"};

    private final RegistrationService registration;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final boolean aleatoires;

    public DemoData(RegistrationService registration, JdbcTemplate jdbc, PasswordEncoder encoder,
                    @org.springframework.beans.factory.annotation.Value("${medsol.demo.random-passwords:false}") boolean aleatoires) {
        this.registration = registration;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.aleatoires = aleatoires;
    }

    /** Serveur accessible sur Internet (medsol.demo.random-passwords=true) : mots de passe tirés au hasard. */
    private String mdp(String connu) {
        if (!aleatoires) return connu;
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom h = new java.security.SecureRandom();
        StringBuilder s = new StringBuilder("Demo-");
        for (int i = 0; i < 14; i++) s.append(a.charAt(h.nextInt(a.length())));
        return s.toString();
    }

    private Compte compte(Compte k) { return new Compte(k.role(), k.nom(), k.email(), mdp(k.motDePasse())); }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (registration.emailTaken(CABINETS.get(0).medecin().email())) {
            LOG.info("Données de démonstration déjà présentes : comptes dans {}",
                    Path.of(System.getProperty("user.home"), ".medsol", "comptes-demo.txt"));
            return;
        }
        StringBuilder fiche = new StringBuilder("Comptes de démonstration MedSol (données fictives)\n\n");
        Random r = new Random(2026);
        // Administrateur de la plateforme (medsol.platform-admins) : son propre cabinet interne, sans patient
        RegistrationForm admin = new RegistrationForm();
        admin.setCabinetName("MedSol — administration"); admin.setDoctorName("Administrateur MedSol");
        String mdpAdmin = mdp("Admin-Plateforme-2026");
        admin.setEmail("admin@demo.medsol"); admin.setPassword(mdpAdmin); admin.setCountry("MA");
        admin.setSpecialty("AUTRE"); admin.setTimezone("Africa/Casablanca"); admin.setCurrency("MAD");
        admin.setCgu(true);
        registration.register(admin, "fr");
        fiche.append("Plateforme MedSol (espace administrateur : abonnements, tarifs)\n")
                .append(String.format("  %-11s %-34s %s%n%n", "Admin", "admin@demo.medsol", mdpAdmin));
        for (Cabinet modele : CABINETS) {
            Cabinet c = new Cabinet(modele.nom(), modele.pays(), modele.fuseau(), modele.devise(), modele.specialite(), modele.adresse(),
                    modele.tel(), compte(modele.medecin()), compte(modele.assistante()), modele.prenomsH(), modele.prenomsF(), modele.noms(),
                    modele.prefixeTel(), modele.medicamentsLocaux());
            creer(c, r);
            fiche.append(c.nom()).append(" (").append(c.pays()).append(")\n");
            for (Compte k : List.of(c.medecin(), c.assistante())) {
                fiche.append(String.format("  %-11s %-34s %s%n", k.role().equals("MEDECIN") ? "Médecin" : "Assistante", k.email(), k.motDePasse()));
            }
            fiche.append('\n');
        }
        Path fichier = Path.of(System.getProperty("user.home"), ".medsol", "comptes-demo.txt");
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, fiche.toString(), StandardCharsets.UTF_8);
        LOG.info("Données de démonstration créées. Comptes : {}\n{}", fichier, fiche);
    }

    private void creer(Cabinet c, Random r) {
        RegistrationForm f = new RegistrationForm();
        f.setCabinetName(c.nom()); f.setDoctorName(c.medecin().nom()); f.setEmail(c.medecin().email());
        f.setPassword(c.medecin().motDePasse()); f.setCountry(c.pays()); f.setSpecialty(c.specialite());
        f.setTimezone(c.fuseau()); f.setCurrency(c.devise());
        f.setCgu(true);   // cabinets de démonstration : conditions réputées acceptées
        UUID cabinetId = registration.register(f, "fr");

        TenantContext.set(cabinetId);
        try {
            jdbc.update("UPDATE cabinet SET adresse = ?, telephone = ?, numero_ordre = ?, essai_fin = current_date + 365",
                    c.adresse(), c.tel(), "ORD-" + (10000 + r.nextInt(89999)));
            // ville des documents ; au Maroc, identifiants fictifs pour montrer l'en-tête et les factures conformes
            jdbc.update("UPDATE cabinet SET ville = ?", c.adresse().substring(c.adresse().lastIndexOf(',') + 1).strip());
            if (c.pays().equals("MA")) {
                jdbc.update("UPDATE cabinet SET identifiant_professionnel = ?, identifiant_fiscal = ?, ice = ?, taxe_professionnelle = ?",
                        chiffres(r, 9), chiffres(r, 8), chiffres(r, 15), chiffres(r, 8));
            }
            // page publique de prise de rendez-vous : /r/demo-cardio, /r/demo-general, /r/demo-pediatrie
            jdbc.update("UPDATE cabinet SET reservation_active = true, reservation_lien = ?", "demo-" + c.specialite().toLowerCase());
            jdbc.update("INSERT INTO utilisateur (cabinet_id, email, mot_de_passe, nom_complet, role) VALUES (current_cabinet(), ?, ?, ?, 'SECRETAIRE')",
                    c.assistante().email(), encoder.encode(c.assistante().motDePasse()), c.assistante().nom());
            UUID medecinId = jdbc.queryForObject("SELECT id FROM utilisateur WHERE role = 'MEDECIN'", UUID.class);
            ZoneId zone = ZoneId.of(c.fuseau());
            LocalDate aujourdhui = LocalDate.now(zone);

            List<Long> medicaments = medicaments(c);
            Map<String, Long> constantes = types("type_constante");
            Map<String, Long> champs = types("type_champ_clinique");
            List<Long> analyses = jdbc.queryForList("SELECT id FROM type_examen WHERE genre = 'ANALYSE' AND cabinet_id IS NULL ORDER BY id LIMIT 12", Long.class);

            List<UUID> patients = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                boolean femme = r.nextBoolean();
                String prenom = (femme ? c.prenomsF() : c.prenomsH())[r.nextInt(10)];
                String nom = c.noms()[r.nextInt(c.noms().length)];
                LocalDate naissance = c.specialite().equals("PEDIATRIE")
                        ? aujourdhui.minusDays(200 + r.nextInt(14 * 365))
                        : aujourdhui.minusDays(18 * 365 + r.nextInt(62 * 365));
                String tel = c.prefixeTel() + String.format(" %02d %02d %02d %02d", r.nextInt(100), r.nextInt(100), r.nextInt(100), r.nextInt(100));
                patients.add(jdbc.queryForObject("""
                        INSERT INTO patient (cabinet_id, numero, nom, prenom, date_naissance, sexe, telephone, adresse, consentement_le, cree_par)
                        VALUES (current_cabinet(), ?, ?, ?, ?, ?, ?, ?, now(), ?) RETURNING id""", UUID.class,
                        i, nom, prenom, Date.valueOf(naissance), femme ? "F" : "M", tel, c.adresse().split(",")[1].strip(), medecinId));
            }
            jdbc.update("INSERT INTO cabinet_compteur (cabinet_id, nom, valeur) VALUES (current_cabinet(), 'patient', 40)");

            // Antécédents et allergies de quelques patients
            for (int i = 0; i < 8; i++) {
                jdbc.update("INSERT INTO antecedent (cabinet_id, patient_id, type, description) VALUES (current_cabinet(), ?, ?, ?)",
                        patients.get(i), i % 3 == 0 ? "FAMILIAL" : "MEDICAL", new String[]{"HTA", "Diabète", "Asthme", "Appendicectomie"}[i % 4]);
            }
            jdbc.update("INSERT INTO allergie (cabinet_id, patient_id, nom) VALUES (current_cabinet(), ?, 'Pénicilline')", patients.get(1));

            // Consultations passées (6 derniers mois)
            for (int i = 0; i < 25; i++) {
                UUID p = patients.get(r.nextInt(patients.size()));
                LocalDate jour = aujourdhui.minusDays(1 + r.nextInt(180));
                List<UUID> ids = jdbc.queryForList("""
                        INSERT INTO consultation (cabinet_id, patient_id, medecin_id, date_consult, motif)
                        VALUES (current_cabinet(), ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id""",
                        UUID.class, p, medecinId, Date.valueOf(jour), MOTIFS[r.nextInt(MOTIFS.length)]);
                if (ids.isEmpty()) continue;
                UUID cid = ids.get(0);
                String motif = jdbc.queryForObject("SELECT motif FROM consultation WHERE id = ?", String.class, cid);
                champ(cid, champs.get("SYMPTOMES"), motif);
                champ(cid, champs.get("DIAGNOSTIC_MEDICAL"), DIAGNOSTICS[r.nextInt(DIAGNOSTICS.length)]);
                boolean enfant = c.specialite().equals("PEDIATRIE");
                double poids = enfant ? 4 + r.nextInt(40) : 50 + r.nextInt(45);
                double taille = enfant ? 0.55 + r.nextInt(100) / 100.0 : 1.55 + r.nextInt(35) / 100.0;
                constante(cid, constantes.get("POIDS"), "", String.valueOf((int) poids));
                constante(cid, constantes.get("TAILLE"), "", String.valueOf(taille));
                constante(cid, constantes.get("IMC"), "", String.valueOf(Math.round(poids / (taille * taille) * 100) / 100.0));
                constante(cid, constantes.get("PRESSION_ARTERIELLE"), "BRAS_GAUCHE", (110 + r.nextInt(50)) + "/" + (65 + r.nextInt(30)));
                if (!medicaments.isEmpty()) {
                    int n = 1 + r.nextInt(3);
                    for (int k = 0; k < n; k++) {
                        jdbc.update("""
                                INSERT INTO prescription (cabinet_id, consultation_id, medicament_id, dose, duree, remarque)
                                VALUES (current_cabinet(), ?, ?, ?, ?, '')""",
                                cid, medicaments.get(r.nextInt(medicaments.size())), POSOLOGIES[r.nextInt(4)], DUREES[r.nextInt(4)]);
                    }
                }
                if (r.nextInt(3) == 0 && !analyses.isEmpty()) {
                    jdbc.update("""
                            INSERT INTO examen (cabinet_id, consultation_id, genre, type_id, description, date_examen)
                            VALUES (current_cabinet(), ?, 'ANALYSE', ?, 'à jeun', ?)""", cid, analyses.get(r.nextInt(analyses.size())), Date.valueOf(jour));
                }
            }

            // Catalogue des actes, actes de chaque consultation passée et encaissements (une partie impayée)
            Map<String, BigDecimal> tarifs = switch (c.specialite()) {
                case "CARDIO" -> Map.of("Consultation", new BigDecimal("300"), "ECG", new BigDecimal("200"), "Échocardiographie", new BigDecimal("600"));
                case "PEDIATRIE" -> Map.of("Consultation", new BigDecimal("10000"), "Vaccination", new BigDecimal("5000"));
                default -> Map.of("Consultation", new BigDecimal("26.50"), "Visite à domicile", new BigDecimal("36.50"));
            };
            tarifs.forEach((nom, prix) -> jdbc.update("INSERT INTO acte (cabinet_id, nom, prix) VALUES (current_cabinet(), ?, ?)", nom, prix));
            UUID consultationActe = jdbc.queryForObject("SELECT id FROM acte WHERE nom = 'Consultation'", UUID.class);
            List<UUID> autres = jdbc.queryForList("SELECT id FROM acte WHERE nom <> 'Consultation' ORDER BY nom", UUID.class);
            long recu = 0;
            for (UUID cid : jdbc.queryForList("SELECT id FROM consultation ORDER BY date_consult", UUID.class)) {
                jdbc.update("INSERT INTO consultation_acte (cabinet_id, consultation_id, acte_id, prix, paye) SELECT current_cabinet(), ?, id, prix, true FROM acte WHERE id = ?",
                        cid, consultationActe);
                if (r.nextInt(3) == 0) {
                    jdbc.update("INSERT INTO consultation_acte (cabinet_id, consultation_id, acte_id, prix, paye) SELECT current_cabinet(), ?, id, prix, ? FROM acte WHERE id = ?",
                            cid, r.nextInt(5) > 0, autres.get(r.nextInt(autres.size())));
                }
                if (r.nextInt(5) > 0) {   // 4 sur 5 réglées
                    jdbc.update("""
                            INSERT INTO versement (cabinet_id, consultation_id, numero, montant, mode, jour)
                            SELECT current_cabinet(), c.id, ?, (SELECT sum(prix) FROM consultation_acte WHERE consultation_id = c.id AND paye), ?, c.date_consult
                            FROM consultation c WHERE c.id = ?""", ++recu, r.nextBoolean() ? "ESPECES" : "CARTE", cid);
                }
            }
            jdbc.update("INSERT INTO cabinet_compteur (cabinet_id, nom, valeur) VALUES (current_cabinet(), 'recu', ?)", recu);

            // Horaires : lundi–vendredi 09:00–13:00 et 15:00–19:00, samedi 09:00–12:00
            jdbc.update("""
                    INSERT INTO disponibilite (cabinet_id, jour, debut, fin)
                    SELECT current_cabinet(), j, h.debut, h.fin FROM generate_series(1, 5) j
                    CROSS JOIN (VALUES (time '09:00', time '13:00'), (time '15:00', time '19:00')) AS h(debut, fin)
                    UNION ALL SELECT current_cabinet(), 6, time '09:00', time '12:00'""");

            // Rendez-vous : cette semaine et la suivante, jours ouvrés, de 9 h à 12 h
            LocalDate lundi = aujourdhui.with(DayOfWeek.MONDAY);
            for (int d = 0; d < 12; d++) {
                LocalDate jour = lundi.plusDays(d);
                if (jour.getDayOfWeek().getValue() > 5) continue;
                for (int s = 0; s < 6; s++) {
                    if (r.nextInt(3) == 0) continue;
                    ZonedDateTime debut = jour.atTime(9, 0).plusMinutes(30L * s).atZone(zone);
                    String statut = jour.isBefore(aujourdhui) ? (r.nextInt(6) == 0 ? "ABSENT" : "TERMINE") : (r.nextBoolean() ? "CONFIRME" : "PLANIFIE");
                    jdbc.update("""
                            INSERT INTO rendez_vous (cabinet_id, patient_id, debut, fin, statut, motif, cree_par)
                            VALUES (current_cabinet(), ?, ?, ?, ?, ?, ?)""",
                            patients.get(r.nextInt(patients.size())), Timestamp.from(debut.toInstant()),
                            Timestamp.from(debut.plusMinutes(30).toInstant()), statut, MOTIFS[r.nextInt(MOTIFS.length)], medecinId);
                }
            }

            // Salle d'attente du jour : trois patients en attente
            for (int n = 1; n <= 3; n++) {
                jdbc.update("""
                        INSERT INTO file_attente (cabinet_id, patient_id, jour, numero, motif, arrive_le)
                        VALUES (current_cabinet(), ?, ?, ?, ?, now() - make_interval(mins => ?))""",
                        patients.get(30 + n), Date.valueOf(aujourdhui), n, MOTIFS[n], 40 - n * 10);
            }
            jdbc.update("INSERT INTO cabinet_compteur (cabinet_id, nom, valeur) VALUES (current_cabinet(), ?, 3)", "ticket:" + aujourdhui);
        } finally {
            TenantContext.clear();
        }
    }

    /** Médicaments du catalogue national (Maroc) ou, ailleurs, quelques médicaments ajoutés par le cabinet. */
    private List<Long> medicaments(Cabinet c) {
        if (c.pays().equals("MA")) {
            List<Long> ids = new ArrayList<>();
            for (String nom : CATALOGUE_MA) {
                ids.addAll(jdbc.queryForList("SELECT id FROM medicament WHERE pays = 'MA' AND nom = ? ORDER BY id LIMIT 1", Long.class, nom));
            }
            return ids;
        }
        List<Long> ids = new ArrayList<>();
        for (String m : c.medicamentsLocaux()) {
            String[] p = m.split("\\|");
            ids.add(jdbc.queryForObject("INSERT INTO medicament (cabinet_id, nom, forme, dci) VALUES (current_cabinet(), ?, ?, ?) RETURNING id",
                    Long.class, p[0], p[1], p[2]));
        }
        return ids;
    }

    private Map<String, Long> types(String table) {
        Map<String, Long> m = new HashMap<>();
        jdbc.query("SELECT nom, id FROM " + table + " WHERE cabinet_id IS NULL", rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    private void champ(UUID cid, Long type, String valeur) {
        if (type != null) jdbc.update("INSERT INTO consultation_champ (consultation_id, cabinet_id, type_id, valeur) VALUES (?, current_cabinet(), ?, ?)", cid, type, valeur);
    }

    private void constante(UUID cid, Long type, String cle, String valeur) {
        if (type != null) jdbc.update("INSERT INTO consultation_constante (consultation_id, cabinet_id, type_id, cle, valeur) VALUES (?, current_cabinet(), ?, ?, ?)", cid, type, cle, valeur);
    }

    /** Numéro fictif de {@code n} chiffres, sans zéro en tête. */
    private static String chiffres(java.util.Random r, int n) {
        StringBuilder b = new StringBuilder().append(1 + r.nextInt(9));
        while (b.length() < n) b.append(r.nextInt(10));
        return b.toString();
    }
}
