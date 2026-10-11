package com.factoryflow.inventory;

import com.factoryflow.test.arch.FactoryFlowRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.factoryflow.inventory",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest ArchRule isolation = FactoryFlowRules.noOtherServiceAccess("inventory");
    @ArchTest ArchRule entry = FactoryFlowRules.ENTRY_THROUGH_API;
    @ArchTest ArchRule publish = FactoryFlowRules.NO_DIRECT_PUBLISH;
    @ArchTest ArchRule injection = FactoryFlowRules.NO_FIELD_INJECTION;
    @ArchTest ArchRule methodInjection = FactoryFlowRules.NO_METHOD_INJECTION;
    @ArchTest ArchRule lombokConstructor = FactoryFlowRules.CONSTRUCTOR_BY_LOMBOK;
    @ArchTest ArchRule streams = FactoryFlowRules.NO_STANDARD_STREAMS;
    @ArchTest ArchRule logging = FactoryFlowRules.SLF4J_ONLY;

    @ArchTest ArchRule packages = FactoryFlowRules.LAYER_PACKAGES;
    @ArchTest ArchRule config = FactoryFlowRules.CONFIG_PACKAGE;
    @ArchTest ArchRule controllerNaming = FactoryFlowRules.CONTROLLER_NAMING;
    @ArchTest ArchRule errorCode = FactoryFlowRules.errorCodeNamed("inventory");
    @ArchTest ArchRule listenerNaming = FactoryFlowRules.EVENT_LISTENER_NAMING;

    @ArchTest ArchRule noValidated = FactoryFlowRules.NO_VALIDATED_ON_CONTROLLER;
    @ArchTest ArchRule validBody = FactoryFlowRules.VALID_REQUEST_BODY;

    @ArchTest ArchRule dtoRecords = FactoryFlowRules.DTOS_ARE_RECORDS;
    @ArchTest ArchRule responseNaming = FactoryFlowRules.RESPONSE_NAMING;
    @ArchTest ArchRule records = FactoryFlowRules.NO_RECORD_INSIDE_RECORD;
    @ArchTest ArchRule responseFactory = FactoryFlowRules.RESPONSE_STATIC_FACTORY;
    @ArchTest ArchRule responseNew = FactoryFlowRules.NO_RESPONSE_CONSTRUCTOR_OUTSIDE;

    @ArchTest ArchRule httpInterface = FactoryFlowRules.CLIENT_IS_HTTP_INTERFACE;
    @ArchTest ArchRule exchangeCalls = FactoryFlowRules.CLIENT_EXCHANGE_ONLY_FROM_CLIENT;
    @ArchTest ArchRule noRestTemplate = FactoryFlowRules.NO_REST_TEMPLATE_OR_FEIGN;
    @ArchTest ArchRule events = FactoryFlowRules.EVENTS_ARE_PUBLISHER_RECORDS;

    @ArchTest ArchRule apiDocs = FactoryFlowRules.API_DOCUMENTED;
    @ArchTest ArchRule dtoDocs = FactoryFlowRules.DTO_FIELDS_DOCUMENTED;
}
