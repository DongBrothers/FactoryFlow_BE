package com.factoryflow.mes;

import com.factoryflow.test.arch.TestCodeRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(
        packages = "com.factoryflow.mes",
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchitectureTest {
    @ArchTest static final ArchTests rules = ArchTests.in(TestCodeRules.class);
}
