package com.factoryflow.simulator;

import com.factoryflow.test.arch.FactoryFlowRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.factoryflow.simulator",
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchitectureTest {
    @ArchTest ArchRule noSkipped = FactoryFlowRules.NO_SKIPPED_TESTS;
    @ArchTest ArchRule mocks = FactoryFlowRules.MOCK_ONLY_CLIENT_AND_CLOCK;
    @ArchTest ArchRule inlineMocks = FactoryFlowRules.NO_INLINE_MOCK_OF_OUR_CODE;
}
