package com.medsol.dossier;

import java.text.Normalizer;
import java.util.*;

/**
 * Référentiel des alertes de prescription : familles de substances (repérées par la DCI, ou le nom à défaut),
 * interactions majeures bien établies, précautions liées à l'âge, allergies de famille.
 * <p><strong>Aide à la décision, liste volontairement courte et non exhaustive</strong> : elle ne remplace ni le jugement
 * du médecin ni une base de référence (Thériaque, Vidal, BNF, ANSM...). Les règles sont versionnées avec le code et
 * couvertes par des tests ; toute modification doit être relue par un médecin.
 */
public final class ReglesPrescription {

    private ReglesPrescription() {}

    public enum Niveau {
        CONTRE_INDICATION("Contre-indication"), DECONSEILLE("Association déconseillée"), PRECAUTION("Précaution"), INFO("Information");
        public final String libelle;
        Niveau(String libelle) { this.libelle = libelle; }
    }

    /** Famille : motifs cherchés dans la DCI (début de mot), alias pour les allergies, signaler deux produits de la famille. */
    public record Famille(String code, String libelle, List<String> motifs, List<String> aliasAllergie, boolean doublon) {}

    public record Interaction(String a, String b, Niveau niveau, String message) {}

    public record Age(String famille, Integer moinsDe, Integer auMoins, Niveau niveau, String message) {}

    private static Famille f(String code, String libelle, boolean doublon, List<String> alias, String... motifs) {
        return new Famille(code, libelle, List.of(motifs), alias, doublon);
    }

