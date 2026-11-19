package com.medsol.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * À chaque emprunt de connexion : bascule sur le rôle medsol_app (soumis à la Row-Level Security,
 * même si l'utilisateur de connexion est propriétaire) et fixe app.cabinet_id.
 * Sans cabinet courant, aucune ligne métier n'est visible.
 */
public class TenantDataSource extends DelegatingDataSource {

    public TenantDataSource(DataSource target) { super(target); }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection c) throws SQLException {
        try {
            try (Statement st = c.createStatement()) {
                st.execute("SET ROLE medsol_app");
            }
            UUID cabinet = TenantContext.get();
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.cabinet_id', ?, false)")) {
                ps.setString(1, cabinet == null ? "" : cabinet.toString());
                ps.execute();
            }
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }
}
