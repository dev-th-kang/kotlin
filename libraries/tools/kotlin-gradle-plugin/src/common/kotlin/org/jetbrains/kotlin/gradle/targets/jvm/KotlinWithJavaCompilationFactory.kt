/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("PackageDirectoryMismatch") // Old package for compatibility
package org.jetbrains.kotlin.gradle.plugin.mpp

import org.jetbrains.kotlin.gradle.dsl.KotlinCommonOptions

class KotlinWithJavaCompilationFactory<KotlinOptionsType : KotlinCommonOptions>(
    override val target: KotlinWithJavaTarget<KotlinOptionsType>,
    val kotlinOptionsFactory: () -> KotlinOptionsType
) : KotlinCompilationFactory<KotlinWithJavaCompilation<KotlinOptionsType>> {

    override val itemClass: Class<KotlinWithJavaCompilation<KotlinOptionsType>>
        @Suppress("UNCHECKED_CAST")
        get() = KotlinWithJavaCompilation::class.java as Class<KotlinWithJavaCompilation<KotlinOptionsType>>

    @Suppress("UNCHECKED_CAST")
    override fun create(name: String): KotlinWithJavaCompilation<KotlinOptionsType> =
        project.objects.newInstance(
            KotlinWithJavaCompilation::class.java,
            target,
            name,
            getOrCreateDefaultSourceSet(name),
            kotlinOptionsFactory()
        ) as KotlinWithJavaCompilation<KotlinOptionsType>
}