    public static final List<Famille> FAMILLES = List.of(
            f("PENICILLINES", "pénicillines", true, List.of("PENICILLINE", "AMOXICILLINE", "AMPICILLINE", "AUGMENTIN", "CLAMOXYL", "BETALACTAMINE", "BETA LACTAMINE"),
                    "AMOXICILLINE", "AMPICILLINE", "BACAMPICILLINE", "PENICILLINE", "BENZYLPENICILLINE", "PHENOXYMETHYLPENICILLINE",
                    "OXACILLINE", "CLOXACILLINE", "PIPERACILLINE", "TICARCILLINE", "PIVMECILLINAM", "BENZATHINE"),
            f("CEPHALOSPORINES", "céphalosporines", true, List.of("CEPHALOSPORINE"), "CEF", "CEPH"),
            f("MACROLIDES", "macrolides", true, List.of("MACROLIDE", "AZITHROMYCINE", "CLARITHROMYCINE", "ERYTHROMYCINE"),
                    "AZITHROMYCINE", "CLARITHROMYCINE", "ERYTHROMYCINE", "SPIRAMYCINE", "ROXITHROMYCINE", "JOSAMYCINE", "TELITHROMYCINE"),
            f("MACROLIDES_INHIBITEURS", "macrolides inhibiteurs enzymatiques", false, List.of(), "CLARITHROMYCINE", "ERYTHROMYCINE", "TELITHROMYCINE"),
            f("FLUOROQUINOLONES", "fluoroquinolones", true, List.of("QUINOLONE", "FLUOROQUINOLONE", "CIPROFLOXACINE"),
                    "CIPROFLOXACINE", "OFLOXACINE", "LEVOFLOXACINE", "NORFLOXACINE", "MOXIFLOXACINE", "PEFLOXACINE", "LOMEFLOXACINE"),
            f("TETRACYCLINES", "tétracyclines", true, List.of("TETRACYCLINE", "DOXYCYCLINE"), "DOXYCYCLINE", "MINOCYCLINE", "TETRACYCLINE", "LYMECYCLINE"),
            f("SULFAMIDES", "sulfamides antibactériens", true, List.of("SULFAMIDE", "BACTRIM", "COTRIMOXAZOLE"),
                    "SULFAMETHOXAZOLE", "COTRIMOXAZOLE", "SULFADIAZINE", "SULFASALAZINE"),
            f("AINS", "anti-inflammatoires non stéroïdiens (AINS)", true, List.of("AINS", "ANTI INFLAMMATOIRE", "IBUPROFENE", "DICLOFENAC", "KETOPROFENE"),
                    "IBUPROFENE", "KETOPROFENE", "DEXKETOPROFENE", "DICLOFENAC", "ACECLOFENAC", "NAPROXENE", "PIROXICAM", "MELOXICAM",
                    "TENOXICAM", "LORNOXICAM", "INDOMETACINE", "CELECOXIB", "ETORICOXIB", "NIMESULIDE", "FLURBIPROFENE", "MEFENAMIQUE",
                    "NIFLUMIQUE", "ETODOLAC", "KETOROLAC"),
            f("ASPIRINE", "aspirine", false, List.of("ASPIRINE", "ACETYLSALICYLIQUE", "SALICYLE"), "ACETYLSALICYLIQUE", "ASPIRINE", "ACETYLSALICYLATE"),
            f("ANTICOAGULANTS", "anticoagulants", true, List.of("ANTICOAGULANT"),
                    "WARFARINE", "ACENOCOUMAROL", "FLUINDIONE", "RIVAROXABAN", "APIXABAN", "DABIGATRAN", "EDOXABAN", "HEPARINE",
                    "ENOXAPARINE", "TINZAPARINE", "NADROPARINE", "DALTEPARINE", "FONDAPARINUX"),
            f("AVK", "antivitamines K", false, List.of(), "WARFARINE", "ACENOCOUMAROL", "FLUINDIONE"),
            f("STATINES_3A4", "simvastatine / lovastatine", false, List.of(), "SIMVASTATINE", "LOVASTATINE"),
            f("STATINES", "statines", true, List.of("STATINE"), "SIMVASTATINE", "LOVASTATINE", "ATORVASTATINE", "ROSUVASTATINE", "PRAVASTATINE", "FLUVASTATINE", "PITAVASTATINE"),
            f("COLCHICINE", "colchicine", false, List.of("COLCHICINE"), "COLCHICINE"),
            f("IEC", "inhibiteurs de l'enzyme de conversion (IEC)", true, List.of("IEC"),
                    "CAPTOPRIL", "ENALAPRIL", "LISINOPRIL", "PERINDOPRIL", "RAMIPRIL", "QUINAPRIL", "FOSINOPRIL", "TRANDOLAPRIL",
                    "BENAZEPRIL", "ZOFENOPRIL", "IMIDAPRIL", "CILAZAPRIL"),
            f("ARA2", "antagonistes de l'angiotensine II (sartans)", true, List.of("SARTAN", "ARA2"),
                    "LOSARTAN", "VALSARTAN", "IRBESARTAN", "CANDESARTAN", "TELMISARTAN", "OLMESARTAN", "EPROSARTAN", "AZILSARTAN"),
            f("HYPERKALIEMIANTS", "épargneurs de potassium / potassium", false, List.of(),
                    "SPIRONOLACTONE", "EPLERENONE", "AMILORIDE", "TRIAMTERENE", "POTASSIUM"),
            f("DIURETIQUES", "diurétiques", false, List.of(),
                    "FUROSEMIDE", "BUMETANIDE", "TORASEMIDE", "HYDROCHLOROTHIAZIDE", "INDAPAMIDE", "CHLORTALIDONE"),
            f("NITRES", "dérivés nitrés et apparentés", false, List.of(), "TRINITRINE", "NITROGLYCERINE", "ISOSORBIDE", "MOLSIDOMINE", "NICORANDIL"),
            f("IPDE5", "inhibiteurs de la phosphodiestérase 5", true, List.of(), "SILDENAFIL", "TADALAFIL", "VARDENAFIL", "AVANAFIL"),
            f("CORTICOIDES", "corticoïdes", false, List.of("CORTICOIDE", "CORTISONE"),
                    "PREDNISONE", "PREDNISOLONE", "METHYLPREDNISOLONE", "DEXAMETHASONE", "BETAMETHASONE", "HYDROCORTISONE", "CORTIVAZOL"),
            f("SEROTONINERGIQUES", "antidépresseurs sérotoninergiques (ISRS, IRSN)", true, List.of(),
                    "FLUOXETINE", "PAROXETINE", "SERTRALINE", "CITALOPRAM", "ESCITALOPRAM", "FLUVOXAMINE", "VENLAFAXINE", "DULOXETINE", "MILNACIPRAN"),
            f("TRAMADOL", "tramadol", false, List.of("TRAMADOL"), "TRAMADOL"),
            f("CODEINE", "codéine", false, List.of("CODEINE"), "CODEINE"),
            f("METHOTREXATE", "méthotrexate", false, List.of(), "METHOTREXATE"),
            f("LITHIUM", "lithium", false, List.of(), "LITHIUM"),
            f("BETABLOQUANTS", "bêtabloquants", true, List.of("BETABLOQUANT"),
                    "BISOPROLOL", "ATENOLOL", "METOPROLOL", "PROPRANOLOL", "NEBIVOLOL", "CARVEDILOL", "ACEBUTOLOL", "CELIPROLOL",
                    "SOTALOL", "NADOLOL", "LABETALOL"),
            f("BRADYCARDISANTS", "vérapamil / diltiazem", false, List.of(), "VERAPAMIL", "DILTIAZEM"),
            f("AMIODARONE", "amiodarone", false, List.of(), "AMIODARONE"),
            f("DIGOXINE", "digoxine", false, List.of(), "DIGOXINE"),
            f("ALLOPURINOL", "allopurinol", false, List.of(), "ALLOPURINOL"),
            f("THIOPURINES", "azathioprine / mercaptopurine", false, List.of(), "AZATHIOPRINE", "MERCAPTOPURINE"),
            f("LOPERAMIDE", "lopéramide", false, List.of(), "LOPERAMIDE"));

