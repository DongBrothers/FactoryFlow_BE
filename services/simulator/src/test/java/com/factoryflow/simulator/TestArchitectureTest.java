package com.factoryflow.simulator;

import com.factoryflow.test.arch.FactoryFlowRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 테스트 코드 규칙. */
@AnalyzeClasses(
        packages = "com.factoryflow.simulator",
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchitectureTest {
    @ArchTest ArchRule noDisabled = FactoryFlowRules.NO_DISABLED_TESTS;
    @ArchTest ArchRule mocks = FactoryFlowRules.MOCK_ONLY_CLIENT_AND_CLOCK;
}
