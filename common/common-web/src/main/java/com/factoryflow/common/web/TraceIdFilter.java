package com.factoryflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 헤더(X-Trace-Id)의 traceId 를 MDC 에 넣고 응답 헤더로 돌려준다. 없으면 새로 만든다. common-event 의 EventPublisher 가 같은
 * MDC 키를 읽는다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
