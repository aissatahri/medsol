package com.medsol.documents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mise en page d'un document imprimé, réglée par le cabinet (Paramètres › Impression). Toute valeur nulle garde la mise
 * en page par défaut de MedSol (celle du bureau) : police, marges en mm, et pour chaque élément l'affichage, la taille
 * (pt), le gras et une position fixe (x, y en mm depuis le coin haut-gauche de la feuille) — utile pour placer la date
 * ou le nom du patient dans les cases d'une feuille préimprimée.
 */
public record MiseEnPage(String document, String police, Float haut, Float bas, Float gauche, Float droite,
                         Map<String, Element> elements, boolean grille) {

    public record Element(Boolean visible, Float x, Float y, Float taille, Boolean gras) {
        public static final Element AUTO = new Element(null, null, null, null, null);
    }

    /**
     * Un élément réglable : clé, libellé, taille et gras par défaut, plaçable ou non (sinon il suit le texte), masquable,
     * et affiché ou non par défaut (ex. la DCI : masquée sauf si le médecin l'active).
     */
    public record Modele(String cle, String libelle, float taille, boolean gras, boolean placable, boolean masquable, boolean visible) {
        public Modele(String cle, String libelle, float taille, boolean gras, boolean placable, boolean masquable) {
            this(cle, libelle, taille, gras, placable, masquable, true);
        }
    }

    /** Un type de document : clé, libellé, format, marges par défaut (mm) et éléments réglables. */
    public record Type(String cle, String libelle, String format, float haut, float bas, float cote, List<Modele> elements) {}

    public static final List<String> POLICES = List.of("HELVETICA", "TIMES", "COURIER");

    private static final float PT = 25.4f / 72f;   // mm par point

    public static final List<Type> TYPES = List.of(
            new Type("ORDONNANCE", "Ordonnance", "A5", 140 * PT, 50 * PT, 30 * PT, List.of(
                    new Modele("DATE", "Date (« Oujda, le … »)", 16, true, true, true),
                    new Modele("PATIENT", "Nom du patient (cadre)", 16, true, true, true),
                    new Modele("PRECISIONS", "Âge et poids du patient", 10, false, false, true),
                    new Modele("RDV", "Prochain rendez-vous", 9, false, false, true),
                    new Modele("MEDICAMENT", "Nom du médicament", 16, true, false, false),
                    new Modele("DCI", "DCI (composant) sous le médicament", 9, false, false, true, false),
                    new Modele("POSOLOGIE", "Posologie et remarque", 12, false, false, false),
                    new Modele("SIGNATURE", "« Signature et cachet » (si en-tête MedSol)", 10, false, true, true))),
            new Type("ANALYSE", "Demande d'analyses", "A5", 140 * PT, 50 * PT, 30 * PT, demande()),
            new Type("IMAGERIE", "Demande d'imagerie", "A5", 140 * PT, 50 * PT, 30 * PT, demande()),
            new Type("CERTIFICAT", "Certificat", "A5", 90 * PT, 30 * PT, 24 * PT, List.of(
                    new Modele("TITRE", "Titre", 16, true, true, true),
                    new Modele("DATE", "Date", 12, false, true, true),
                    new Modele("TEXTE", "Texte du certificat", 12, false, false, false),
                    new Modele("SIGNATURE", "Signature et nom du médecin", 12, false, true, true))),
            new Type("LETTRE", "Lettre de référence", "A4", 50 * PT, 40 * PT, 40 * PT, List.of(
                    new Modele("TITRE", "Titre", 18, true, true, true),
                    new Modele("DATE", "Lieu et date", 11, false, true, true),
                    new Modele("DESTINATAIRE", "Destinataire", 11, false, true, true),
                    new Modele("PATIENT", "Patient concerné", 11, false, true, true),
                    new Modele("TEXTE", "Texte de la lettre", 11, false, false, false),
                    new Modele("SIGNATURE", "Signature et cachet", 11, false, true, true))),
            new Type("COMPTE_RENDU", "Compte rendu d'examen", "A4", 50 * PT, 50 * PT, 36 * PT, List.of(
                    new Modele("TITRE", "Titre", 18, true, true, true),
                    new Modele("SOUS_TITRE", "Sous-titre", 14, true, true, true),
                    new Modele("DATE", "Date de l'examen", 12, false, true, true),
                    new Modele("PATIENT", "Informations du patient", 12, false, true, true),
                    new Modele("TEXTE", "Compte rendu et conclusion", 12, false, false, false),
                    new Modele("SIGNATURE", "Signature et cachet", 12, false, true, true))));

    private static List<Modele> demande() {
        return List.of(
                new Modele("TITRE", "Titre (« Demande d'… »)", 16, true, true, true),
                new Modele("DATE", "Date", 16, false, true, true),
                new Modele("PATIENT", "Nom du patient", 16, true, true, true),
                new Modele("CONSIGNE", "« Faire s'il vous plaît : »", 16, false, true, true),
                new Modele("EXAMEN", "Examens demandés", 16, false, false, false),
                new Modele("SIGNATURE", "Signature et cachet", 8, false, true, true));
    }

    public static Type type(String cle) {
        return TYPES.stream().filter(t -> t.cle().equals(cle)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Document inconnu : " + cle));
    }

    /** Mise en page par défaut de MedSol (aucun réglage). */
    public static MiseEnPage defaut(String document) {
        return new MiseEnPage(document, null, null, null, null, null, Map.of(), false);
    }

    public MiseEnPage avecGrille(boolean g) { return new MiseEnPage(document, police, haut, bas, gauche, droite, elements, g); }

    public Element element(String cle) { return elements == null ? Element.AUTO : elements.getOrDefault(cle, Element.AUTO); }

    public Modele modele(String cle) {
        return type(document).elements().stream().filter(m -> m.cle().equals(cle)).findFirst().orElseThrow();
    }

    public boolean visible(String cle) {
        Modele mo = modele(cle);
        if (!mo.masquable()) return true;
        Boolean v = element(cle).visible();
        return v != null ? v : mo.visible();
    }

    public float taille(String cle) { Float t = element(cle).taille(); return t != null ? t : modele(cle).taille(); }

    /** Taille réglée, ou celle donnée par le code (qui varie selon le contexte, ex. date avec ou sans ville). */
    public float taille(String cle, float parDefaut) { Float t = element(cle).taille(); return t != null ? t : parDefaut; }

    public boolean gras(String cle) { Boolean g = element(cle).gras(); return g != null ? g : modele(cle).gras(); }

    /** Position fixe réglée (x et y renseignés, élément plaçable). */
    public boolean place(String cle) {
        Element e = element(cle);
        return e.x() != null && e.y() != null && modele(cle).placable();
    }

    /** Marges en points, avec les valeurs du code quand le cabinet n'a rien réglé. */
    public float hautPt(float parDefaut) { return haut != null ? haut / PT : parDefaut; }
    public float basPt(float parDefaut) { return bas != null ? bas / PT : parDefaut; }
    public float gauchePt(float parDefaut) { return gauche != null ? gauche / PT : parDefaut; }
    public float droitePt(float parDefaut) { return droite != null ? droite / PT : parDefaut; }

    public static float pt(float mm) { return mm / PT; }

    /** Copie modifiable (formulaire) : tous les éléments du type, réglés ou non. */
    public Map<String, Element> tousLesElements() {
        Map<String, Element> m = new LinkedHashMap<>();
        type(document).elements().forEach(e -> m.put(e.cle(), element(e.cle())));
        return m;
    }
}
