package com.medsol.cabinet;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pays proposés à l'inscription : le médecin choisit son pays, la devise et le fuseau horaire en découlent (il n'a plus
 * à connaître « MAD » ni « Africa/Casablanca »). Ordre : Maroc d'abord (marché principal), puis Maghreb, Afrique de
 * l'Ouest et centrale, Europe, Amérique du Nord.
 */
public record Pays(String code, Map<String, String> nom, String devise, String fuseau) {

    public static final List<Pays> TOUS = List.of(
            new Pays("MA", Map.of("fr", "Maroc", "en", "Morocco", "es", "Marruecos"), "MAD", "Africa/Casablanca"),
            new Pays("DZ", Map.of("fr", "Algérie", "en", "Algeria", "es", "Argelia"), "DZD", "Africa/Algiers"),
            new Pays("TN", Map.of("fr", "Tunisie", "en", "Tunisia", "es", "Túnez"), "TND", "Africa/Tunis"),
            new Pays("SN", Map.of("fr", "Sénégal", "en", "Senegal", "es", "Senegal"), "XOF", "Africa/Dakar"),
            new Pays("CI", Map.of("fr", "Côte d’Ivoire", "en", "Côte d’Ivoire", "es", "Costa de Marfil"), "XOF", "Africa/Abidjan"),
            new Pays("ML", Map.of("fr", "Mali", "en", "Mali", "es", "Malí"), "XOF", "Africa/Bamako"),
            new Pays("BF", Map.of("fr", "Burkina Faso", "en", "Burkina Faso", "es", "Burkina Faso"), "XOF", "Africa/Ouagadougou"),
            new Pays("CM", Map.of("fr", "Cameroun", "en", "Cameroon", "es", "Camerún"), "XAF", "Africa/Douala"),
            new Pays("GA", Map.of("fr", "Gabon", "en", "Gabon", "es", "Gabón"), "XAF", "Africa/Libreville"),
            new Pays("FR", Map.of("fr", "France", "en", "France", "es", "Francia"), "EUR", "Europe/Paris"),
            new Pays("BE", Map.of("fr", "Belgique", "en", "Belgium", "es", "Bélgica"), "EUR", "Europe/Brussels"),
            new Pays("ES", Map.of("fr", "Espagne", "en", "Spain", "es", "España"), "EUR", "Europe/Madrid"),
            new Pays("US", Map.of("fr", "États-Unis", "en", "United States", "es", "Estados Unidos"), "USD", "America/New_York"));

    public static Optional<Pays> de(String code) {
        return TOUS.stream().filter(p -> p.code().equalsIgnoreCase(code == null ? "" : code.strip())).findFirst();
    }

    public String nom(String langue) { return nom.getOrDefault(langue, nom.get("fr")); }
}