    public static final List<Interaction> INTERACTIONS = List.of(
            new Interaction("IPDE5", "NITRES", Niveau.CONTRE_INDICATION, "Risque d'hypotension sévère, parfois mortelle."),
            new Interaction("MACROLIDES_INHIBITEURS", "STATINES_3A4", Niveau.CONTRE_INDICATION, "Risque de rhabdomyolyse (élévation importante de la statine)."),
            new Interaction("MACROLIDES_INHIBITEURS", "COLCHICINE", Niveau.CONTRE_INDICATION, "Toxicité de la colchicine majorée, potentiellement fatale."),
            new Interaction("AINS", "ANTICOAGULANTS", Niveau.DECONSEILLE, "Risque hémorragique majoré."),
            new Interaction("ASPIRINE", "ANTICOAGULANTS", Niveau.DECONSEILLE, "Risque hémorragique majoré (sauf indication validée)."),
            new Interaction("AINS", "METHOTREXATE", Niveau.DECONSEILLE, "Toxicité hématologique du méthotrexate majorée."),
            new Interaction("SULFAMIDES", "METHOTREXATE", Niveau.DECONSEILLE, "Toxicité hématologique du méthotrexate majorée."),
            new Interaction("IEC", "ARA2", Niveau.DECONSEILLE, "Double blocage du système rénine-angiotensine : insuffisance rénale, hyperkaliémie."),
            new Interaction("LITHIUM", "AINS", Niveau.DECONSEILLE, "Augmentation de la lithémie : risque de surdosage."),
            new Interaction("LITHIUM", "IEC", Niveau.DECONSEILLE, "Augmentation de la lithémie : risque de surdosage."),
            new Interaction("LITHIUM", "ARA2", Niveau.DECONSEILLE, "Augmentation de la lithémie : risque de surdosage."),
            new Interaction("LITHIUM", "DIURETIQUES", Niveau.DECONSEILLE, "Augmentation de la lithémie : risque de surdosage."),
            new Interaction("ALLOPURINOL", "THIOPURINES", Niveau.DECONSEILLE, "Toxicité médullaire de l'azathioprine / mercaptopurine majorée."),
            new Interaction("AINS", "ASPIRINE", Niveau.PRECAUTION, "Risque hémorragique et ulcérogène majoré."),
            new Interaction("IEC", "HYPERKALIEMIANTS", Niveau.PRECAUTION, "Risque d'hyperkaliémie : surveiller la kaliémie."),
            new Interaction("ARA2", "HYPERKALIEMIANTS", Niveau.PRECAUTION, "Risque d'hyperkaliémie : surveiller la kaliémie."),
            new Interaction("AINS", "IEC", Niveau.PRECAUTION, "Risque d'insuffisance rénale aiguë (sujet âgé, déshydraté)."),
            new Interaction("AINS", "ARA2", Niveau.PRECAUTION, "Risque d'insuffisance rénale aiguë (sujet âgé, déshydraté)."),
            new Interaction("FLUOROQUINOLONES", "CORTICOIDES", Niveau.PRECAUTION, "Risque de tendinopathie, voire de rupture tendineuse."),
            new Interaction("TRAMADOL", "SEROTONINERGIQUES", Niveau.PRECAUTION, "Risque de syndrome sérotoninergique et de convulsions."),
            new Interaction("BETABLOQUANTS", "BRADYCARDISANTS", Niveau.PRECAUTION, "Bradycardie, troubles de la conduction : surveillance clinique et ECG."),
            new Interaction("AMIODARONE", "STATINES_3A4", Niveau.PRECAUTION, "Risque de rhabdomyolyse : ne pas dépasser 20 mg/j de simvastatine."),
            new Interaction("AMIODARONE", "AVK", Niveau.PRECAUTION, "Effet de l'anticoagulant augmenté : contrôler l'INR."),
            new Interaction("AMIODARONE", "DIGOXINE", Niveau.PRECAUTION, "Digoxinémie augmentée, bradycardie : surveiller."),
            new Interaction("BRADYCARDISANTS", "DIGOXINE", Niveau.PRECAUTION, "Digoxinémie augmentée, bradycardie : surveiller."),
            new Interaction("FLUOROQUINOLONES", "AVK", Niveau.PRECAUTION, "Effet de l'anticoagulant augmenté : contrôler l'INR."),
            new Interaction("MACROLIDES_INHIBITEURS", "AVK", Niveau.PRECAUTION, "Effet de l'anticoagulant augmenté : contrôler l'INR."));

