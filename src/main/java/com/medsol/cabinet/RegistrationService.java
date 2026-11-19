package com.medsol.cabinet;

import com.medsol.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class RegistrationService {

    /**
     * Version des documents juridiques acceptés ensemble (conditions générales /conditions et accord de traitement des
     * données /sous-traitance) : à changer à chaque modification d'un des deux textes ; les médecins des cabinets
     * existants les acceptent alors à leur connexion suivante.
     */
    public static final String CGU_VERSION = "2026-11-17";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public RegistrationService(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tx = new TransactionTemplate(tm);
    }

    public boolean emailTaken(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT email_existe(?)", Boolean.class, email));
    }

    /**
     * Crée un cabinet et son médecin titulaire en une transaction.
     * L'id du cabinet est choisi ici et placé dans le contexte pour satisfaire la politique RLS.
     */
    public UUID register(RegistrationForm f, String language) {
        UUID cabinetId = UUID.randomUUID();
        String hash = encoder.encode(f.getPassword());
        TenantContext.set(cabinetId);
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO cabinet (id, nom, pays, fuseau, langue, devise, specialite, essai_fin)
                        VALUES (?, ?, upper(?), ?, ?, upper(?), ?, current_date + 30)""",
                        cabinetId, f.getCabinetName().trim(), f.getCountry(), f.getTimezone(), language,
                        f.getCurrency(), f.getSpecialty());
                UUID medecin = jdbc.queryForObject("""
                        INSERT INTO utilisateur (cabinet_id, email, mot_de_passe, nom_complet, role, langue)
                        VALUES (?, ?, ?, ?, 'MEDECIN', ?) RETURNING id""", UUID.class,
                        cabinetId, f.getEmail().trim(), hash, f.getDoctorName().trim(), com.medsol.web.Langues.ou_francais(language));
                if (f.isCgu()) accepter(cabinetId, medecin);
                if (f.getSource() != null && RegistrationForm.SOURCES.contains(f.getSource())) {
                    String detail = f.getSourceDetail() == null || f.getSourceDetail().isBlank() ? null : f.getSourceDetail().strip();
                    jdbc.update("UPDATE cabinet SET source = ?, source_detail = ? WHERE id = ?", f.getSource(), detail, cabinetId);
                }
            });
        } finally {
            TenantContext.clear();
        }
        return cabinetId;
    }

    /** Conditions générales et accord de traitement des données acceptés par le médecin (version courante). */
    public void accepter(UUID cabinetId, UUID medecin) {
        jdbc.update("""
                UPDATE cabinet SET cgu_version = ?, cgu_acceptees_le = now(), dpa_version = ?, dpa_acceptee_le = now(),
                    dpa_accepte_par = ? WHERE id = ?""", CGU_VERSION, CGU_VERSION, medecin, cabinetId);
    }

    /** Le cabinet courant a-t-il accepté la version courante des documents ? */
    public boolean documentsAcceptes() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT coalesce(interne OR (cgu_version = ? AND dpa_version = ?), false) FROM cabinet", Boolean.class, CGU_VERSION, CGU_VERSION));
    }
}
