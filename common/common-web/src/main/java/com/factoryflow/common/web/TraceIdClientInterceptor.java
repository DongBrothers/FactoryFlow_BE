package com.factoryflow.common.web;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 다른 서비스를 동기로 부를 때(client/) 지금 traceId 를 X-Trace-Id 로 싣는다. 받는 서비스의 {@link TraceIdFilter} 가 그 값을 이어
 * 써서 추적이 끊기지 않는다. 요청·이벤트 밖이라 traceId 가 없으면 싣지 않는다 (받는 쪽이 새로 만든다).
 */
public class TraceIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            request.getHeaders().set(TraceIdFilter.HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
