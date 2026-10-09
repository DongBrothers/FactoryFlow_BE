package com.factoryflow.common.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("factoryflow.events")
public record EventProperties(
        @DefaultValue("factoryflow.events") String exchange,
        @DefaultValue("100") int relayBatchSize
) {
}
