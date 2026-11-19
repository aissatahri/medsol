package com.medsol.documents;

import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfViewerPreferences;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.medsol.caisse.Monnaie;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Facture, reçu de paiement et clôture de caisse (A5), repris de FacturesPdf de SGCM-Cardio (mise en page de
 * FacturePdfService du bureau) : en-tête du cabinet, code-barres de l'identifiant professionnel, montant en lettres.
 * Montants dans la devise du cabinet.
 */
@Component
public class FacturesPdf {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * En-tête : nom du cabinet, médecin, mentions légales (une par ligne), identifiant professionnel (brut pour le
     * code-barres, et tel qu'imprimé : « INPE : … »), devise ; {@code piedDePage} : mention fiscale (Maroc : exonération de TVA).
     */
    public record Entete(String titre, String medecin, List<String> mentions, String identifiant, String identifiantImprime,
                         String devise, String piedDePage) {}

    public record Article(String acte, BigDecimal prix) {}

    /** Acte du reçu : « Offert » s'il n'est pas payé. */
    public record ActeRecu(String acte, BigDecimal prix, boolean paye) {}

    public record VersementRecu(LocalDate date, String mode, BigDecimal montant) {}

    public record VersementJour(String patient, String dossier, String mode, BigDecimal montant) {}

    public byte[] facture(Entete e, String patient, String dossier, LocalDate date, String numero, List<Article> articles) {
        return a5(16, 26, 18, 26, (pdf, doc, p) -> {
            entete(doc, e, p);
            if (rempli(e.identifiant())) {
                Barcode128 code = new Barcode128(pdf);
                code.setCode(e.identifiant().strip());
                code.setBarHeight(24f);
                code.setFont(PdfFontFactory.createFont(StandardFonts.COURIER));
                code.setSize(8f);
                doc.add(new Image(code.createFormXObject(ColorConstants.BLACK, ColorConstants.BLACK, pdf)).setWidth(120).setMarginBottom(6));
            }
            doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginBottom(8));
            doc.add(new Paragraph().add(new Text("FACTURE  ").setFont(p.gras).setFontSize(13))
                    .add(new Text("N° " + numero).setFont(p.normal).setFontSize(10).setFontColor(ColorConstants.DARK_GRAY))
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));
            Table ligne = sansBord(2).setMarginBottom(4);
            ligne.addCell(cellule(libelle("Patient : ", patient, p)));
            ligne.addCell(cellule(libelle("Date : ", date.format(JOUR), p).setTextAlignment(TextAlignment.RIGHT)));
            doc.add(ligne);
            Table dos = sansBord(2).setMarginBottom(8);
            dos.addCell(cellule(libelle("N° dossier : ", rempli(dossier) ? dossier : "—", p)));
            dos.addCell(cellule(new Paragraph("")));
            doc.add(dos);

            Table t = new Table(new float[]{4f, 1.6f}).setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6);
            t.addHeaderCell(enTete("Acte", TextAlignment.LEFT, p));
            t.addHeaderCell(enTete("Montant (" + Monnaie.symbole(e.devise()) + ")", TextAlignment.RIGHT, p));
            BigDecimal total = BigDecimal.ZERO;
            for (Article a : articles) {
                total = total.add(a.prix());
                t.addCell(new Cell().add(new Paragraph(a.acte()).setFont(p.normal).setFontSize(10)).setPadding(3));
                t.addCell(droite(montant(a.prix(), e.devise()), p.normal));
            }
            doc.add(t);
            Table tot = sansBord(2).setMarginBottom(6);
            tot.addCell(cellule(new Paragraph("")));
            tot.addCell(cellule(new Paragraph().add(new Text("TOTAL : ").setFont(p.gras).setFontSize(11))
                    .add(new Text(Monnaie.format(total, e.devise())).setFont(p.gras).setFontSize(11)).setTextAlignment(TextAlignment.RIGHT)));
            doc.add(tot);
            doc.add(new Paragraph().add(new Text("Arrêté la somme de : ").setFont(p.gras).setFontSize(10))
                    .add(new Text(Monnaie.enLettres(total, e.devise())).setFont(p.italique).setFontSize(10)).setMarginBottom(10));
            piedDePage(doc, e, p);
            doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginBottom(6));
            doc.add(new Paragraph("Cachet et signature").setFont(p.normal).setFontSize(9).setTextAlignment(TextAlignment.RIGHT).setFontColor(ColorConstants.GRAY));
        });
    }

    public byte[] recu(Entete e, String patient, String dossier, LocalDate dateConsultation, long numero, LocalDate dateRecu,
                       List<ActeRecu> actes, List<VersementRecu> versements, BigDecimal total) {
        BigDecimal paye = versements.stream().map(VersementRecu::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reste = total.subtract(paye).max(BigDecimal.ZERO);
        return a5(14, 28, 14, 28, (pdf, doc, p) -> {
            entete(doc, e, p);
            doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginBottom(6));
            doc.add(new Paragraph().add(new Text("REÇU DE PAIEMENT  ").setFont(p.gras).setFontSize(13))
                    .add(new Text("N° R-" + numero).setFont(p.normal).setFontSize(10).setFontColor(ColorConstants.DARK_GRAY))
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));
            Table infos = sansBord(2).setMarginBottom(8);
            infos.addCell(cellule(libelle("Patient : ", patient, p)));
            infos.addCell(cellule(libelle("Date : ", dateRecu.format(JOUR), p).setTextAlignment(TextAlignment.RIGHT)));
            infos.addCell(cellule(libelle("N° dossier : ", rempli(dossier) ? dossier : "—", p)));
            infos.addCell(cellule(libelle("Consultation du : ", dateConsultation != null ? dateConsultation.format(JOUR) : "—", p)
                    .setTextAlignment(TextAlignment.RIGHT)));
            doc.add(infos);
            String sym = Monnaie.symbole(e.devise());
            if (!actes.isEmpty()) {
                Table ta = new Table(new float[]{4f, 1.5f}).setWidth(UnitValue.createPercentValue(100)).setMarginBottom(8);
                ta.addHeaderCell(enTete("Acte", TextAlignment.LEFT, p));
                ta.addHeaderCell(enTete("Prix (" + sym + ")", TextAlignment.RIGHT, p));
                for (ActeRecu a : actes) {
                    ta.addCell(new Cell().add(new Paragraph(a.acte()).setFont(p.normal).setFontSize(10)).setPadding(3));
                    ta.addCell(droite(a.paye() ? montant(a.prix(), e.devise()) : "Offert", p.normal));
                }
                doc.add(ta);
            }
            Table tv = new Table(new float[]{2f, 2f, 1.5f}).setWidth(UnitValue.createPercentValue(100)).setMarginBottom(8);
            tv.addHeaderCell(enTete("Versement du", TextAlignment.LEFT, p));
            tv.addHeaderCell(enTete("Mode", TextAlignment.LEFT, p));
            tv.addHeaderCell(enTete("Montant (" + sym + ")", TextAlignment.RIGHT, p));
            for (VersementRecu v : versements) {
                tv.addCell(new Cell().add(new Paragraph(v.date() != null ? v.date().format(JOUR) : "—").setFont(p.normal).setFontSize(10)).setPadding(3));
                tv.addCell(new Cell().add(new Paragraph(rempli(v.mode()) ? v.mode() : "—").setFont(p.normal).setFontSize(10)).setPadding(3));
                tv.addCell(droite(montant(v.montant(), e.devise()), p.normal));
            }
            doc.add(tv);
            doc.add(new Paragraph("Total : " + Monnaie.format(total, e.devise())).setFont(p.normal).setFontSize(10).setTextAlignment(TextAlignment.RIGHT).setMarginBottom(1));
            doc.add(new Paragraph("Payé : " + Monnaie.format(paye, e.devise())).setFont(p.gras).setFontSize(11).setTextAlignment(TextAlignment.RIGHT).setMarginBottom(1));
            doc.add(new Paragraph(reste.compareTo(new BigDecimal("0.005")) > 0 ? "Reste à payer : " + Monnaie.format(reste, e.devise()) : "Soldé")
                    .setFont(p.gras).setFontSize(11).setTextAlignment(TextAlignment.RIGHT).setMarginBottom(6));
            doc.add(new Paragraph().add(new Text("Arrêté la somme reçue à : ").setFont(p.gras).setFontSize(9))
                    .add(new Text(Monnaie.enLettres(paye, e.devise())).setFont(p.italique).setFontSize(9)).setMarginBottom(10));
            piedDePage(doc, e, p);
            doc.add(new Paragraph("Cachet et signature").setFont(p.normal).setFontSize(9).setTextAlignment(TextAlignment.RIGHT).setFontColor(ColorConstants.GRAY));
        });
    }

    /** Facture d'abonnement de MedSol à un cabinet : l'éditeur (vous) facture le médecin. */
    public record FactureAbonnement(String emetteur, List<String> emetteurMentions, String mentionTva,
                                    List<String> client, String numero, LocalDate date, String designation,
                                    BigDecimal montant, String devise, String reglement) {}

    public byte[] factureAbonnement(FactureAbonnement f) {
        return a5(18, 26, 18, 26, (pdf, doc, p) -> {
            doc.add(new Paragraph(f.emetteur()).setFont(p.gras).setFontSize(14).setMarginBottom(2));
            for (String m : f.emetteurMentions()) doc.add(new Paragraph(m).setFont(p.normal).setFontSize(9).setMultipliedLeading(1.1f).setMargin(0));
            doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginTop(8).setMarginBottom(8));
            doc.add(new Paragraph().add(new Text("FACTURE  ").setFont(p.gras).setFontSize(13))
                    .add(new Text("N° " + f.numero()).setFont(p.normal).setFontSize(10).setFontColor(ColorConstants.DARK_GRAY))
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2));
            doc.add(new Paragraph("Date : " + f.date().format(JOUR)).setFont(p.normal).setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(10));
            Div client = new Div().setBorder(new com.itextpdf.layout.borders.SolidBorder(ColorConstants.LIGHT_GRAY, 0.8f)).setPadding(6).setMarginBottom(10);
            client.add(new Paragraph("Facturé à").setFont(p.gras).setFontSize(9).setFontColor(ColorConstants.DARK_GRAY).setMargin(0));
            for (String c : f.client()) client.add(new Paragraph(c).setFont(p.normal).setFontSize(10).setMultipliedLeading(1.1f).setMargin(0));
            doc.add(client);
            Table t = new Table(new float[]{4f, 1.6f}).setWidth(UnitValue.createPercentValue(100)).setMarginBottom(6);
            t.addHeaderCell(enTete("Désignation", TextAlignment.LEFT, p));
            t.addHeaderCell(enTete("Montant (" + Monnaie.symbole(f.devise()) + ")", TextAlignment.RIGHT, p));
            t.addCell(new Cell().add(new Paragraph(f.designation()).setFont(p.normal).setFontSize(10)).setPadding(4));
            t.addCell(droite(montant(f.montant(), f.devise()), p.normal));
            doc.add(t);
            doc.add(new Paragraph().add(new Text("TOTAL : ").setFont(p.gras).setFontSize(11))
                    .add(new Text(Monnaie.format(f.montant(), f.devise())).setFont(p.gras).setFontSize(11))
                    .setTextAlignment(TextAlignment.RIGHT).setMarginBottom(6));
            doc.add(new Paragraph().add(new Text("Arrêtée la présente facture à la somme de : ").setFont(p.gras).setFontSize(9))
                    .add(new Text(Monnaie.enLettres(f.montant(), f.devise())).setFont(p.italique).setFontSize(9)).setMarginBottom(8));
            if (rempli(f.mentionTva())) doc.add(new Paragraph(f.mentionTva()).setFont(p.italique).setFontSize(8).setMarginBottom(4));
            doc.add(new Paragraph(f.reglement()).setFont(p.normal).setFontSize(9).setFontColor(ColorConstants.DARK_GRAY));
        });
    }

    /** Clôture de caisse du jour : versements, totaux par mode, total, signatures. */
    public byte[] cloture(Entete e, LocalDate jour, List<VersementJour> versements, Map<String, BigDecimal> parMode, BigDecimal reste) {
        BigDecimal total = versements.stream().map(VersementJour::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        return a5(14, 26, 18, 26, (pdf, doc, p) -> {
            entete(doc, e, p);
            doc.add(new LineSeparator(new SolidLine(0.5f)).setMarginBottom(6));
            doc.add(new Paragraph().add(new Text("CLÔTURE DE CAISSE  ").setFont(p.gras).setFontSize(13))
                    .add(new Text("du " + jour.format(JOUR)).setFont(p.normal).setFontSize(11))
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));
            Table t = new Table(new float[]{3.2f, 1.6f, 1.4f}).setWidth(UnitValue.createPercentValue(100)).setMarginBottom(8);
            t.addHeaderCell(enTete("Patient", TextAlignment.LEFT, p));
            t.addHeaderCell(enTete("Mode", TextAlignment.LEFT, p));
            t.addHeaderCell(enTete("Montant (" + Monnaie.symbole(e.devise()) + ")", TextAlignment.RIGHT, p));
            if (versements.isEmpty()) {
                t.addCell(new Cell(1, 3).add(new Paragraph("Aucun encaissement ce jour.").setFont(p.italique).setFontSize(9)).setPadding(4));
            }
            for (VersementJour v : versements) {
                t.addCell(new Cell().add(new Paragraph(v.patient() + (rempli(v.dossier()) ? "  (" + v.dossier() + ")" : "")).setFont(p.normal).setFontSize(9)).setPadding(3));
                t.addCell(new Cell().add(new Paragraph(rempli(v.mode()) ? v.mode() : "—").setFont(p.normal).setFontSize(9)).setPadding(3));
                t.addCell(droite(montant(v.montant(), e.devise()), p.normal));
            }
            doc.add(t);
            Table tot = new Table(new float[]{3f, 1.4f}).setWidth(UnitValue.createPercentValue(70))
                    .setHorizontalAlignment(HorizontalAlignment.RIGHT).setMarginBottom(6);
            parMode.forEach((mode, m) -> {
                tot.addCell(cellule(new Paragraph(mode).setFont(p.normal).setFontSize(10)));
                tot.addCell(cellule(new Paragraph(Monnaie.format(m, e.devise())).setFont(p.normal).setFontSize(10).setTextAlignment(TextAlignment.RIGHT)));
            });
            tot.addCell(cellule(new Paragraph("Total encaissé (" + versements.size() + " versement" + (versements.size() > 1 ? "s" : "") + ")").setFont(p.gras).setFontSize(11)));
            tot.addCell(cellule(new Paragraph(Monnaie.format(total, e.devise())).setFont(p.gras).setFontSize(11).setTextAlignment(TextAlignment.RIGHT)));
            if (reste.compareTo(new BigDecimal("0.005")) > 0) {
                tot.addCell(cellule(new Paragraph("Reste à encaisser (consultations du jour)").setFont(p.normal).setFontSize(9)));
                tot.addCell(cellule(new Paragraph(Monnaie.format(reste, e.devise())).setFont(p.normal).setFontSize(9).setTextAlignment(TextAlignment.RIGHT)));
            }
            doc.add(tot);
            doc.add(new Paragraph().add(new Text("Arrêté à la somme de : ").setFont(p.gras).setFontSize(9))
                    .add(new Text(Monnaie.enLettres(total, e.devise())).setFont(p.italique).setFontSize(9)).setMarginBottom(16));
            Table sig = sansBord(2);
            sig.addCell(cellule(new Paragraph("Assistante").setFont(p.normal).setFontSize(9).setFontColor(ColorConstants.GRAY)));
            sig.addCell(cellule(new Paragraph("Médecin").setFont(p.normal).setFontSize(9).setFontColor(ColorConstants.GRAY).setTextAlignment(TextAlignment.RIGHT)));
            doc.add(sig);
        });
    }

    // ─────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface Contenu {
        void remplir(PdfDocument pdf, Document doc, Polices p) throws IOException;
    }

    /** A5, impression à 100 % (pas d'« ajuster à la page ») : la demi-feuille sort à la bonne taille. */
    private static byte[] a5(float haut, float droite, float bas, float gauche, Contenu contenu) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(sortie)); Document doc = new Document(pdf, PageSize.A5)) {
            pdf.setDefaultPageSize(PageSize.A5);
            PdfViewerPreferences pref = new PdfViewerPreferences();
            pref.setPrintScaling(PdfViewerPreferences.PdfViewerPreferencesConstants.NONE);
            pdf.getCatalog().setViewerPreferences(pref);
            doc.setMargins(haut, droite, bas, gauche);
            contenu.remplir(pdf, doc, new Polices());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return sortie.toByteArray();
    }

    static final class Polices {
        final PdfFont gras;
        final PdfFont normal;
        final PdfFont italique;

        Polices() throws IOException {
            gras = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            normal = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            italique = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE);
        }
    }

    private static void entete(Document doc, Entete e, Polices p) {
        if (rempli(e.titre())) doc.add(new Paragraph(e.titre()).setFont(p.gras).setFontSize(13).setMarginBottom(2));
        if (rempli(e.medecin())) doc.add(new Paragraph(e.medecin()).setFont(p.normal).setFontSize(10).setMarginTop(0).setMarginBottom(2));
        for (String m : e.mentions()) doc.add(new Paragraph(m).setFont(p.normal).setFontSize(9).setMultipliedLeading(1.1f).setMargin(0));
        String id = rempli(e.identifiantImprime()) ? e.identifiantImprime() : e.identifiant();
        if (rempli(id)) doc.add(new Paragraph(id).setFont(p.normal).setFontSize(9).setMultipliedLeading(1.1f).setMargin(0).setMarginBottom(4));
    }

    /** Mention fiscale en bas de facture ou de reçu. */
    private static void piedDePage(Document doc, Entete e, Polices p) {
        if (rempli(e.piedDePage())) {
            doc.add(new Paragraph(e.piedDePage()).setFont(p.italique).setFontSize(8).setFontColor(ColorConstants.DARK_GRAY).setMarginBottom(4));
        }
    }

    private static String montant(BigDecimal v, String devise) {
        return Monnaie.format(v, devise).replace(" " + Monnaie.symbole(devise), "");
    }

    private static Paragraph libelle(String titre, String valeur, Polices p) {
        return new Paragraph().add(new Text(titre).setFont(p.gras).setFontSize(10)).add(new Text(valeur == null ? "" : valeur).setFont(p.normal).setFontSize(10));
    }

    private static Cell enTete(String texte, TextAlignment a, Polices p) {
        return new Cell().add(new Paragraph(texte).setFont(p.gras).setFontSize(9)).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(a).setPadding(3);
    }

    private static Table sansBord(int colonnes) {
        float[] l = new float[colonnes];
        java.util.Arrays.fill(l, 1f);
        return new Table(l).setBorder(Border.NO_BORDER).setWidth(UnitValue.createPercentValue(100));
    }

    private static Cell cellule(Paragraph p) { return new Cell().add(p).setBorder(Border.NO_BORDER); }

    private static Cell droite(String texte, PdfFont police) {
        return new Cell().add(new Paragraph(texte).setFont(police).setFontSize(10)).setTextAlignment(TextAlignment.RIGHT).setPadding(4);
    }

    private static boolean rempli(String s) { return s != null && !s.isBlank(); }
}
