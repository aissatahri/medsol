package com.medsol.caisse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Montants dans la devise du cabinet : symbole imprimé et montant en toutes lettres (repris de Montants de
 * SGCM-Cardio, qui ne connaissait que le dirham).
 */
public final class Monnaie {

    private record Noms(String symbole, String unite, String unites, String centime, String centimes, boolean decimales) {}

    private static Noms noms(String devise) {
        return switch (devise == null ? "" : devise) {
            case "MAD" -> new Noms("DH", "dirham", "dirhams", "centime", "centimes", true);
            case "EUR" -> new Noms("€", "euro", "euros", "centime", "centimes", true);
            case "XOF", "XAF" -> new Noms("FCFA", "franc CFA", "francs CFA", "", "", false);
            case "USD" -> new Noms("$", "dollar", "dollars", "cent", "cents", true);
            case "DZD" -> new Noms("DA", "dinar", "dinars", "centime", "centimes", true);
            case "TND" -> new Noms("DT", "dinar", "dinars", "millime", "millimes", true);
            default -> new Noms(devise, devise, devise, "centime", "centimes", true);
        };
    }

    private static final String[] UNITES = {"", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] DIZAINES = {"", "", "vingt", "trente", "quarante", "cinquante",
            "soixante", "soixante-dix", "quatre-vingt", "quatre-vingt-dix"};

    private Monnaie() {}

    public static String symbole(String devise) { return noms(devise).symbole(); }

    public static boolean decimales(String devise) { return noms(devise).decimales(); }

    public static BigDecimal arrondi(BigDecimal v) { return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP); }

    /** « 1 250,50 DH » ; sans décimales pour le franc CFA. */
    public static String format(BigDecimal montant, String devise) {
        boolean dec = decimales(devise);
        DecimalFormatSymbols s = DecimalFormatSymbols.getInstance(Locale.FRENCH);
        s.setGroupingSeparator(' ');
        DecimalFormat f = new DecimalFormat(dec ? "#,##0.00" : "#,##0", s);
        return f.format(arrondi(montant)) + " " + symbole(devise);
    }

    /** 1250.50 → « Mille deux cent cinquante dirhams et cinquante centimes ». */
    public static String enLettres(BigDecimal montant, String devise) {
        Noms n = noms(devise);
        long totalCentimes = arrondi(montant).movePointRight(2).longValue();
        long entiers = totalCentimes / 100;
        long centimes = n.decimales() ? totalCentimes % 100 : 0;
        if (entiers == 0 && centimes == 0) return "Zéro " + n.unite();
        StringBuilder sb = new StringBuilder();
        if (entiers > 0) sb.append(nombre(entiers)).append(' ').append(entiers > 1 ? n.unites() : n.unite());
        if (centimes > 0) {
            if (entiers > 0) sb.append(" et ");
            sb.append(nombre(centimes)).append(' ').append(centimes > 1 ? n.centimes() : n.centime());
        }
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    static String nombre(long n) {
        if (n < 0) return "moins " + nombre(-n);
        if (n == 0) return "zéro";
        if (n < 20) return UNITES[(int) n];
        if (n < 100) {
            int diz = (int) (n / 10);
            int uni = (int) (n % 10);
            if (diz == 7 || diz == 9) {
                // 70-79 et 90-99 : « soixante » / « quatre-vingt » + dix à dix-neuf (72 = soixante-douze).
                // Le bureau et SGCM-Cardio partaient de « soixante-dix » : 72 y devenait « soixante-dix-douze ».
                String base = DIZAINES[diz - 1];
                if (uni == 1 && diz == 7) return base + "-et-onze";
                return base + "-" + UNITES[10 + uni];
            }
            if (diz == 8) return uni == 0 ? "quatre-vingts" : "quatre-vingt-" + UNITES[uni];
            if (uni == 0) return DIZAINES[diz];
            return uni == 1 ? DIZAINES[diz] + "-et-un" : DIZAINES[diz] + "-" + UNITES[uni];
        }
        if (n < 1000) {
            long cents = n / 100;
            long reste = n % 100;
            String c = cents == 1 ? "cent" : UNITES[(int) cents] + " cent";
            if (reste == 0) return cents > 1 ? c + "s" : c;
            return c + " " + nombre(reste);
        }
        if (n < 1_000_000) {
            long milliers = n / 1000;
            long reste = n % 1000;
            String m = milliers == 1 ? "mille" : nombre(milliers) + " mille";
            return reste == 0 ? m : m + " " + nombre(reste);
        }
        if (n < 1_000_000_000L) {
            long millions = n / 1_000_000;
            long reste = n % 1_000_000;
            String m = nombre(millions) + (millions > 1 ? " millions" : " million");
            return reste == 0 ? m : m + " " + nombre(reste);
        }
        long milliards = n / 1_000_000_000L;
        long reste = n % 1_000_000_000L;
        String m = nombre(milliards) + (milliards > 1 ? " milliards" : " milliard");
        return reste == 0 ? m : m + " " + nombre(reste);
    }
}