    public static final List<Age> AGES = List.of(
            new Age("CODEINE", 12, null, Niveau.CONTRE_INDICATION, "Codéine contre-indiquée avant 12 ans (dépression respiratoire)."),
            new Age("TETRACYCLINES", 8, null, Niveau.CONTRE_INDICATION, "Tétracyclines contre-indiquées avant 8 ans (coloration des dents)."),
            new Age("LOPERAMIDE", 2, null, Niveau.CONTRE_INDICATION, "Lopéramide contre-indiqué avant 2 ans."),
            new Age("ASPIRINE", 16, null, Niveau.DECONSEILLE, "Aspirine déconseillée avant 16 ans (syndrome de Reye), sauf indication spécifique."),
            new Age("TRAMADOL", 12, null, Niveau.DECONSEILLE, "Tramadol déconseillé avant 12 ans."),
            new Age("FLUOROQUINOLONES", 15, null, Niveau.DECONSEILLE, "Fluoroquinolones déconseillées chez l'enfant (atteinte du cartilage), sauf situation particulière."),
            new Age("AINS", null, 75, Niveau.PRECAUTION, "Sujet âgé : risque rénal, digestif et cardiovasculaire des AINS ; dose minimale, durée courte."));

    /** Allergie à une famille qui expose à une réaction croisée avec une autre (précaution, pas contre-indication). */
    public static final List<Interaction> ALLERGIES_CROISEES = List.of(
            new Interaction("PENICILLINES", "CEPHALOSPORINES", Niveau.PRECAUTION, "Allergie croisée possible avec les céphalosporines."),
            new Interaction("ASPIRINE", "AINS", Niveau.DECONSEILLE, "Intolérance croisée fréquente entre aspirine et AINS."),
            new Interaction("AINS", "ASPIRINE", Niveau.DECONSEILLE, "Intolérance croisée fréquente entre AINS et aspirine."));

    private static final Map<String, Famille> PAR_CODE = new HashMap<>();
    static { FAMILLES.forEach(x -> PAR_CODE.put(x.code(), x)); }

    public static Famille famille(String code) { return PAR_CODE.get(code); }

    /** Majuscules sans accents, séparateurs réduits à des espaces. */
    public static String normaliser(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        return t.replaceAll("[^A-Z0-9]+", " ").strip();
    }

    /** Familles d'un médicament : un mot de sa DCI (ou de son nom) commence par l'un des motifs. */
    public static Set<String> familles(String dci, String nom) {
        String texte = normaliser((dci == null ? "" : dci) + " " + (nom == null ? "" : nom));
        Set<String> r = new LinkedHashSet<>();
        for (Famille fa : FAMILLES) {
            for (String mot : texte.split(" ")) {
                if (fa.motifs().stream().anyMatch(mot::startsWith)) { r.add(fa.code()); break; }
            }
        }
        return r;
    }

    /** Substances d'une DCI (« PARACETAMOL + CODEINE » → deux) ; le nom à défaut de DCI. */
    public static Set<String> substances(String dci, String nom) {
        String source = dci == null || dci.isBlank() ? (nom == null ? "" : nom.split("\\s+")[0]) : dci;
        Set<String> r = new LinkedHashSet<>();
        for (String s : Normalizer.normalize(source, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .split("\\s*(\\+|/|,|;|\\bET\\b)\\s*")) {
            String n = normaliser(s);
            if (n.length() >= 4) r.add(n);
        }
        return r;
    }

    /** Familles visées par le texte d'une allergie (« allergie à la pénicilline » → PENICILLINES). */
    public static Set<String> famillesAllergie(String allergie) {
        String texte = " " + normaliser(allergie) + " ";
        Set<String> r = new LinkedHashSet<>();
        for (Famille fa : FAMILLES) {
            // mot entier, pluriel accepté (« AINS » ne doit pas reconnaître « AINSI »)
            boolean touche = fa.aliasAllergie().stream().anyMatch(a -> texte.contains(" " + a + " ") || texte.contains(" " + a + "S "));
            if (!touche) {
                for (String mot : texte.strip().split(" ")) {
                    if (mot.length() >= 6 && fa.motifs().stream().anyMatch(m -> m.length() >= 6 && mot.startsWith(m))) touche = true;
                }
            }
            if (touche) r.add(fa.code());
        }
        return r;
    }
}
