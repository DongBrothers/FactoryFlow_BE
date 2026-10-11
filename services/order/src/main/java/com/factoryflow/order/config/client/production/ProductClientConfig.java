package com.factoryflow.order.config.client.production;

import com.factoryflow.order.client.production.ProductClient;
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

    /** RestClient 위에 HTTP Interface 구현을 만든다. 테스트도 이 메서드로 만든다. */
    public static ProductClient create(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductClient.class);
    }
}
