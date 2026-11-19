package com.medsol.documents;

import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.barcodes.qrcode.EncodeHintType;
import com.itextpdf.barcodes.qrcode.ErrorCorrectionLevel;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Fiche de présentation de MedSol (A4, une page) à laisser au cabinet après une visite : points forts, offre d'essai,
 * tarif, coordonnées de l'éditeur et QR code vers l'inscription (canal « Visite » présélectionné).
 */
@Component
public class FicheCommerciale {

    private static final DeviceRgb NUIT = new DeviceRgb(11, 23, 48);
    private static final DeviceRgb BLEU = new DeviceRgb(42, 91, 224);
    private static final DeviceRgb BLEU_FONCE = new DeviceRgb(27, 68, 173);
    private static final DeviceRgb BLEU_CLAIR = new DeviceRgb(237, 242, 254);
    private static final DeviceRgb GRIS = new DeviceRgb(71, 85, 105);
    private static final DeviceRgb VERT = new DeviceRgb(4, 120, 87);
    private static final DeviceRgb VERT_CLAIR = new DeviceRgb(232, 247, 239);
    private static final DeviceRgb TEXTE_VITRINE = new DeviceRgb(182, 194, 218);

    /** @param contact lignes de contact (nom, téléphone, e-mail) ; @param prixMensuel ex. « 390 DH » (null : non affiché) */
    public record Fiche(String urlInscription, String urlSite, String prixMensuel, String prixAnnuel, String moisOfferts,
                        List<String> contact, String offreFondateur) {}

    private static final List<String[]> FONCTIONS = List.of(
            new String[]{"Dossier patient complet", "Antécédents, allergies, constantes, couverture sanitaire, consultations adaptées à votre spécialité (22 spécialités)."},
            new String[]{"Ordonnances conformes", "En-tête avec INPE et n° d'Ordre, plus de 6 000 médicaments marocains, alertes allergies et interactions."},
            new String[]{"Agenda et salle d'attente", "Planning du cabinet, salle d'attente en temps réel, horaires, absences et reports en un clic."},
            new String[]{"Rendez-vous en ligne et rappels", "Vos patients réservent depuis votre page ou un QR code ; rappels automatiques la veille."},
            new String[]{"Caisse et factures", "Actes et tarifs, reçus et factures conformes (ICE, IF, montant en lettres), statistiques."},
            new String[]{"Votre équipe connectée", "Un compte pour votre assistante, avec les droits que vous choisissez, et une messagerie interne."});

    private static final List<String> SECURITE = List.of(
            "Connexion chiffrée et double authentification",
            "Données cloisonnées par cabinet, accès tracés",
            "Sauvegardes chiffrées chaque jour, hors du serveur",
            "Hébergement UE, accord de sous-traitance (loi 09-08)");

