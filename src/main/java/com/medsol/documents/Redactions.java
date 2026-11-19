package com.medsol.documents;

import java.util.ArrayList;
import java.util.List;

/**
 * Courriers et comptes rendus rédigés depuis une consultation. La lettre de référence est commune ; les comptes
 * rendus dépendent de la spécialité du cabinet (l'ETT de SGCM-Cardio devient le modèle de la cardiologie).
 */
public final class Redactions {

    public record Modele(String code, String titre, String sousTitre, String trame, boolean conclusion) {}

    public static final Modele LETTRE = new Modele("LETTRE", "Lettre de référence", null, null, false);

    private static final List<Modele> CARDIO = List.of(
            new Modele("ETT", "COMPTE RENDU", "Échocardiographie Transthoracique (ETT)", """
                    Échogénicité :
                    VG : non dilaté, non hypertrophié. DTD : … mm, DTS : … mm, SIV : … mm, PP : … mm.
                    Cinétique segmentaire : …
                    FEVG (Simpson biplan) : … %
                    Fonction diastolique : E/A : …, E/e' : …
                    OG : … mm (surface … cm²). VD : non dilaté, TAPSE : … mm.
                    Valves : mitrale …, aortique …, tricuspide …, pulmonaire …
                    PAPS estimée : … mmHg. Péricarde : sec. VCI : non dilatée, compliante.""", true),
            new Modele("ECG", "COMPTE RENDU", "Électrocardiogramme", """
                    Rythme : sinusal, régulier. Fréquence : … bpm.
                    PR : … ms. QRS : … ms, axe … °. QT/QTc : … / … ms.
                    Repolarisation : …""", true),
            new Modele("HOLTER", "COMPTE RENDU", "Holter ECG des 24 heures", """
                    Durée d'enregistrement : … h. Rythme de base : …
                    Fréquence moyenne : … bpm (min … / max …).
                    Extrasystoles : … Pauses : … Troubles conductifs : …
                    Symptômes signalés : …""", true));

    private static final List<Modele> GYNECO = List.of(
            new Modele("ECHO_OBSTETRICALE", "COMPTE RENDU", "Échographie obstétricale", """
                    Terme : … SA (date des dernières règles : …).
                    Grossesse : unique / gémellaire, évolutive. Activité cardiaque : présente, … bpm.
                    Biométrie : BIP … mm, PC … mm, PA … mm, LF … mm. Poids fœtal estimé : … g.
                    Placenta : … Liquide amniotique : … Morphologie : …""", true),
            new Modele("ECHO_PELVIENNE", "COMPTE RENDU", "Échographie pelvienne", """
                    Utérus : … × … × … mm, endomètre : … mm.
                    Ovaire droit : … Ovaire gauche : …
                    Cul-de-sac de Douglas : …""", true));

    private static final List<Modele> OPHTALMO = List.of(
            new Modele("FOND_OEIL", "COMPTE RENDU", "Examen du fond d'œil", """
                    Œil droit : papille …, macula …, vaisseaux …, périphérie …
                    Œil gauche : papille …, macula …, vaisseaux …, périphérie …""", true));

    private Redactions() {}

    /** Modèles proposés au cabinet : la lettre, puis les comptes rendus de sa spécialité. */
    public static List<Modele> pour(String specialite) {
        List<Modele> l = new ArrayList<>(List.of(LETTRE));
        l.addAll(switch (specialite == null ? "" : specialite) {
            case "CARDIO" -> CARDIO;
            case "GYNECO" -> GYNECO;
            case "OPHTALMO" -> OPHTALMO;
            default -> List.of();
        });
        return l;
    }

    public static java.util.Optional<Modele> trouver(String specialite, String code) {
        return pour(specialite).stream().filter(m -> m.code().equals(code)).findFirst();
    }
}
