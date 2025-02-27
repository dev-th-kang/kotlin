/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.evaluate

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.analysis.api.base.KtConstantValue
import org.jetbrains.kotlin.analysis.api.base.KtConstantValueFactory
import org.jetbrains.kotlin.analysis.api.components.KtConstantEvaluationMode
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isFinal
import org.jetbrains.kotlin.fir.declarations.utils.referredVariableSymbol
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.builder.buildConstExpression
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolvedSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.constants.evaluate.CompileTimeType
import org.jetbrains.kotlin.resolve.constants.evaluate.evalBinaryOp
import org.jetbrains.kotlin.resolve.constants.evaluate.evalUnaryOp
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * An evaluator that transform numeric operation, such as div, into compile-time constant iff involved operands, such as explicit receiver
 * and the argument, are compile-time constant as well.
 */
internal object FirCompileTimeConstantEvaluator {
    // TODO: Handle boolean operators, class reference, array, annotation values, etc.
    fun evaluate(
        fir: FirElement?,
        mode: KtConstantEvaluationMode,
    ): FirConstExpression<*>? =
        when (fir) {
            is FirPropertyAccessExpression -> {
                when (val referredVariable = fir.referredVariableSymbol) {
                    is FirPropertySymbol -> {
                        if (referredVariable.callableId.isStringLength) {
                            evaluate(fir.explicitReceiver, mode)?.evaluateStringLength()
                        } else {
                            referredVariable.toConstExpression(mode)
                        }
                    }
                    is FirFieldSymbol -> referredVariable.toConstExpression(mode)
                    else -> null
                }
            }
            is FirConstExpression<*> -> {
                fir.adaptToConstKind()
            }
            is FirFunctionCall -> {
                evaluateFunctionCall(fir, mode)
            }
            is FirNamedReference -> {
                when (val resolvedSymbol = fir.resolvedSymbol) {
                    is FirPropertySymbol -> resolvedSymbol.toConstExpression(mode)
                    else -> null
                }
            }
            else -> null
        }

    private val CallableId.isStringLength: Boolean
        get() = classId == StandardClassIds.String && callableName.identifierOrNullIfSpecial == "length"

