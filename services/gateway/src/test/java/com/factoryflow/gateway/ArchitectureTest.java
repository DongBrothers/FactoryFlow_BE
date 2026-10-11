package com.factoryflow.gateway;

import com.factoryflow.test.arch.FactoryFlowRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.factoryflow.gateway",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest ArchRule isolation = FactoryFlowRules.noOtherServiceAccess("gateway");
    @ArchTest ArchRule injection = FactoryFlowRules.NO_FIELD_INJECTION;
    @ArchTest ArchRule methodInjection = FactoryFlowRules.NO_METHOD_INJECTION;
    @ArchTest ArchRule lombokConstructor = FactoryFlowRules.CONSTRUCTOR_BY_LOMBOK;
    @ArchTest ArchRule streams = FactoryFlowRules.NO_STANDARD_STREAMS;
    @ArchTest ArchRule logging = FactoryFlowRules.SLF4J_ONLY;
    @ArchTest ArchRule config = FactoryFlowRules.CONFIG_PACKAGE;
    @ArchTest ArchRule records = FactoryFlowRules.NO_RECORD_INSIDE_RECORD;
}
