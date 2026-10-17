package org.semiproject.pillbadeumserver.common.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = request.getHeader("X-Request-Id");
            if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        response.setHeader("X-Request-Id", id);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally { if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous); }
    }
}
