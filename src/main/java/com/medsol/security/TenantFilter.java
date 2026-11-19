package com.medsol.security;

import com.medsol.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Place le cabinet de l'utilisateur connecté dans TenantContext pour toute la durée de la requête. */
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth != null && auth.getPrincipal() instanceof MedsolUser u) {
                TenantContext.set(u.cabinetId());
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
