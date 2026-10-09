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
    @ArchTest ArchRule streams = FactoryFlowRules.NO_STANDARD_STREAMS;
}
