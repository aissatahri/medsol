package com.medsol.caisse;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Montants dans les pages : ${@monnaie.format(montant, devise)} (Thymeleaf interdit l'accès statique). */
@Component("monnaie")
public class MonnaieFormat {

    public String format(BigDecimal montant, String devise) { return Monnaie.format(montant, devise); }

    public String symbole(String devise) { return Monnaie.symbole(devise); }

    public String saisie(BigDecimal montant) { return Monnaie.arrondi(montant).toPlainString(); }

    public String mode(String mode) { return CaisseRepository.modeLibelle(mode); }

    public boolean positif(BigDecimal montant) { return montant != null && montant.compareTo(new BigDecimal("0.005")) > 0; }
}
