package com.medsol.documents;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfViewerPreferences;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.medsol.dossier.ExamenRepository;
import com.medsol.dossier.OrdonnanceRepository;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Documents imprimés, repris de DocumentsPdf de SGCM-Cardio Web (lui-même fidèle au PdfGenerator du bureau) :
 * A5, feuilles préimprimées du cabinet (marge haute de 140 pt pour l'en-tête imprimé), mêmes textes et couleurs.
 */
@Component
public class DocumentsPdf {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DeviceRgb VERT = new DeviceRgb(22, 160, 133);
    private static final DeviceRgb BLEU = new DeviceRgb(52, 152, 219);
    private static final DeviceRgb JAUNE = new DeviceRgb(241, 196, 15);
    private static final DeviceRgb ARDOISE = new DeviceRgb(52, 73, 94);
    private static final DeviceRgb FOND_PATIENT = new DeviceRgb(240, 248, 255);

    /** Tailles par défaut du bureau (titre 16, texte 12, demande 8). */
    private static final float TITRE = 16f;
    private static final float TEXTE = 12f;
    private static final float DEMANDE = 8f;

    public record ProchainRdv(LocalDate date, LocalTime heure) {}

    /**
     * En-tête imprimé par MedSol dans la marge haute (Maroc : nom du médecin, spécialité, n° d'inscription à l'Ordre,
     * INPE, adresse et téléphone du cabinet). Absent quand le cabinet imprime sur du papier à en-tête.
     */
    public record EnTete(String medecin, String specialite, String numeroOrdre, String identifiant,
                         String cabinet, String adresse, String telephone) {}

    /**
     * Mise en page commune : en-tête éventuel, ville du « Fait à …, le … » et réglages d'impression du cabinet
     * (Paramètres › Impression ; null : mise en page par défaut).
     */
    public record Cadre(EnTete entete, String ville, MiseEnPage mise) {
        public static final Cadre VIDE = new Cadre(null, null, null);

        public Cadre(EnTete entete, String ville) { this(entete, ville, null); }

        /** Réglages pour ce document (ceux du cabinet s'ils le concernent, sinon la mise en page par défaut). */
        MiseEnPage mise(String document) {
            return mise != null && document.equals(mise.document()) ? mise : MiseEnPage.defaut(document);
        }

        /** A5 : 140 pt laissés libres pour la feuille préimprimée, 112 pt quand MedSol imprime l'en-tête. */
        float margeHaut() { return entete == null ? 140f : 112f; }

        /** « Oujda, le 16/11/2026 », ou la date seule si la ville n'est pas renseignée. */
        String faitLe(LocalDate date) {
            return ville == null || ville.isBlank() ? date.format(JOUR) : ville.strip() + ", le " + date.format(JOUR);
        }
    }

    /** Ordonnance (ordonnance_*.pdf du bureau) ; {@code precisions} : âge, et poids de l'enfant (« 4 ans · 16 kg »). */
    public byte[] ordonnance(Cadre cadreDoc, String nom, String prenom, String precisions, LocalDate date,
                             List<OrdonnanceRepository.Ligne> lignes, ProchainRdv rdv) {
        MiseEnPage m = cadreDoc.mise("ORDONNANCE");
        return pdf(cadreDoc.entete(), m, m.hautPt(cadreDoc.margeHaut()), m.basPt(50f), m.gauchePt(30f), m.droitePt(30f), doc -> {
            Rendu r = new Rendu(m, doc);
            r.ajouter("DATE", r.style(new Paragraph(cadreDoc.faitLe(date)).setTextAlignment(TextAlignment.RIGHT).setMarginBottom(4),
                    "DATE", cadreDoc.ville() == null ? TITRE : TEXTE));

            boolean avecRdv = rdv != null && m.visible("RDV");
            Table cadre = new Table(avecRdv ? UnitValue.createPercentArray(new float[]{2f, 1f}) : UnitValue.createPercentArray(new float[]{1f}))
                    .setWidth(UnitValue.createPercentValue(100)).setBackgroundColor(FOND_PATIENT).setBorder(new SolidBorder(VERT, 1));
            Text nomPatient = new Text(upper(nom) + " " + prenom).setFontSize(m.taille("PATIENT"));
            if (m.gras("PATIENT")) nomPatient.setBold();
            Paragraph qui = new Paragraph().add(nomPatient);
            if (precisions != null && !precisions.isBlank() && m.visible("PRECISIONS")) {
                qui.add(new Text("\n" + precisions).setFontSize(m.taille("PRECISIONS")).setFontColor(ARDOISE));
            }
            cadre.addCell(new Cell().add(qui)
                    .setBackgroundColor(FOND_PATIENT).setPadding(10).setBorder(Border.NO_BORDER));
            if (avecRdv) {
                String libelle = "RDV : " + rdv.date().format(JOUR) + (rdv.heure() != null ? " " + rdv.heure().format(DateTimeFormatter.ofPattern("HH:mm")) : "");
                float taille = m.taille("RDV");
                cadre.addCell(new Cell().add(new Paragraph(libelle).setFontSize(taille).setTextAlignment(TextAlignment.RIGHT).setFixedLeading(taille + 2))
                        .setBackgroundColor(FOND_PATIENT).setPadding(10).setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE));
            }
            r.ajouter("PATIENT", cadre);
            if (!m.place("PATIENT")) doc.add(new Paragraph("").setMarginBottom(3));

            float tMed = m.taille("MEDICAMENT");
            float tPos = m.taille("POSOLOGIE");
            int n = 1;
            for (OrdonnanceRepository.Ligne l : lignes) {
                Text nomMed = new Text(l.medicament() == null ? "" : l.medicament()).setFontSize(tMed);
                Text numero = new Text(n + ". ").setFontSize(tMed).setFontColor(VERT);
                if (m.gras("MEDICAMENT")) { nomMed.setBold(); numero.setBold(); }
                Paragraph med = new Paragraph().add(numero).add(nomMed).setMarginBottom(0);
                if (l.forme() != null && !l.forme().isBlank()) med.add(new Text(" " + l.forme()).setFontSize(tPos).setItalic());
                doc.add(med);
                // DCI sous le nom commercial : le pharmacien peut proposer un générique
                if (m.visible("DCI") && !texte(l.dci()).isEmpty() && !texte(l.dci()).equalsIgnoreCase(texte(l.medicament()))) {
                    doc.add(new Paragraph("DCI : " + texte(l.dci())).setFontSize(m.taille("DCI")).setFontColor(ARDOISE).setMarginLeft(16).setMarginBottom(0));
                }
                // Posologie « dose pendant durée », puis la remarque, comme le bureau en mode structuré
                StringBuilder posologie = new StringBuilder(texte(l.dose()));
                if (!texte(l.duree()).isEmpty()) posologie.append(posologie.isEmpty() ? "" : " pendant ").append(texte(l.duree()));
                Text pos = new Text(posologie.toString()).setFontSize(tPos);
                if (m.gras("POSOLOGIE")) pos.setBold();
                Paragraph p = new Paragraph().add(new Text("   • ").setFontColor(BLEU)).add(pos);
                if (!texte(l.remarque()).isEmpty()) {
                    p.add(new Text("  •  ").setFontColor(JAUNE)).add(new Text(texte(l.remarque())).setFontSize(tPos).setItalic().setFontColor(ARDOISE));
                }
                doc.add(p.setMarginLeft(10).setMarginBottom(1));
                if (n < lignes.size()) {
                    doc.add(new Paragraph("").setMarginTop(1).setMarginBottom(2).setBorderBottom(new SolidBorder(new DeviceRgb(236, 240, 241), 1)));
                }
                n++;
            }
            // Feuille préimprimée : rien en dehors des médicaments (la signature et le cachet vont dans la zone prévue)
            if (cadreDoc.entete() != null) {
                r.ajouter("SIGNATURE", new Div().setKeepTogether(true).setMarginTop(14)
                        .add(r.style(new Paragraph("Signature et cachet du médecin").setTextAlignment(TextAlignment.RIGHT).setFontColor(ARDOISE),
                                "SIGNATURE", TEXTE - 2)));
            }
            r.terminer();
        });
    }

    /** Demande d'analyses ou d'imagerie (demande_analyse_*.pdf / demande_imagerie_*.pdf du bureau). */
    public byte[] demande(Cadre cadreDoc, ExamenRepository.Genre genre, String nom, String prenom, LocalDate date, List<ExamenRepository.Examen> examens) {
        String titre = genre == ExamenRepository.Genre.ANALYSE ? "Demande d'Analyse" : "Demande d'Imagerie";
        MiseEnPage m = cadreDoc.mise(genre == ExamenRepository.Genre.ANALYSE ? "ANALYSE" : "IMAGERIE");
        return pdf(cadreDoc.entete(), m, m.hautPt(cadreDoc.margeHaut()), m.basPt(50f), m.gauchePt(30f), m.droitePt(30f), doc -> {
            Rendu r = new Rendu(m, doc);
            Paragraph pTitre = r.style(new Paragraph(titre).setTextAlignment(TextAlignment.CENTER), "TITRE", TITRE);
            Paragraph pDate = r.style(new Paragraph(cadreDoc.faitLe(date)).setTextAlignment(TextAlignment.RIGHT), "DATE",
                    cadreDoc.ville() == null ? TITRE : TEXTE);
            if (!m.place("TITRE") && !m.place("DATE") && m.visible("TITRE") && m.visible("DATE")) {
                // disposition du bureau : titre et date sur une même ligne
                Table tete = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
                tete.addCell(new Cell().add(pTitre).setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE));
                tete.addCell(new Cell().add(pDate).setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE));
                doc.add(tete);
            } else {
                r.ajouter("TITRE", pTitre);
                r.ajouter("DATE", pDate);
            }
            doc.add(new Paragraph("\n").setFontSize(12f));
            r.ajouter("PATIENT", r.style(new Paragraph(nom + " " + prenom).setMarginLeft(85.04f), "PATIENT", TITRE));
            doc.add(new Paragraph("\n").setFontSize(DEMANDE));
            r.ajouter("CONSIGNE", r.style(new Paragraph("Faire s'il vous plaît :").setMarginLeft(10).setMarginBottom(6), "CONSIGNE", TITRE));
            float tExa = m.taille("EXAMEN");
            int n = 1;
            for (ExamenRepository.Examen e : examens) {
                // Code s'il existe, sinon le nom : comme formatAnalyseDisplay du bureau
                String intitule = e.code() != null && !e.code().isBlank() ? e.code() : e.intitule();
                Text libelle = new Text(intitule).setFontSize(tExa);
                if (m.gras("EXAMEN")) libelle.setBold();
                Paragraph ligne = new Paragraph()
                        .add(new Text(n++ + ". ").setFontSize(tExa).setBold().setFontColor(VERT))
                        .add(libelle);
                if (e.description() != null && !e.description().isBlank()) {
                    ligne.add(new Text("  (" + e.description().strip() + ")").setFontSize(Math.max(6f, tExa - 4)).setItalic().setFontColor(ARDOISE));
                }
                doc.add(ligne.setMarginLeft(10).setMarginBottom(5));
            }
            doc.add(new Paragraph("\n").setFontSize(TITRE));
            float tSig = m.taille("SIGNATURE");
            Div bloc = new Div().setKeepTogether(true);
            bloc.add(new Paragraph("Signature du médecin : _____________________\n\n").setTextAlignment(TextAlignment.RIGHT).setFontSize(tSig));
            bloc.add(new Paragraph("[Cachet du médecin]").setTextAlignment(TextAlignment.RIGHT).setFontSize(tSig));
            r.ajouter("SIGNATURE", bloc);
            r.terminer();
        });
    }

    /** Certificat (generateGenericCertificat du bureau). */
    public byte[] certificat(Cadre cadreDoc, String titre, String contenu, String signatureMedecin, LocalDate date) {
        MiseEnPage m = cadreDoc.mise("CERTIFICAT");
        float haut = m.hautPt(cadreDoc.entete() != null ? 125f : 90f);
        return pdf(cadreDoc.entete(), m, haut, m.basPt(30f), m.gauchePt(24f), m.droitePt(24f), doc -> {
            Rendu r = new Rendu(m, doc);
            doc.add(new Paragraph("").setMarginTop(6));
            r.ajouter("TITRE", r.style(new Paragraph(titre).setTextAlignment(TextAlignment.CENTER).setUnderline(), "TITRE", TITRE));
            r.ajouter("DATE", r.style(new Paragraph(cadreDoc.ville() == null ? "Date : " + date.format(JOUR) : "Fait à " + cadreDoc.faitLe(date))
                    .setTextAlignment(TextAlignment.RIGHT), "DATE", TEXTE));
            doc.add(new Paragraph("").setBorderBottom(new SolidBorder(new DeviceRgb(0, 0, 0), 1)).setMarginBottom(6));
            doc.add(r.style(new Paragraph(contenu).setTextAlignment(TextAlignment.LEFT).setMarginTop(6).setMarginBottom(6), "TEXTE", TEXTE));
            doc.add(new Paragraph("").setMarginTop(8));
            float tSig = m.taille("SIGNATURE");
            r.ajouter("SIGNATURE", new Div().setKeepTogether(true)
                    .add(new Paragraph("Signature et cachet du médecin").setTextAlignment(TextAlignment.RIGHT).setFontSize(tSig))
                    .add(new Paragraph(signatureMedecin).setTextAlignment(TextAlignment.RIGHT).setFontSize(tSig).setBold()));
            r.terminer();
        });
    }

    /** Fiche de soins (generateFicheSoinsPdf du bureau) : A4, bandeau de titre, un bloc par consultation. */
    public byte[] ficheSoins(FicheSoins f) {
        DeviceRgb bleu = new DeviceRgb(0, 0, 255);
        DeviceRgb rouge = new DeviceRgb(200, 0, 0);
        DeviceRgb bleuFonce = new DeviceRgb(21, 67, 136);
        float t = 11f;
        return a4(null, null, 24, 36, 40, 36, doc -> {
            Table bandeau = new Table(UnitValue.createPercentArray(new float[]{1, 2, 1})).useAllAvailableWidth()
                    .setBackgroundColor(new DeviceRgb(232, 240, 252)).setBorder(Border.NO_BORDER)
                    .setBorderBottom(new SolidBorder(bleuFonce, 2f)).setMarginBottom(10);
            bandeau.addCell(new Cell().setBorder(Border.NO_BORDER));
            bandeau.addCell(new Cell().add(new Paragraph("FICHE DE SOINS").setBold().setFontSize(TITRE + 4).setFontColor(bleuFonce)
                            .setCharacterSpacing(2f).setTextAlignment(TextAlignment.CENTER).setMargin(0))
                    .setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE).setPaddingTop(8).setPaddingBottom(8));
            bandeau.addCell(new Cell().add(new Paragraph("Le " + f.dateEdition()).setFontSize(t - 1).setFontColor(bleuFonce)
                            .setTextAlignment(TextAlignment.RIGHT).setMargin(0))
                    .setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE).setPaddingRight(8));
            doc.add(bandeau);
            if (!f.datePremiere().isEmpty()) {
                doc.add(new Paragraph().add(new Text("Date de première consultation : ").setBold().setFontColor(bleu))
                        .add(new Text(f.datePremiere())).setFontSize(t).setMarginBottom(6));
            }
            Table info = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
            for (FicheSoins.Champ c : f.identite()) info.addCell(etiquette(c.label(), c.valeur(), bleu, t));
            if (f.identite().size() % 2 == 1) info.addCell(new Cell().setBorder(Border.NO_BORDER));
            doc.add(info.setMarginBottom(8));
            doc.add(rubrique("Motif de consultation :", f.motif(), bleu, t));
            doc.add(rubrique("ATCD :", f.antecedents(), bleu, t));
            doc.add(rubrique("Traitement en cours :", f.traitementEnCours(), bleu, t));
            for (FicheSoins.Bloc b : f.blocs()) {
                Div bloc = new Div().setMarginTop(10).setBorderTop(new SolidBorder(new DeviceRgb(150, 150, 150), 0.8f)).setPaddingTop(6);
                bloc.add(new Paragraph(b.titre()).setBold().setFontSize(t + 1).setFontColor(bleu).setMarginBottom(4));
                for (FicheSoins.Section s : b.sections()) {
                    if (s.estGrille()) {
                        bloc.add(new Paragraph(s.label()).setBold().setFontSize(t).setFontColor(rouge).setMarginBottom(2));
                        Table g = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1, 1})).useAllAvailableWidth();
                        for (FicheSoins.Champ c : s.grille()) g.addCell(etiquette(c.label(), c.valeur(), rouge, t));
                        for (int reste = (4 - s.grille().size() % 4) % 4; reste > 0; reste--) g.addCell(new Cell().setBorder(Border.NO_BORDER));
                        bloc.add(g.setMarginBottom(6));
                    } else {
                        bloc.add(rubrique(s.label(), s.texte(), s.rouge() ? rouge : bleu, t));
                    }
                }
                doc.add(bloc);
            }
        });
    }

    /** Lettre de référence (generateLettreOrientationA4 du bureau) : A4, paragraphes séparés par une ligne vide. */
    public byte[] lettre(Cadre cadreDoc, String lieu, LocalDate date, String destinataire, String patient, LocalDate naissance, String contenu, String signature) {
        MiseEnPage m = cadreDoc.mise("LETTRE");
        EnTete entete = cadreDoc.entete();
        return a4(entete, m, m.hautPt(entete != null ? 130 : 50), m.droitePt(40), m.basPt(40), m.gauchePt(40), doc -> {
            Rendu r = new Rendu(m, doc);
            r.ajouter("TITRE", r.style(new Paragraph("Lettre de Référence").setTextAlignment(TextAlignment.CENTER).setMarginBottom(15f), "TITRE", TITRE + 2));
            r.ajouter("DATE", r.style(new Paragraph("Fait à " + lieu + ", le " + date.format(JOUR)).setTextAlignment(TextAlignment.RIGHT).setMarginBottom(15f),
                    "DATE", TEXTE - 1));
            float t = m.taille("TEXTE");
            if (destinataire != null && !destinataire.isBlank()) {
                r.ajouter("DESTINATAIRE", new Paragraph().add(new Text("À l'attention de : ").setBold()).add(destinataire)
                        .setFontSize(m.taille("DESTINATAIRE")).setMarginBottom(8f));
            }
            Paragraph info = new Paragraph().setFontSize(m.taille("PATIENT")).setMarginBottom(15f).add(new Text("Concernant : ").setBold()).add(patient + "\n");
            if (naissance != null) info.add(new Text("Né(e) le : ").setBold()).add(naissance.format(JOUR));
            r.ajouter("PATIENT", info);
            for (String para : contenu.replace("\r", "").split("\n\n")) {
                if (!para.isBlank()) doc.add(new Paragraph(para.strip()).setFontSize(t).setTextAlignment(TextAlignment.JUSTIFIED).setMarginBottom(8f).setFirstLineIndent(15f));
            }
            doc.add(new Paragraph("").setMarginTop(20f));
            r.ajouter("SIGNATURE", new Paragraph().setFontSize(m.taille("SIGNATURE")).setTextAlignment(TextAlignment.RIGHT)
                    .add(new Text("Signature et cachet\n\n").setBold()).add(signature));
            r.terminer();
        });
    }

    /** Compte rendu d'examen (generateCompteRenduETTPdf du bureau, généralisé aux spécialités) : A4. */
    public byte[] compteRendu(Cadre cadreDoc, String titre, String sousTitre, LocalDate date, String patient, LocalDate naissance, Integer age,
                              String contenu, String conclusion, String signature) {
        MiseEnPage m = cadreDoc.mise("COMPTE_RENDU");
        EnTete entete = cadreDoc.entete();
        return a4(entete, m, m.hautPt(entete != null ? 130 : 50), m.droitePt(36), m.basPt(50), m.gauchePt(36), doc -> {
            Rendu r = new Rendu(m, doc);
            if (entete == null && m.haut() == null) doc.add(new Paragraph("").setMarginBottom(40));
            r.ajouter("TITRE", r.style(new Paragraph(titre).setTextAlignment(TextAlignment.CENTER).setUnderline().setMarginTop(10).setMarginBottom(5),
                    "TITRE", TITRE + 2));
            r.ajouter("SOUS_TITRE", r.style(new Paragraph(sousTitre).setTextAlignment(TextAlignment.CENTER).setMarginBottom(15), "SOUS_TITRE", TEXTE + 2));
            doc.add(new Paragraph("").setBorderBottom(new SolidBorder(new DeviceRgb(0, 0, 0), 1)).setMarginBottom(10));
            r.ajouter("DATE", r.style(new Paragraph("Date de l'examen : " + date.format(JOUR)).setMarginBottom(15), "DATE", TEXTE));
            float tp = m.taille("PATIENT");
            r.ajouter("PATIENT", new Div()
                    .add(new Paragraph("Informations du Patient").setBold().setFontSize(tp + 1).setMarginBottom(5))
                    .add(new Paragraph("Nom : " + patient + "\nDate de naissance : " + (naissance != null ? naissance.format(JOUR) : "Non renseigné")
                            + (age != null ? "\nÂge : " + age + " ans" : "")).setFontSize(tp).setMarginBottom(20)));
            doc.add(new Paragraph("").setBorderBottom(new SolidBorder(new DeviceRgb(200, 200, 200), 0.5f)).setMarginBottom(15));
            float t = m.taille("TEXTE");
            doc.add(new Paragraph("Compte Rendu de l'Examen").setBold().setFontSize(t + 1).setMarginBottom(10));
            doc.add(new Paragraph(contenu).setTextAlignment(TextAlignment.JUSTIFIED).setFontSize(t).setMarginBottom(30));
            if (conclusion != null && !conclusion.isBlank()) {
                doc.add(new Paragraph("Conclusion").setBold().setFontSize(t + 1).setMarginBottom(8));
                doc.add(new Paragraph(conclusion.strip()).setTextAlignment(TextAlignment.JUSTIFIED).setFontSize(t).setItalic().setMarginBottom(30));
            }
            float ts = m.taille("SIGNATURE");
            r.ajouter("SIGNATURE", new Div().setKeepTogether(true)
                    .add(new Paragraph("Signature et cachet du médecin").setTextAlignment(TextAlignment.RIGHT).setFontSize(ts).setMarginTop(30))
                    .add(new Paragraph(signature).setTextAlignment(TextAlignment.RIGHT).setFontSize(ts).setBold()));
            r.terminer();
        });
    }

    private static byte[] a4(EnTete entete, MiseEnPage mise, float haut, float droite, float bas, float gauche, Contenu contenu) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(sortie)); Document doc = new Document(pdf, PageSize.A4, false)) {
            if (entete != null) enTete(pdf, entete, haut, gauche, 11f);
            doc.setMargins(haut, droite, bas, gauche);
            doc.setFont(police(mise, false));
            contenu.remplir(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sortie.toByteArray();
    }

    /** Police du document : Helvetica (par défaut), Times ou Courier ; toutes contiennent les accents français. */
    static PdfFont police(MiseEnPage mise, boolean gras) throws IOException {
        String p = mise == null || mise.police() == null ? "HELVETICA" : mise.police();
        return PdfFontFactory.createFont(switch (p) {
            case "TIMES" -> gras ? StandardFonts.TIMES_BOLD : StandardFonts.TIMES_ROMAN;
            case "COURIER" -> gras ? StandardFonts.COURIER_BOLD : StandardFonts.COURIER;
            default -> gras ? StandardFonts.HELVETICA_BOLD : StandardFonts.HELVETICA;
        });
    }

    /**
     * Applique les réglages d'impression pendant la construction d'un document : taille et gras des éléments, éléments
     * masqués, et éléments placés à une position fixe (x, y en mm depuis le coin haut-gauche), dessinés en fin de
     * document sur la première page au lieu de suivre le texte. Grille de calage en mm pour l'aperçu.
     */
    static final class Rendu {
        private final MiseEnPage m;
        private final Document doc;
        private final List<Object[]> places = new java.util.ArrayList<>();

        Rendu(MiseEnPage m, Document doc) { this.m = m; this.doc = doc; }

        /** Taille (réglée, sinon celle du code) et gras de l'élément. */
        <T extends com.itextpdf.layout.element.AbstractElement<T>> T style(T e, String cle, float tailleParDefaut) {
            e.setFontSize(m.taille(cle, tailleParDefaut));
            if (m.gras(cle)) e.setBold();
            return e;
        }

        /** Ajoute l'élément au fil du texte, ou le met de côté s'il est placé ; rien s'il est masqué. */
        void ajouter(String cle, com.itextpdf.layout.element.IBlockElement e) {
            if (!m.visible(cle)) return;
            if (m.place(cle)) places.add(new Object[]{cle, e});
            else doc.add(e);
        }

        /** Dessine les éléments placés sur la première page, puis la grille de calage si demandée. */
        void terminer() throws IOException {
            PdfDocument pdf = doc.getPdfDocument();
            if (pdf.getNumberOfPages() == 0) pdf.addNewPage();
            com.itextpdf.kernel.pdf.PdfPage page = pdf.getPage(1);
            Rectangle t = page.getPageSize();
            for (Object[] p : places) {
                MiseEnPage.Element el = m.element((String) p[0]);
                float x = MiseEnPage.pt(el.x()), y = MiseEnPage.pt(el.y());
                float largeur = Math.max(40f, t.getWidth() - x - Math.min(doc.getRightMargin(), 20f));
                com.itextpdf.layout.element.IBlockElement e = (com.itextpdf.layout.element.IBlockElement) p[1];
                if (e instanceof com.itextpdf.layout.element.BlockElement<?> b) {
                    b.setMarginTop(0);
                    b.setMarginLeft(0);
                    b.setMarginRight(0);
                    b.setTextAlignment(TextAlignment.LEFT);
                }
                // état graphique isolé (q … Q) : couleurs et police ne débordent pas sur le reste de la page
                PdfCanvas fond = new PdfCanvas(page.newContentStreamAfter(), page.getResources(), pdf);
                fond.saveState();
                try (Canvas c = new Canvas(fond, new Rectangle(x, 0, largeur, Math.max(1f, t.getHeight() - y)))) {
                    c.setFont(police(m, false));
                    c.add(e);
                }
                fond.restoreState();
            }
            if (m.grille()) grille(pdf, page);
        }

        /** Grille de calage : un trait tous les 5 mm, plus marqué tous les 10 mm, numéroté en mm depuis le haut-gauche. */
        private static void grille(PdfDocument pdf, com.itextpdf.kernel.pdf.PdfPage page) throws IOException {
            Rectangle t = page.getPageSize();
            // sous le texte (flux placé avant), dans un état graphique isolé
            PdfCanvas c = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdf);
            c.saveState();
            PdfFont f = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            for (int mm = 5; MiseEnPage.pt(mm) < t.getWidth(); mm += 5) {
                float x = MiseEnPage.pt(mm);
                c.setStrokeColor(mm % 10 == 0 ? new DeviceRgb(150, 180, 230) : new DeviceRgb(215, 225, 242)).setLineWidth(mm % 10 == 0 ? 0.5f : 0.3f)
                        .moveTo(x, 0).lineTo(x, t.getHeight()).stroke();
                if (mm % 10 == 0) c.beginText().setFontAndSize(f, 5).setFillColor(new DeviceRgb(60, 100, 180))
                        .moveText(x + 1, t.getHeight() - 7).showText(String.valueOf(mm)).endText();
            }
            for (int mm = 5; MiseEnPage.pt(mm) < t.getHeight(); mm += 5) {
                float y = t.getHeight() - MiseEnPage.pt(mm);
                c.setStrokeColor(mm % 10 == 0 ? new DeviceRgb(150, 180, 230) : new DeviceRgb(215, 225, 242)).setLineWidth(mm % 10 == 0 ? 0.5f : 0.3f)
                        .moveTo(0, y).lineTo(t.getWidth(), y).stroke();
                if (mm % 10 == 0) c.beginText().setFontAndSize(f, 5).setFillColor(new DeviceRgb(60, 100, 180))
                        .moveText(1.5f, y + 1).showText(String.valueOf(mm)).endText();
            }
            c.restoreState();
        }
    }

    private static Cell etiquette(String label, String valeur, DeviceRgb couleur, float taille) {
        return new Cell().add(new Paragraph().add(new Text(label).setBold().setFontColor(couleur)).add(new Text(" " + texte(valeur)))
                .setFontSize(taille)).setBorder(Border.NO_BORDER);
    }

    private static Paragraph rubrique(String label, String valeur, DeviceRgb couleur, float taille) {
        Paragraph p = new Paragraph().add(new Text(label).setBold().setFontColor(couleur)).add(new Text("\n")).setFontSize(taille);
        if (valeur != null && !valeur.isBlank()) p.add(new Text(valeur));
        return p.setMarginBottom(8);
    }

    @FunctionalInterface
    interface Contenu {
        void remplir(Document doc) throws IOException;
    }

    /** A5 portrait, impression à 100 % (pas de mise à l'échelle) pour tomber juste sur la feuille préimprimée. */
    private static byte[] pdf(EnTete entete, MiseEnPage mise, float margeHaut, float margeBas, float gauche, float droite, Contenu contenu) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(sortie))) {
            pdf.setDefaultPageSize(PageSize.A5);
            if (entete != null) enTete(pdf, entete, margeHaut, 26f, 9f);
            PdfViewerPreferences preferences = new PdfViewerPreferences();
            preferences.setPrintScaling(PdfViewerPreferences.PdfViewerPreferencesConstants.NONE);
            pdf.getCatalog().setViewerPreferences(preferences);
            // immediateFlush = false : les éléments placés sont dessinés sur la première page une fois le texte posé
            try (Document doc = new Document(pdf, PageSize.A5, false)) {
                doc.setMargins(margeHaut, droite, margeBas, gauche);
                doc.setFont(police(mise, false));
                contenu.remplir(doc);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sortie.toByteArray();
    }

    /**
     * Dessine l'en-tête du médecin dans la marge haute de chaque page : à gauche le médecin (nom, spécialité,
     * n° d'Ordre, INPE), à droite le cabinet (nom, adresse, téléphone), puis un filet.
     */
    private static void enTete(PdfDocument pdf, EnTete e, float margeHaut, float cote, float taille) throws IOException {
        PdfFont normal = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont gras = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        PdfFont italique = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE);
        pdf.addEventHandler(PdfDocumentEvent.END_PAGE, ev -> {
            PdfPage page = ((PdfDocumentEvent) ev).getPage();
            Rectangle t = page.getPageSize();
            float hautZone = t.getTop() - 18f;
            float basZone = t.getTop() - margeHaut + 8f;
            Rectangle zone = new Rectangle(t.getLeft() + cote, basZone, t.getWidth() - 2 * cote, hautZone - basZone);
            Table tableau = new Table(UnitValue.createPercentArray(new float[]{3f, 2f})).setWidth(UnitValue.createPercentValue(100))
                    .setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(VERT, 1.2f));
            Paragraph gauche = new Paragraph().setMultipliedLeading(1.15f).setMargin(0);
            if (!texte(e.medecin()).isEmpty()) gauche.add(new Text(e.medecin().strip()).setFont(gras).setFontSize(taille + 4).setFontColor(ARDOISE));
            if (!texte(e.specialite()).isEmpty()) gauche.add(new Text("\n" + e.specialite().strip()).setFont(italique).setFontSize(taille + 1).setFontColor(VERT));
            if (!texte(e.numeroOrdre()).isEmpty()) gauche.add(new Text("\nInscrit à l'Ordre n° " + e.numeroOrdre().strip()).setFont(normal).setFontSize(taille));
            if (!texte(e.identifiant()).isEmpty()) gauche.add(new Text("\n" + e.identifiant().strip()).setFont(normal).setFontSize(taille));
            Paragraph droite = new Paragraph().setMultipliedLeading(1.15f).setMargin(0).setTextAlignment(TextAlignment.RIGHT);
            boolean premier = true;
            for (String[] l : new String[][]{{e.cabinet(), "g"}, {e.adresse(), ""}, {texte(e.telephone()).isEmpty() ? null : "Tél. : " + e.telephone().strip(), ""}}) {
                if (texte(l[0]).isEmpty()) continue;
                droite.add(new Text((premier ? "" : "\n") + l[0].strip()).setFont(l[1].isEmpty() ? normal : gras).setFontSize(taille));
                premier = false;
            }
            tableau.addCell(new Cell().add(gauche).setBorder(Border.NO_BORDER).setPadding(0).setPaddingBottom(5));
            tableau.addCell(new Cell().add(droite).setBorder(Border.NO_BORDER).setPadding(0).setPaddingBottom(5).setVerticalAlignment(VerticalAlignment.BOTTOM));
            try (Canvas canvas = new Canvas(new PdfCanvas(page.newContentStreamAfter(), page.getResources(), pdf), zone)) {
                canvas.add(tableau);
            }
        });
    }

    /** « Dr Karim Benali », sans doubler le titre si le nom du compte le contient déjà (« Dr. Benali », « Docteur Benali »). */
    public static String docteur(String nom) {
        return "Dr " + texte(nom).replaceFirst("(?i)^(dr\\.?|docteur)\\s+", "");
    }

    private static String texte(String s) { return s == null ? "" : s.strip(); }

    private static String upper(String s) { return s == null ? "" : s.toUpperCase(Locale.FRENCH); }
}
