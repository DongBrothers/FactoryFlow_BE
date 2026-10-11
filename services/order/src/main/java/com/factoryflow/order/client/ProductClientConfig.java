package com.factoryflow.order.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ProductClientConfig {

    @Bean
    RestClient productRestClient(
            @Value("${clients.production.url}") String baseUrl,
            @Value("${clients.production.timeout:3s}") Duration timeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
