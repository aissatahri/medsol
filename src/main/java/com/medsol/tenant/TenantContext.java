package com.medsol.tenant;

import java.util.UUID;

/** Cabinet courant du thread. Lu par TenantDataSource à chaque emprunt de connexion. */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID cabinetId) { CURRENT.set(cabinetId); }

    public static UUID get() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }
}
