/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.atomicfu;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/atomicfu/atomicfu-compiler/testData/box")
@TestDataPath("$PROJECT_ROOT")
public class AtomicfuJvmIrTestGenerated extends AbstractAtomicfuJvmIrTest {
    @Test
    public void testAllFilesPresentInBox() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/atomicfu/atomicfu-compiler/testData/box"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("ArithmeticTest.kt")
    public void testArithmeticTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ArithmeticTest.kt");
    }

    @Test
    @TestMetadata("ArrayInlineExtensionTest.kt")
    public void testArrayInlineExtensionTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ArrayInlineExtensionTest.kt");
    }

    @Test
    @TestMetadata("ArrayLoopTest.kt")
    public void testArrayLoopTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ArrayLoopTest.kt");
    }

    @Test
    @TestMetadata("AtomicArrayTest.kt")
    public void testAtomicArrayTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/AtomicArrayTest.kt");
    }

    @Test
    @TestMetadata("ComplexLoopTest.kt")
    public void testComplexLoopTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ComplexLoopTest.kt");
    }

    @Test
    @TestMetadata("DelegatedPropertiesTest.kt")
    public void testDelegatedPropertiesTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/DelegatedPropertiesTest.kt");
    }

    @Test
    @TestMetadata("ExtensionLoopTest.kt")
    public void testExtensionLoopTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ExtensionLoopTest.kt");
    }

    @Test
    @TestMetadata("ExtensionsTest.kt")
    public void testExtensionsTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ExtensionsTest.kt");
    }

    @Test
    @TestMetadata("IndexArrayElementGetterTest.kt")
    public void testIndexArrayElementGetterTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/IndexArrayElementGetterTest.kt");
    }

    @Test
    @TestMetadata("InlineExtensionWithTypeParameterTest.kt")
    public void testInlineExtensionWithTypeParameterTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/InlineExtensionWithTypeParameterTest.kt");
    }

    @Test
    @TestMetadata("LambdaTest.kt")
    public void testLambdaTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LambdaTest.kt");
    }

    @Test
    @TestMetadata("LateinitPropertiesTest.kt")
    public void testLateinitPropertiesTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LateinitPropertiesTest.kt");
    }

    @Test
    @TestMetadata("LockFreeIntBitsTest.kt")
    public void testLockFreeIntBitsTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LockFreeIntBitsTest.kt");
    }

    @Test
    @TestMetadata("LockFreeLongCounterTest.kt")
    public void testLockFreeLongCounterTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LockFreeLongCounterTest.kt");
    }

    @Test
    @TestMetadata("LockFreeQueueTest.kt")
    public void testLockFreeQueueTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LockFreeQueueTest.kt");
    }

    @Test
    @TestMetadata("LockFreeStackTest.kt")
    public void testLockFreeStackTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LockFreeStackTest.kt");
    }

    @Test
    @TestMetadata("LockTest.kt")
    public void testLockTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LockTest.kt");
    }

    @Test
    @TestMetadata("LoopTest.kt")
    public void testLoopTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/LoopTest.kt");
    }

    @Test
    @TestMetadata("MultiInitTest.kt")
    public void testMultiInitTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/MultiInitTest.kt");
    }

    @Test
    @TestMetadata("ParameterizedInlineFunExtensionTest.kt")
    public void testParameterizedInlineFunExtensionTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ParameterizedInlineFunExtensionTest.kt");
    }

    @Test
    @TestMetadata("ReentrantLockTest.kt")
    public void testReentrantLockTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ReentrantLockTest.kt");
    }

    @Test
    @TestMetadata("ScopeTest.kt")
    public void testScopeTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/ScopeTest.kt");
    }

    @Test
    @TestMetadata("SimpleLockTest.kt")
    public void testSimpleLockTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/SimpleLockTest.kt");
    }

    @Test
    @TestMetadata("SynchronizedObjectTest.kt")
    public void testSynchronizedObjectTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/SynchronizedObjectTest.kt");
    }

    @Test
    @TestMetadata("TopLevelTest.kt")
    public void testTopLevelTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/TopLevelTest.kt");
    }

    @Test
    @TestMetadata("TraceTest.kt")
    public void testTraceTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/TraceTest.kt");
    }

    @Test
    @TestMetadata("UncheckedCastTest.kt")
    public void testUncheckedCastTest() throws Exception {
        runTest("plugins/atomicfu/atomicfu-compiler/testData/box/UncheckedCastTest.kt");
    }
}
