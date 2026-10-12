package com.factoryflow.test.arch;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 테스트 코드 규칙 묶음. 각 서비스 TestArchitectureTest 가 ArchTests.in(TestCodeRules.class) 로 등록한다. */
public final class TestCodeRules {

    @ArchTest static final ArchRule noSkipped = FactoryFlowRules.NO_SKIPPED_TESTS;
    @ArchTest static final ArchRule mocks = FactoryFlowRules.MOCK_ONLY_CLIENT_AND_CLOCK;
    @ArchTest static final ArchRule inlineMocks = FactoryFlowRules.NO_INLINE_MOCK_OF_OUR_CODE;
    @ArchTest static final ArchRule testcontainers = FactoryFlowRules.TESTS_USE_TESTCONTAINERS;

    @ArchTest
    static final ArchRule transactionalOnly = FactoryFlowRules.TRANSACTIONAL_ANNOTATION_ONLY;

    private TestCodeRules() {}
}
