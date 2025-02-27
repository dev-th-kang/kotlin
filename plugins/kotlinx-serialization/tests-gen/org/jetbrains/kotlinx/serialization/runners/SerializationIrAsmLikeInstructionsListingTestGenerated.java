/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlinx.serialization.TestGeneratorKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/kotlinx-serialization/testData/codegen")
@TestDataPath("$PROJECT_ROOT")
public class SerializationIrAsmLikeInstructionsListingTestGenerated extends AbstractSerializationIrAsmLikeInstructionsListingTest {
    @Test
    public void testAllFilesPresentInCodegen() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/kotlinx-serialization/testData/codegen"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("Basic.kt")
    public void testBasic() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/codegen/Basic.kt");
    }

    @Test
    @TestMetadata("Delegated.kt")
    public void testDelegated() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/codegen/Delegated.kt");
    }

    @Test
    @TestMetadata("Sealed.kt")
    public void testSealed() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/codegen/Sealed.kt");
    }
}
