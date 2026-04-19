package com.sst.flaggame.common.logging;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 모든 요청에 requestId를 부여하고 MDC에 심는 필터.
 * -들어오는 X-Request-Id 헤더가 있으면 그 값을 사용. 없으면 8자리 uuid를 생성
 *
 * @Order(HIGHEST_PRECEDENCE + 10) 를 하여, 최대한 앞에서 실행하도록 하여, security filter보다 앞에서 실행.
 * 인증 실패시에도 requestId가 붙음
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LogContextFilter extends OncePerRequestFilter {
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String MDC_REQUEST_ID    = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = Optional.ofNullable(request.getHeader(HEADER_REQUEST_ID))
                .filter(s -> !s.isBlank())
                .orElse("req-" + UUID.randomUUID().toString().substring(0, 8));

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            //다음 request에 requestId가 오염 되지 않기 위해
            MDC.clear();
        }
    }
}