    private fun FirPropertySymbol.toConstExpression(
        mode: KtConstantEvaluationMode,
    ): FirConstExpression<*>? {
        return when {
            mode == KtConstantEvaluationMode.CONSTANT_EXPRESSION_EVALUATION && !isConst -> null
            isVal && hasInitializer -> {
                // NB: the initializer could be [FirLazyExpression] in [BodyBuildingMode.LAZY_BODIES].
                this.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE) // to unwrap lazy body
                evaluate(fir.initializer, mode)
            }
            else -> null
        }
    }

    private fun FirFieldSymbol.toConstExpression(
        mode: KtConstantEvaluationMode,
    ): FirConstExpression<*>? {
        return when {
            mode == KtConstantEvaluationMode.CONSTANT_EXPRESSION_EVALUATION && !isFinal -> null
            isVal && hasInitializer -> {
                evaluate(fir.initializer, mode)
            }
            else -> null
        }
    }

    fun evaluateAsKtConstantValue(
        fir: FirElement,
        mode: KtConstantEvaluationMode,
    ): KtConstantValue? {
        val evaluated = evaluate(fir, mode) ?: return null

        val ktConstantValue = KtConstantValueFactory.createConstantValue(evaluated.value, evaluated.psi as? KtElement) ?: return null
        check(ktConstantValue.constantValueKind == evaluated.kind) {
            "Expected ${evaluated.kind} for created KtConstantValue but ${ktConstantValue.constantValueKind} found"
        }
        return ktConstantValue
    }

    private fun FirConstExpression<*>.adaptToConstKind(): FirConstExpression<*> {
        return kind.toConstExpression(
            source,
            kind.convertToNumber(value as? Number) ?: value
        )
    }

    private fun evaluateFunctionCall(
        functionCall: FirFunctionCall,
        mode: KtConstantEvaluationMode,
    ): FirConstExpression<*>? {
        val function = functionCall.getOriginalFunction() as? FirSimpleFunction ?: return null

        val opr1 = evaluate(functionCall.explicitReceiver, mode) ?: return null
        opr1.evaluate(function)?.let {
            return it.adjustType(functionCall.typeRef)
        }

        val opr2 = evaluate(functionCall.argument, mode) ?: return null
        opr1.evaluate(function, opr2)?.let {
            return it.adjustType(functionCall.typeRef)
        }
        return null
    }

    private fun FirConstExpression<*>.adjustType(expectedType: FirTypeRef): FirConstExpression<*> {
        val expectedKind = expectedType.toConstantValueKind()
        // Note that the resolved type for the const expression is not always matched with the const kind. For example,
        //   fun foo(x: Int) {
        //     when (x) {
        //       -2_147_483_628 -> ...
        //   } }
        // That constant is encoded as `unaryMinus` call with the const 2147483628 of long type, while the resolved type is Int.
        // After computing the compile time constant, we need to adjust its type here.
        val expression =
            if (expectedKind != null && expectedKind != kind && value is Number) {
                val typeAdjustedValue = expectedKind.convertToNumber(value as Number)!!
                expectedKind.toConstExpression(source, typeAdjustedValue)
            } else {
                this
            }
        // Lastly, we should preserve the resolved type of the original function call.
        return expression.apply {
            replaceTypeRef(expectedType)
        }
    }

    private fun <T> ConstantValueKind<T>.toCompileTimeType(): CompileTimeType {
        return when (this) {
            ConstantValueKind.Byte -> CompileTimeType.BYTE
            ConstantValueKind.Short -> CompileTimeType.SHORT
            ConstantValueKind.Int -> CompileTimeType.INT
            ConstantValueKind.Long -> CompileTimeType.LONG
            ConstantValueKind.Double -> CompileTimeType.DOUBLE
            ConstantValueKind.Float -> CompileTimeType.FLOAT
            ConstantValueKind.Char -> CompileTimeType.CHAR
            ConstantValueKind.Boolean -> CompileTimeType.BOOLEAN
            ConstantValueKind.String -> CompileTimeType.STRING

            else -> CompileTimeType.ANY
        }
    }

    // Unary operators
    private fun FirConstExpression<*>.evaluate(function: FirSimpleFunction): FirConstExpression<*>? {
        if (value == null) return null
        (value as? String)?.let { opr ->
            evalUnaryOp(
                function.name.asString(),
                kind.toCompileTimeType(),
                opr
            )?.let {
                return it.toConstantValueKind().toConstExpression(source, it)
            }
        }
        return kind.convertToNumber(value as? Number)?.let { opr ->
            evalUnaryOp(
                function.name.asString(),
                kind.toCompileTimeType(),
                opr
            )?.let {
                it.toConstantValueKind().toConstExpression(source, it)
            }
        }
    }

    private fun FirConstExpression<*>.evaluateStringLength(): FirConstExpression<*>? {
        return (value as? String)?.length?.let {
            it.toConstantValueKind().toConstExpression(source, it)
        }
    }

    // Binary operators
    private fun FirConstExpression<*>.evaluate(
        function: FirSimpleFunction,
        other: FirConstExpression<*>
    ): FirConstExpression<*>? {
        if (value == null || other.value == null) return null
        // NB: some utils accept very general types, and due to the way operation map works, we should up-cast rhs type.
        val rightType = when {
            function.symbol.callableId.isStringEquals -> CompileTimeType.ANY
            function.symbol.callableId.isStringPlus -> CompileTimeType.ANY
            else -> other.kind.toCompileTimeType()
        }
        (value as? String)?.let { opr1 ->
            other.value?.let { opr2 ->
                evalBinaryOp(
                    function.name.asString(),
                    kind.toCompileTimeType(),
                    opr1,
                    rightType,
                    opr2
                )?.let {
                    return it.toConstantValueKind().toConstExpression(source, it)
                }
            }
        }
        return kind.convertToNumber(value as? Number)?.let { opr1 ->
            other.kind.convertToNumber(other.value as? Number)?.let { opr2 ->
                evalBinaryOp(
                    function.name.asString(),
                    kind.toCompileTimeType(),
                    opr1,
                    other.kind.toCompileTimeType(),
                    opr2
                )?.let {
                    it.toConstantValueKind().toConstExpression(source, it)
                }
            }
        }
    }

    private val CallableId.isStringEquals: Boolean
        get() = classId == StandardClassIds.String && callableName.identifierOrNullIfSpecial == "equals"

    private val CallableId.isStringPlus: Boolean
        get() = classId == StandardClassIds.String && callableName.identifierOrNullIfSpecial == "plus"

    ////// KINDS

    private fun FirTypeRef.toConstantValueKind(): ConstantValueKind<*>? =
        when (this) {
            !is FirResolvedTypeRef -> null
            !is FirImplicitBuiltinTypeRef -> type.toConstantValueKind()

            is FirImplicitByteTypeRef -> ConstantValueKind.Byte
            is FirImplicitDoubleTypeRef -> ConstantValueKind.Double
            is FirImplicitFloatTypeRef -> ConstantValueKind.Float
            is FirImplicitIntTypeRef -> ConstantValueKind.Int
            is FirImplicitLongTypeRef -> ConstantValueKind.Long
            is FirImplicitShortTypeRef -> ConstantValueKind.Short

            is FirImplicitCharTypeRef -> ConstantValueKind.Char
            is FirImplicitStringTypeRef -> ConstantValueKind.String
            is FirImplicitBooleanTypeRef -> ConstantValueKind.Boolean

            else -> null
        }

    private fun ConeKotlinType.toConstantValueKind(): ConstantValueKind<*>? =
        when (this) {
            is ConeErrorType -> null
            is ConeLookupTagBasedType -> lookupTag.name.asString().toConstantValueKind()
            is ConeFlexibleType -> upperBound.toConstantValueKind()
            is ConeCapturedType -> lowerType?.toConstantValueKind() ?: constructor.supertypes!!.first().toConstantValueKind()
            is ConeDefinitelyNotNullType -> original.toConstantValueKind()
            is ConeIntersectionType -> intersectedTypes.first().toConstantValueKind()
            is ConeStubType -> null
            is ConeIntegerLiteralType -> null
        }

    private fun String.toConstantValueKind(): ConstantValueKind<*>? =
        when (this) {
            "Byte" -> ConstantValueKind.Byte
            "Double" -> ConstantValueKind.Double
            "Float" -> ConstantValueKind.Float
            "Int" -> ConstantValueKind.Int
            "Long" -> ConstantValueKind.Long
            "Short" -> ConstantValueKind.Short

            "Char" -> ConstantValueKind.Char
            "String" -> ConstantValueKind.String
            "Boolean" -> ConstantValueKind.Boolean

            else -> null
        }

    private fun <T> T.toConstantValueKind(): ConstantValueKind<*> =
        when (this) {
            is Byte -> ConstantValueKind.Byte
            is Double -> ConstantValueKind.Double
            is Float -> ConstantValueKind.Float
            is Int -> ConstantValueKind.Int
            is Long -> ConstantValueKind.Long
            is Short -> ConstantValueKind.Short

            is Char -> ConstantValueKind.Char
            is String -> ConstantValueKind.String
            is Boolean -> ConstantValueKind.Boolean

            null -> ConstantValueKind.Null
            else -> error("Unknown constant value")
        }

    private fun ConstantValueKind<*>.convertToNumber(value: Number?): Any? {
        if (value == null) {
            return null
        }
        return when (this) {
            ConstantValueKind.Byte -> value.toByte()
            ConstantValueKind.Double -> value.toDouble()
            ConstantValueKind.Float -> value.toFloat()
            ConstantValueKind.Int -> value.toInt()
            ConstantValueKind.Long -> value.toLong()
            ConstantValueKind.Short -> value.toShort()
            ConstantValueKind.UnsignedByte -> value.toLong().toUByte()
            ConstantValueKind.UnsignedShort -> value.toLong().toUShort()
            ConstantValueKind.UnsignedInt -> value.toLong().toUInt()
            ConstantValueKind.UnsignedLong -> value.toLong().toULong()
            else -> null
        }
    }

    private fun <T> ConstantValueKind<T>.toConstExpression(source: KtSourceElement?, value: Any?): FirConstExpression<T> =
        @Suppress("UNCHECKED_CAST")
        buildConstExpression(source, this, value as T)

    private fun FirFunctionCall.getOriginalFunction(): FirCallableDeclaration? {
        val symbol: FirBasedSymbol<*>? = when (val reference = calleeReference) {
            is FirResolvedNamedReference -> reference.resolvedSymbol
            else -> null
        }
        return symbol?.fir as? FirCallableDeclaration
    }
}
