package com.medsol.web;

import java.util.List;

/** Langues de l'interface (fichiers messages*.properties). Une langue inconnue retombe sur le français. */
public final class Langues {

    public static final List<String> SUPPORTEES = List.of("fr", "en", "es");

    private Langues() {}

    public static boolean supportee(String code) { return code != null && SUPPORTEES.contains(code); }

    public static String ou_francais(String code) { return supportee(code) ? code : "fr"; }
}
