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
    @ArchTest ArchRule streams = FactoryFlowRules.NO_STANDARD_STREAMS;
}
