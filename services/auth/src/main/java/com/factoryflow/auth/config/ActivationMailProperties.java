package com.factoryflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 활성화 메일 설정. linkBaseUrl 은 프론트의 활성화 페이지 주소(메일 링크는 여기에 ?token= 을 붙인다), from 은 보내는 사람 주소다.
 * (docs/specs/auth-account.md "활성화 메일")
 */
@ConfigurationProperties(prefix = "auth.activation-mail")
public record ActivationMailProperties(String linkBaseUrl, String from) {}
