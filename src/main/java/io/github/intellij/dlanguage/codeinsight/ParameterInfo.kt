package io.github.intellij.dlanguage.codeinsight

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTreeUtil.*
import io.github.intellij.dlanguage.psi.DLanguageFunctionCallExpression
import io.github.intellij.dlanguage.psi.DLanguageIdentifierOrTemplateInstance
import io.github.intellij.dlanguage.psi.references.DReference
import io.github.intellij.dlanguage.resolve.DResolveUtil
import io.github.intellij.dlanguage.utils.FunctionCallExpression
import io.github.intellij.dlanguage.utils.Parameter

class ParameterInfo : ParameterInfoHandler<FunctionCallExpression, Parameter> {
    override fun updateParameterInfo(parameterOwner: FunctionCallExpression, context: UpdateParameterInfoContext) {

    }

    override fun getParametersForDocumentation(p: Parameter?, context: ParameterInfoContext?): Array<Any>? {
        TODO("this doesn't need to be implemented b/c it is not used by intellij-core")
    }

    override fun tracksParameterIndex(): Boolean {
        TODO("this doesn't need to be implemented b/c it is not used by intellij-core")
    }

    override fun showParameterInfo(functionCallExpression: FunctionCallExpression, context: CreateParameterInfoContext) {
        var reference = functionCallExpression.unaryExpression?.primaryExpression?.identifierOrTemplateInstance?.identifier?.reference
        if (reference == null) {
            reference = functionCallExpression.unaryExpression?.primaryExpression?.identifierOrTemplateInstance?.templateInstance?.identifier?.reference
        }
        if (reference == null || reference !is DReference) {
            return
        }
        val definitionNodes = DResolveUtil.getInstance(functionCallExpression.project).findDefinitionNode(reference.element, false)
//        val resolvedElements: Array<ResolveResult> = reference.multiResolve(false);
        context.itemsToShow = definitionNodes.flatMap { findChildrenOfType(it, Parameter::class.java) }.filterNotNull().toTypedArray()
        context.showHint(functionCallExpression, 0, this)
    }

    override fun updateUI(p: Parameter, context: ParameterInfoUIContext) {
        context.setupRawUIComponentPresentation(p.text)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): FunctionCallExpression? {
        val listStart = context.offset
        val file = context.file
        val element = file.findElementAt(listStart)
        val functionCallExpression = getParentOfType(element, DLanguageFunctionCallExpression::class.java)
        return functionCallExpression;
    }

    override fun getParameterCloseChars(): String? {
        TODO("this doesn't need to be implemented b/c it is not used by intellij-core")
    }

    override fun getParametersForLookup(item: LookupElement, context: ParameterInfoContext): Array<Any>? {
        //todo I'm not sure what this is meant to do.
        return arrayOf(item)
    }

    override fun couldShowInLookup(): Boolean {
        return true
    }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): FunctionCallExpression? {
        val listStart = context.offset
        val file = context.file
        val element = file.findElementAt(listStart)
        val functionCallExpression = getParentOfType(element, DLanguageFunctionCallExpression::class.java)
        return functionCallExpression;
    }


}