    public byte[] pdf(Fiche f) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(sortie)); Document doc = new Document(pdf, PageSize.A4)) {
            pdf.getDocumentInfo().setTitle("MedSol — Logiciel de cabinet médical");
            doc.setMargins(0, 0, 0, 0);
            PdfFont n = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            PdfFont g = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont coche = PdfFontFactory.createFont(StandardFonts.ZAPFDINGBATS);

            // ── Bandeau : logo, nom, accroche
            Table logo = new Table(new float[]{46, 400}).setBorder(Border.NO_BORDER);
            logo.addCell(new Cell().add(new Image(logoXObject(pdf)).setWidth(42).setHeight(42)).setBorder(Border.NO_BORDER).setPadding(0)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE));
            logo.addCell(new Cell().add(new Paragraph().add(new Text("MedSol").setFont(g).setFontSize(20).setFontColor(ColorConstants.WHITE))
                            .add(new Text("\nLogiciel de gestion de cabinet médical").setFont(n).setFontSize(10).setFontColor(TEXTE_VITRINE))
                            .setMultipliedLeading(1.05f).setMargin(0))
                    .setBorder(Border.NO_BORDER).setPaddingLeft(10).setVerticalAlignment(VerticalAlignment.MIDDLE));
            Div bandeau = new Div().setBackgroundColor(NUIT).setPaddingTop(26).setPaddingBottom(26).setPaddingLeft(44).setPaddingRight(44);
            bandeau.add(logo);
            bandeau.add(new Paragraph("Votre cabinet médical, simple et bien organisé.").setFont(g).setFontSize(25)
                    .setFontColor(ColorConstants.WHITE).setMarginTop(18).setMarginBottom(6).setMultipliedLeading(1.1f));
            bandeau.add(new Paragraph("Dossiers patients, ordonnances conformes, agenda, rendez-vous en ligne et caisse : tout votre "
                    + "cabinet dans un seul outil, sur ordinateur, tablette et téléphone. Conçu au Maroc, pour les médecins au Maroc.")
                    .setFont(n).setFontSize(11).setFontColor(TEXTE_VITRINE).setMargin(0).setMultipliedLeading(1.3f));
            doc.add(bandeau);

            Div corps = new Div().setPaddingLeft(44).setPaddingRight(44).setPaddingTop(20);

            // ── Six points forts, sur deux colonnes
            corps.add(new Paragraph("Tout ce dont votre cabinet a besoin").setFont(g).setFontSize(14).setFontColor(NUIT).setMarginBottom(8));
            Table fonctions = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
            for (String[] fn : FONCTIONS) {
                Paragraph p = new Paragraph().setMargin(0).setMultipliedLeading(1.25f)
                        .add(new Text("4  ").setFont(coche).setFontSize(10).setFontColor(BLEU))
                        .add(new Text(fn[0]).setFont(g).setFontSize(10.5f).setFontColor(NUIT))
                        .add(new Text("\n" + fn[1]).setFont(n).setFontSize(9).setFontColor(GRIS));
                fonctions.addCell(new Cell().add(p).setBorder(Border.NO_BORDER).setPaddingTop(5).setPaddingBottom(5).setPaddingRight(14));
            }
            corps.add(fonctions);

            // ── Pensé pour le Maroc + sécurité
            Table deux = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth().setMarginTop(12);
            Div maroc = new Div().setBackgroundColor(BLEU_CLAIR).setPadding(12);
            maroc.add(new Paragraph("Pensé pour le Maroc").setFont(g).setFontSize(11).setFontColor(BLEU_FONCE).setMargin(0).setMarginBottom(4));
            for (String s : List.of("Ordonnance : INPE, n° d'Ordre, « Fait à … le … », poids de l'enfant",
                    "Factures et reçus : IF, ICE, TP, exonération de TVA, montant en lettres",
                    "Vos ordonnanciers préimprimés : police, tailles et position de chaque élément réglables")) {
                maroc.add(puce(s, n, coche, BLEU));
            }
            Div secu = new Div().setBackgroundColor(VERT_CLAIR).setPadding(12);
            secu.add(new Paragraph("Les données de vos patients, protégées").setFont(g).setFontSize(11).setFontColor(VERT).setMargin(0).setMarginBottom(4));
            for (String s : SECURITE) secu.add(puce(s, n, coche, VERT));
            deux.addCell(new Cell().add(maroc).setBorder(Border.NO_BORDER).setPadding(0).setPaddingRight(6));
            deux.addCell(new Cell().add(secu).setBorder(Border.NO_BORDER).setPadding(0).setPaddingLeft(6));
            corps.add(deux);

            // ── Offre, contact et QR code
            Table offre = new Table(UnitValue.createPercentArray(new float[]{62, 38})).useAllAvailableWidth().setMarginTop(16)
                    .setBorder(new SolidBorder(BLEU, 1.5f));
            Div gauche = new Div().setPadding(14);
            gauche.add(new Paragraph("30 jours d'essai gratuit").setFont(g).setFontSize(18).setFontColor(BLEU).setMargin(0));
            gauche.add(new Paragraph("Sans carte bancaire · sans engagement · votre cabinet est prêt en 2 minutes.")
                    .setFont(n).setFontSize(9.5f).setFontColor(GRIS).setMargin(0).setMarginBottom(8));
            if (f.offreFondateur() != null) {
                gauche.add(new Paragraph(f.offreFondateur()).setFont(g).setFontSize(9.5f).setFontColor(VERT)
                        .setBackgroundColor(VERT_CLAIR).setPadding(5).setMargin(0).setMarginBottom(6));
            }
            if (f.prixMensuel() != null) {
                Paragraph prix = new Paragraph().setMargin(0)
                        .add(new Text("Ensuite ").setFont(n).setFontSize(10).setFontColor(GRIS))
                        .add(new Text(f.prixMensuel()).setFont(g).setFontSize(16).setFontColor(NUIT))
                        .add(new Text(" / mois").setFont(n).setFontSize(10).setFontColor(GRIS));
                gauche.add(prix);
                if (f.prixAnnuel() != null) {
                    gauche.add(new Paragraph("ou " + f.prixAnnuel() + " par an" + (f.moisOfferts() != null ? " (" + f.moisOfferts() + " mois offerts)" : "")
                            + " · tout compris : patients illimités, compte assistante, mises à jour, sauvegardes, accompagnement")
                            .setFont(n).setFontSize(8.5f).setFontColor(GRIS).setMargin(0).setMarginBottom(8));
                }
            }
            if (!f.contact().isEmpty()) {
                gauche.add(new Paragraph("Démonstration, questions, aide à l'installation :").setFont(g).setFontSize(9.5f).setFontColor(NUIT).setMargin(0));
                for (String c : f.contact()) gauche.add(new Paragraph(c).setFont(n).setFontSize(9.5f).setFontColor(NUIT).setMargin(0));
            }
            Div droite = new Div().setPadding(12).setBackgroundColor(new DeviceRgb(247, 249, 252)).setTextAlignment(TextAlignment.CENTER);
            droite.add(new Paragraph("Scannez pour créer votre cabinet").setFont(g).setFontSize(9.5f).setFontColor(NUIT).setMargin(0).setMarginBottom(4));
            droite.add(new Image(qr(pdf, f.urlInscription())).setWidth(118).setHeight(118)
                    .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER));
            droite.add(new Paragraph(f.urlSite()).setFont(g).setFontSize(10).setFontColor(BLEU).setMargin(0).setMarginTop(4));
            offre.addCell(new Cell().add(gauche).setBorder(Border.NO_BORDER).setPadding(0).setVerticalAlignment(VerticalAlignment.MIDDLE));
            offre.addCell(new Cell().add(droite).setBorder(Border.NO_BORDER).setPadding(0).setVerticalAlignment(VerticalAlignment.MIDDLE));
            corps.add(offre);
            doc.add(corps);

            // ── Pied de page fixe
            doc.add(new Paragraph("MedSol · " + f.urlSite() + " · Logiciel conforme aux usages marocains · Données hébergées dans l'Union européenne")
                    .setFont(n).setFontSize(7.5f).setFontColor(GRIS).setTextAlignment(TextAlignment.CENTER)
                    .setFixedPosition(1, 0, 18, PageSize.A4.getWidth()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sortie.toByteArray();
    }

    private static Paragraph puce(String texte, PdfFont n, PdfFont coche, DeviceRgb couleur) {
        return new Paragraph().setMargin(0).setMarginTop(3).setMultipliedLeading(1.2f)
                .add(new Text("4  ").setFont(coche).setFontSize(8).setFontColor(couleur))
                .add(new Text(texte).setFont(n).setFontSize(8.8f).setFontColor(NUIT));
    }

    /** Logo de l'application : carré arrondi bleu, croix blanche traversée d'un tracé d'ECG. */
    private static PdfFormXObject logoXObject(PdfDocument pdf) {
        PdfFormXObject x = new PdfFormXObject(new Rectangle(24, 24));
        PdfCanvas c = new PdfCanvas(x, pdf);
        c.setFillColor(BLEU).roundRectangle(0, 0, 24, 24, 5.5f).fill();
        // croix (repère SVG retourné : y → 24 − y), réduite à 62 % et centrée
        c.saveState();
        c.concatMatrix(0.62, 0, 0, -0.62, 24 * 0.19, 24 - 24 * 0.19);
        c.setFillColor(ColorConstants.WHITE)
                .moveTo(9, 3).lineTo(15, 3).lineTo(15, 9).lineTo(21, 9).lineTo(21, 15).lineTo(15, 15).lineTo(15, 21)
                .lineTo(9, 21).lineTo(9, 15).lineTo(3, 15).lineTo(3, 9).lineTo(9, 9).closePath().fill();
        c.setStrokeColor(BLEU_FONCE).setLineWidth(1.7f).setLineCapStyle(1).setLineJoinStyle(1)
                .moveTo(3.5, 12).lineTo(8.1, 12).lineTo(9.8, 8.8).lineTo(12.4, 15.2).lineTo(14.3, 12).lineTo(20.5, 12).stroke();
        c.restoreState();
        return x;
    }

    private static PdfFormXObject qr(PdfDocument pdf, String url) {
        BarcodeQRCode code = new BarcodeQRCode(url, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        return code.createFormXObject(NUIT, pdf);
    }
}
