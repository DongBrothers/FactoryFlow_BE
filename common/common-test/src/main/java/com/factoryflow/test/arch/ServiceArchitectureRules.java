package com.factoryflow.test.arch;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 서비스 본 코드 규칙 묶음. 각 서비스 ArchitectureTest 가 ArchTests.in(ServiceArchitectureRules.class) 로 등록한다. */
public final class ServiceArchitectureRules {

    @ArchTest static final ArchRule isolation = FactoryFlowRules.NO_OTHER_SERVICE_ACCESS;
    @ArchTest static final ArchRule entry = FactoryFlowRules.ENTRY_THROUGH_API;
    @ArchTest static final ArchRule publish = FactoryFlowRules.NO_DIRECT_PUBLISH;
    @ArchTest static final ArchRule injection = FactoryFlowRules.NO_FIELD_INJECTION;
    @ArchTest static final ArchRule methodInjection = FactoryFlowRules.NO_METHOD_INJECTION;
    @ArchTest static final ArchRule lombokConstructor = FactoryFlowRules.CONSTRUCTOR_BY_LOMBOK;
    @ArchTest static final ArchRule streams = FactoryFlowRules.NO_STANDARD_STREAMS;
    @ArchTest static final ArchRule logging = FactoryFlowRules.SLF4J_ONLY;

    @ArchTest static final ArchRule packages = FactoryFlowRules.LAYER_PACKAGES;
    @ArchTest static final ArchRule config = FactoryFlowRules.CONFIG_PACKAGE;
    @ArchTest static final ArchRule controllerNaming = FactoryFlowRules.CONTROLLER_NAMING;
    @ArchTest static final ArchRule errorCode = FactoryFlowRules.ERROR_CODE_NAMING;
    @ArchTest static final ArchRule listenerNaming = FactoryFlowRules.EVENT_LISTENER_NAMING;

    @ArchTest static final ArchRule noValidated = FactoryFlowRules.NO_VALIDATED_ON_CONTROLLER;
    @ArchTest static final ArchRule validBody = FactoryFlowRules.VALID_REQUEST_BODY;

    @ArchTest static final ArchRule dtoRecords = FactoryFlowRules.DTOS_ARE_RECORDS;
    @ArchTest static final ArchRule responseNaming = FactoryFlowRules.RESPONSE_NAMING;
    @ArchTest static final ArchRule records = FactoryFlowRules.NO_RECORD_INSIDE_RECORD;
    @ArchTest static final ArchRule responseFactory = FactoryFlowRules.RESPONSE_STATIC_FACTORY;
    @ArchTest static final ArchRule responseNew = FactoryFlowRules.NO_RESPONSE_CONSTRUCTOR_OUTSIDE;

    @ArchTest static final ArchRule httpInterface = FactoryFlowRules.CLIENT_IS_HTTP_INTERFACE;

    @ArchTest
    static final ArchRule exchangeCalls = FactoryFlowRules.CLIENT_EXCHANGE_ONLY_FROM_CLIENT;

    @ArchTest static final ArchRule noRestTemplate = FactoryFlowRules.NO_REST_TEMPLATE_OR_FEIGN;

    @ArchTest static final ArchRule queries = FactoryFlowRules.QUERYDSL_FOR_CUSTOM_QUERIES;
    @ArchTest static final ArchRule noJdbc = FactoryFlowRules.NO_JDBC;

    @ArchTest
    static final ArchRule controllerResult = FactoryFlowRules.CONTROLLER_STORES_SERVICE_RESULT;

    @ArchTest static final ArchRule events = FactoryFlowRules.EVENTS_ARE_PUBLISHER_RECORDS;

    @ArchTest static final ArchRule apiDocs = FactoryFlowRules.API_DOCUMENTED;
    @ArchTest static final ArchRule dtoDocs = FactoryFlowRules.DTO_FIELDS_DOCUMENTED;

    private ServiceArchitectureRules() {}
}
