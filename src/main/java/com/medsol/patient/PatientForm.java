package com.medsol.patient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class PatientForm {

    @NotBlank @Size(max = 100) private String nom;
    @NotBlank @Size(max = 100) private String prenom;
    @Past @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate dateNaissance;
    @Pattern(regexp = "[MFX]?") private String sexe;
    @Size(max = 30) @Pattern(regexp = "[0-9+()\\-. ]*") private String telephone;
    @Email @Size(max = 200) private String email;
    @Size(max = 50) private String identifiantNational;
    @Size(max = 300) private String adresse;
    @Pattern(regexp = "(CELIBATAIRE|MARIE|DIVORCE|VEUF)?") private String situationFamiliale;
    @Size(max = 100) private String profession;
    @Size(max = 100) private String couverture;
    @Size(max = 2000) private String traitementAvant;
    private boolean consentement;
    private boolean ignorerDoublon;

    public static PatientForm from(Patient p) {
        PatientForm f = new PatientForm();
        f.nom = p.nom(); f.prenom = p.prenom(); f.dateNaissance = p.dateNaissance(); f.sexe = p.sexe();
        f.telephone = p.telephone(); f.email = p.email(); f.identifiantNational = p.identifiantNational();
        f.adresse = p.adresse(); f.situationFamiliale = p.situationFamiliale(); f.profession = p.profession();
        f.couverture = p.couverture(); f.traitementAvant = p.traitementAvant();
        return f;
    }

    public String getSituationFamiliale() { return situationFamiliale; }
    public void setSituationFamiliale(String v) { this.situationFamiliale = v; }
    public String getProfession() { return profession; }
    public void setProfession(String v) { this.profession = v; }
    public String getCouverture() { return couverture; }
    public void setCouverture(String v) { this.couverture = v; }
    public String getTraitementAvant() { return traitementAvant; }
    public void setTraitementAvant(String v) { this.traitementAvant = v; }

    public String getNom() { return nom; }
    public void setNom(String v) { this.nom = v; }
    public String getPrenom() { return prenom; }
    public void setPrenom(String v) { this.prenom = v; }
    public LocalDate getDateNaissance() { return dateNaissance; }
    public void setDateNaissance(LocalDate v) { this.dateNaissance = v; }
    public String getSexe() { return sexe; }
    public void setSexe(String v) { this.sexe = v; }
    public String getTelephone() { return telephone; }
    public void setTelephone(String v) { this.telephone = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v == null || v.isBlank() ? null : v; }
    public String getIdentifiantNational() { return identifiantNational; }
    public void setIdentifiantNational(String v) { this.identifiantNational = v; }
    public String getAdresse() { return adresse; }
    public void setAdresse(String v) { this.adresse = v; }
    public boolean isConsentement() { return consentement; }
    public void setConsentement(boolean v) { this.consentement = v; }
    public boolean isIgnorerDoublon() { return ignorerDoublon; }
    public void setIgnorerDoublon(boolean v) { this.ignorerDoublon = v; }
}
