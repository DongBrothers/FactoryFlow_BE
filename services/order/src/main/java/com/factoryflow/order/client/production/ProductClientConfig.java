package com.factoryflow.order.client.production;

import com.factoryflow.common.web.TraceIdClientInterceptor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class ProductClientConfig {

    @Bean
    ProductClient productClient(
            @Value("${clients.production.url}") String baseUrl,
            @Value("${clients.production.timeout:3s}") Duration timeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return create(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build());
    }

    /** RestClient 위에 HTTP Interface 구현을 만든다. 지금 traceId 를 X-Trace-Id 로 싣는다. 테스트도 이 메서드로 만든다. */
    public static ProductClient create(RestClient restClient) {
        RestClient traced =
                restClient.mutate().requestInterceptor(new TraceIdClientInterceptor()).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(traced))
                .build()
                .createClient(ProductClient.class);
    }
}
