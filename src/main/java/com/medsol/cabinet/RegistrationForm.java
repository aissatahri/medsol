package com.medsol.cabinet;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegistrationForm {

    @NotBlank private String cabinetName;
    @NotBlank private String doctorName;
    @NotBlank @Email private String email;
    @NotBlank @Size(min = 10, max = 100) private String password;
    @NotBlank @Size(min = 2, max = 2) private String country = "MA";
    @NotBlank private String specialty;
    // Déduits du pays à l'inscription (voir Pays) ; Maroc par défaut
    @NotBlank @Size(min = 3, max = 3) private String currency = "MAD";
    @NotBlank private String timezone = "Africa/Casablanca";
    /** Conditions générales acceptées (case à cocher de l'inscription). */
    private boolean cgu;

    /** « Comment avez-vous connu MedSol ? » (codes de RegistrationForm.SOURCES) et précision libre. */
    private String source;
    @Size(max = 150) private String sourceDetail;

    public static final java.util.List<String> SOURCES =
            java.util.List.of("CONFRERE", "VISITE", "DELEGUE", "WHATSAPP", "FACEBOOK", "LINKEDIN", "GOOGLE", "EVENEMENT", "AUTRE");

    /** Code de parrainage (lien « /register?parrain=CODE » envoyé par un confrère). */
    @Size(max = 20) private String parrain;

    public String getParrain() { return parrain; }
    public void setParrain(String v) { this.parrain = v; }

    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getSourceDetail() { return sourceDetail; }
    public void setSourceDetail(String v) { this.sourceDetail = v; }

    public boolean isCgu() { return cgu; }
    public void setCgu(boolean v) { this.cgu = v; }

    public String getCabinetName() { return cabinetName; }
    public void setCabinetName(String v) { this.cabinetName = v; }
    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String v) { this.doctorName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getSpecialty() { return specialty; }
    public void setSpecialty(String v) { this.specialty = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
}
