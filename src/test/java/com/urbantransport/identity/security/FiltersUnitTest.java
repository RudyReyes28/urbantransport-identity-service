package com.urbantransport.identity.security;

import com.urbantransport.identity.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class FiltersUnitTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void internalApiKeyFilterShouldAuthenticateOnlyValidKeyOnInternalPath() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("secret-key-123");

        // 1. Ruta no interna -> no filtra
        MockHttpServletRequest publicReq = new MockHttpServletRequest("GET", "/auth/login");
        assertTrue(filter.shouldNotFilter(publicReq));

        // 2. Ruta interna con llave correcta -> autentica ROLE_INTERNAL_SERVICE
        MockHttpServletRequest internalReq = new MockHttpServletRequest("POST", "/internal/users/customer");
        internalReq.addHeader(InternalApiKeyFilter.HEADER, "secret-key-123");
        assertFalse(filter.shouldNotFilter(internalReq));

        filter.doFilter(internalReq, new MockHttpServletResponse(), new MockFilterChain());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTERNAL_SERVICE".equals(a.getAuthority())));

        // 3. Ruta interna con llave incorrecta o vacía -> no autentica
        SecurityContextHolder.clearContext();
        MockHttpServletRequest wrongKeyReq = new MockHttpServletRequest("POST", "/internal/users/customer");
        wrongKeyReq.addHeader(InternalApiKeyFilter.HEADER, "wrong-key");
        filter.doFilter(wrongKeyReq, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        // 4. Filtro configurado con apiKey en blanco -> rechaza todo
        InternalApiKeyFilter blankKeyFilter = new InternalApiKeyFilter("   ");
        blankKeyFilter.doFilter(internalReq, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void correlationIdFilterShouldPropagateOrGenerateHeaderAndCleanMdc() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();

        // 1. Sin header: genera un UUID nuevo
        MockHttpServletRequest reqWithoutHeader = new MockHttpServletRequest("GET", "/users");
        MockHttpServletResponse resWithoutHeader = new MockHttpServletResponse();
        FilterChain chain1 = (req, res) -> assertNotNull(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(reqWithoutHeader, resWithoutHeader, chain1);
        assertNotNull(resWithoutHeader.getHeader(CorrelationIdFilter.HEADER));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY)); // Limpiado en el finally

        // 2. Con header existente: reutiliza el mismo
        MockHttpServletRequest reqWithHeader = new MockHttpServletRequest("GET", "/users");
        reqWithHeader.addHeader(CorrelationIdFilter.HEADER, "corr-abc-123");
        MockHttpServletResponse resWithHeader = new MockHttpServletResponse();
        FilterChain chain2 = (req, res) -> assertEquals("corr-abc-123", MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(reqWithHeader, resWithHeader, chain2);
        assertEquals("corr-abc-123", resWithHeader.getHeader(CorrelationIdFilter.HEADER));
    }
}