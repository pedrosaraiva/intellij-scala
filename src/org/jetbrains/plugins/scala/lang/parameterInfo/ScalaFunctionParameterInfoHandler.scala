package org.jetbrains.plugins.scala
package lang
package parameterInfo

import _root_.org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocumentationProvider
import _root_.org.jetbrains.plugins.scala.lang.psi.types._
import _root_.org.jetbrains.plugins.scala.lang.resolve.{ResolveUtils, ScalaResolveResult}

import _root_.scala.collection.mutable.ArrayBuffer
import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.completion.JavaCompletionUtil
import com.intellij.codeInsight.lookup.{LookupItem, LookupElement}
import com.intellij.lang.parameterInfo._

import com.intellij.psi._
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ArrayUtil
import com.intellij.util.containers.hash.HashSet
import java.awt.Color
import java.lang.{Class, String}
import lexer.ScalaTokenTypes
import nonvalue.Parameter
import psi.api.base.types.{ScParameterizedTypeElement, ScTypeElement}
import psi.api.base.{ScConstructor, ScPrimaryConstructor}
import psi.api.expr._
import psi.api.statements.ScFunction
import psi.api.toplevel.typedef.{ScClass, ScTypeDefinition}
import psi.api.toplevel.{ScTypeParametersOwner, ScTypedDefinition}
import psi.ScalaPsiUtil
import result.TypingContext
import psi.fake.FakePsiMethod
import collection.Seq
import psi.api.statements.params.{ScParameter, ScParameterClause}
import extensions.toPsiNamedElementExt

/**
 * User: Alexander Podkhalyuzin
 * Date: 18.01.2009
 */

class ScalaFunctionParameterInfoHandler extends ParameterInfoHandlerWithTabActionSupport[ScArgumentExprList, Any, ScExpression] {
  def getArgListStopSearchClasses: java.util.Set[_ <: Class[_]] = {
    java.util.Collections.singleton(classOf[PsiMethod])
  }

  def getParameterCloseChars: String = "{},);\n"

  def couldShowInLookup: Boolean = true

  def getActualParameterDelimiterType: IElementType = ScalaTokenTypes.tCOMMA

  def getActualParameters(argExprList: ScArgumentExprList): Array[ScExpression] = argExprList.exprs.toArray

  def getArgumentListClass: Class[ScArgumentExprList] = classOf[ScArgumentExprList]

  def getActualParametersRBraceType: IElementType = ScalaTokenTypes.tRBRACE

  def getArgumentListAllowedParentClasses: java.util.Set[Class[_]] = {
    val set = new HashSet[Class[_]]()
    set.add(classOf[ScMethodCall])
    set.add(classOf[ScConstructor])
    set.add(classOf[ScSelfInvocation])
    set
  }

  def findElementForParameterInfo(context: CreateParameterInfoContext): ScArgumentExprList = {
    findCall(context)
  }

  def findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): ScArgumentExprList = {
    findCall(context)
  }

  def getParametersForDocumentation(p: Any, context: ParameterInfoContext): Array[Object] = {
    p match {
      case x: ScFunction => {
        x.parameters.toArray
      }
      case _ => ArrayUtil.EMPTY_OBJECT_ARRAY
    }
  }

  def showParameterInfo(element: ScArgumentExprList, context: CreateParameterInfoContext) {
    context.showHint(element, element.getTextRange.getStartOffset, this)
  }

  def getParametersForLookup(item: LookupElement, context: ParameterInfoContext): Array[Object] = {
    if (!item.isInstanceOf[LookupItem[_]]) return null
    val allElements = JavaCompletionUtil.getAllPsiElements(item.asInstanceOf[LookupItem[_]])

    if (allElements != null &&
        allElements.size > 0 &&
        allElements.get(0).isInstanceOf[PsiMethod]) {
      return allElements.toArray(new Array[Object](allElements.size))
    }
    null
  }

  def updateParameterInfo(o: ScArgumentExprList, context: UpdateParameterInfoContext) {
    if (context.getParameterOwner != o) context.removeHint()
    val offset = context.getOffset
    var child = o.getNode.getFirstChildNode
    var i = 0
    while (child != null && child.getStartOffset < offset) {
      if (child.getElementType == ScalaTokenTypes.tCOMMA) i = i + 1
      child = child.getTreeNext
    }
    context.setCurrentParameter(i)
  }


  def updateUI(p: Any, context: ParameterInfoUIContext) {
    if (context == null || context.getParameterOwner == null || !context.getParameterOwner.isValid) return
    context.getParameterOwner match {
      case args: ScArgumentExprList => {
        val color: Color = context.getDefaultParameterColor
        val index = context.getCurrentParameterIndex
        val buffer: StringBuilder = new StringBuilder("")
        var isGrey = false
        //todo: var isGreen = true
        var namedMode = false
        def paramText(param: ScParameter, subst: ScSubstitutor) = {
          ScalaDocumentationProvider.parseParameter(param,
            (t: ScType) =>
              ScType.presentableText(subst.subst(t)), escape = false)
        }
        def applyToParameters(parameters: Seq[(Parameter, String)], subst: ScSubstitutor, canBeNaming: Boolean,
                              isImplicit: Boolean = false) {
          if (parameters.length > 0) {
            var k = 0
            val exprs: Seq[ScExpression] = args.exprs
            if (isImplicit) buffer.append("implicit ")
            val used = new Array[Boolean](parameters.length)
            while (k < parameters.length) {
              val namedPrefix = "["
              val namedPostfix = "]"

              def appendFirst(useGrey: Boolean = false) {
                val getIt = used.indexOf(false)
                used(getIt) = true
                if (namedMode) buffer.append(namedPrefix)
                val param: (Parameter, String) = parameters(getIt)

                buffer.append(param._2)
                if (namedMode) buffer.append(namedPostfix)
              }
              def doNoNamed(expr: ScExpression) {
                if (namedMode) {
                  isGrey = true
                  appendFirst()
                } else {
                  val exprType = expr.getType(TypingContext.empty).getOrNothing
                  val getIt = used.indexOf(false)
                  used(getIt) = true
                  val param: (Parameter, String) = parameters(getIt)
                  val paramType = param._1.paramType
                  if (!exprType.conforms(paramType)) isGrey = true
                  buffer.append(param._2)
                }
              }
              if (k == index || (k == parameters.length - 1 && index >= parameters.length &&
                      parameters(parameters.length - 1)._1.isRepeated)) {
                buffer.append("<b>")
              }
              if (k < index && !isGrey) {
                //slow checking
                if (k >= exprs.length) { //shouldn't be
                  appendFirst(useGrey = true)
                  isGrey = true
                } else {
                  exprs(k) match {
                    case assign@NamedAssignStmt(name) => {
                      val ind = parameters.indexWhere(param => ScalaPsiUtil.memberNamesEquals(param._1.name, name))
                      if (ind == -1 || used(ind) == true) {
                        doNoNamed(assign)
                      } else {
                        if (k != ind) namedMode = true
                        used(ind) = true
                        val param: (Parameter, String) = parameters(ind)
                        if (namedMode) buffer.append(namedPrefix)
                        buffer.append(param._2)
                        if (namedMode) buffer.append(namedPostfix)
                        assign.getRExpression match {
                          case Some(expr: ScExpression) => {
                            for (exprType <- expr.getType(TypingContext.empty)) {
                              val paramType = param._1.paramType
                              if (!exprType.conforms(paramType)) isGrey = true
                            }
                          }
                          case _ => isGrey = true
                        }
                      }
                    }
                    case expr: ScExpression => {
                      doNoNamed(expr)
                    }
                  }
                }
              } else {
                //fast checking
                if (k >= exprs.length) {
                  appendFirst()
                } else {
                  exprs(k) match {
                    case NamedAssignStmt(name) => {
                      val ind = parameters.indexWhere(param => ScalaPsiUtil.memberNamesEquals(param._1.name, name))
                      if (ind == -1 || used(ind) == true) {
                        appendFirst()
                      } else {
                        if (k != ind) namedMode = true
                        used(ind) = true
                        if (namedMode) buffer.append(namedPrefix)
                        buffer.append(parameters(ind)._2)
                        if (namedMode) buffer.append(namedPostfix)
                      }
                    }
                    case _ => appendFirst()
                  }
                }
              }
              if (k == index || (k == parameters.length - 1 && index >= parameters.length &&
                      parameters(parameters.length - 1)._1.isRepeated)) {
                buffer.append("</b>")
              }
              k = k + 1
              if (k != parameters.length) buffer.append(", ")
            }
            if (!isGrey && exprs.length > parameters.length && index >= parameters.length) {
              if (!namedMode && parameters(parameters.length - 1)._1.isRepeated) {
                val paramType = parameters(parameters.length - 1)._1.paramType
                while (!isGrey && k < exprs.length.min(index)) {
                  if (k < index) {
                    for (exprType <- exprs(k).getType(TypingContext.empty)) {
                    if (!exprType.conforms(paramType)) isGrey = true
                    }
                  }
                  k = k + 1
                }
              } else isGrey = true
            }
          } else buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
        }
        p match {
          case x: String if x == "" => {
            buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
          }
          case (seq: Seq[(String, ScType, PsiAnnotationMemberValue)], i: Int) => {
            if (seq.length == 0) buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
            else {
              val paramsSeq: Seq[(Parameter, String)] = seq.zipWithIndex.map {
                case (t, paramIndex) =>
                  (new Parameter(t._1, t._2, t._3 != null, false, false, paramIndex), t._1 + ": " + ScType.presentableText(t._2) + (
                          if (t._3 != null) " = " + t._3.getText else ""))
              }
              applyToParameters(paramsSeq, ScSubstitutor.empty, canBeNaming = true, isImplicit = false)
            }
          }
          case (sign: PhysicalSignature, i: Int) => { //i  can be -1 (it's update method)
            val subst = sign.substitutor
            sign.method match {
              case method: ScFunction => {
                val clauses = method.effectiveParameterClauses
                if (clauses.length <= i || (i == -1 && clauses.length == 0)) buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
                else {
                  val clause: ScParameterClause = if (i >= 0) clauses(i) else clauses(0)
                  val length = clause.parameters.length
                  val parameters: Seq[ScParameter] = if (i != -1) clause.parameters else clause.parameters.take(length - 1)
                  applyToParameters(parameters.map(param =>
                    ((new Parameter(param), paramText(param, subst)))), subst, canBeNaming = true, isImplicit = clause.isImplicit)
                }
              }
              case method: FakePsiMethod => {
                if (method.params.length == 0) buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
                else {
                  buffer.append(method.params.
                          map((param: Parameter) => {
                    val buffer: StringBuilder = new StringBuilder("")
                    val paramType = param.paramType
                    val name = param.name
                    if (name != "") {
                      buffer.append(name)
                      buffer.append(": ")
                    }
                    buffer.append(ScType.presentableText(paramType))
                    if (param.isRepeated) buffer.append("*")

                    if (param.isDefault) buffer.append(" = _")

                    val isBold = if (method.params.indexOf(param) == index || (param.isRepeated && method.params.indexOf(param) <= index)) true
                    else {
                      //todo: check type
                      false
                    }
                    val paramText = buffer.toString()
                    if (isBold) "<b>" + paramText + "</b>" else paramText
                  }).mkString(", "))
                }
              }
              case method: PsiMethod => {
                val p = method.getParameterList
                if (p.getParameters.length == 0) buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
                else {
                  buffer.append(p.getParameters.
                          map((param: PsiParameter) => {
                    val buffer: StringBuilder = new StringBuilder("")
                    val list = param.getModifierList
                    if (list == null) return
                    val lastSize = buffer.length
                    for (a <- list.getAnnotations) {
                      if (lastSize != buffer.length) buffer.append(" ")
                      val element = a.getNameReferenceElement
                      if (element != null) buffer.append("@").append(element.getText)
                    }
                    if (lastSize != buffer.length) buffer.append(" ")
                    val paramType = param.getType match {
                      case arr: PsiArrayType if param.isVarArgs => arr.getComponentType
                      case tp => tp
                    }

                    val name = param.name
                    if (name != null) {
                      buffer.append(name)
                    }
                    buffer.append(": ")
                    buffer.append(ScType.presentableText(subst.subst(
                      ScType.create(paramType, method.getProject, paramTopLevel = true)
                    )))
                    if (param.isVarArgs) buffer.append("*")

                    val isBold = if (p.getParameters.indexOf(param) == index || (param.isVarArgs && p.getParameters.indexOf(param) <= index)) true
                    else {
                      //todo: check type
                      false
                    }
                    val paramText = buffer.toString()
                    if (isBold) "<b>" + paramText + "</b>" else paramText
                  }).mkString(", "))
                }
              }
            }
          }
          case (constructor: ScPrimaryConstructor, subst: ScSubstitutor, i: Int) => {
            val clauses = constructor.effectiveParameterClauses
            if (clauses.length <= i) buffer.append(CodeInsightBundle.message("parameter.info.no.parameters"))
            else {
              val clause: ScParameterClause = clauses(i)
              applyToParameters(clause.parameters.map(param =>
                ((new Parameter(param), paramText(param, subst)))), subst, canBeNaming = true, isImplicit = clause.isImplicit)
            }
          }
          case _ =>
        }
        val startOffset = buffer.indexOf("<b>")
        if (startOffset != -1) buffer.replace(startOffset, startOffset + 3, "")

        val endOffset = buffer.indexOf("</b>")
        if (endOffset != -1) buffer.replace(endOffset, endOffset + 4, "")

        if (buffer.toString != "")
          context.setupUIComponentPresentation(buffer.toString(), startOffset, endOffset, isGrey, false, false, color)
        else
          context.setUIComponentEnabled(false)
      }
      case _ =>
    }
  }

  def tracksParameterIndex: Boolean = true

  /**
   * Returns context's ScArgumentExprList and fill context items
   * by appropriate PsiElements (in which we can resolve)
   * @param context current context
   * @return context's argument expression
   */
  private def findCall(context: ParameterInfoContext): ScArgumentExprList = {
    val (file, offset) = (context.getFile, context.getOffset)
    val element = file.findElementAt(offset)
    if (element == null) return null
    val args: ScArgumentExprList = PsiTreeUtil.getParentOfType(element, getArgumentListClass)
    if (args != null) {
      context match {
        case context: CreateParameterInfoContext => {
          args.getParent match {
            case call: ScMethodCall => {
              val res: ArrayBuffer[Object] = new ArrayBuffer[Object]
              def collectResult() {
                val canBeUpdate = call.getParent match {
                  case assignStmt: ScAssignStmt if call == assignStmt.getLExpression => true
                  case notExpr if !notExpr.isInstanceOf[ScExpression] || notExpr.isInstanceOf[ScBlockExpr] => true
                  case _ => false
                }
                val count = args.invocationCount
                val gen = args.callGeneric.getOrElse(null: ScGenericCall)
                def collectSubstitutor(element: PsiElement): ScSubstitutor = {
                  if (gen == null) return ScSubstitutor.empty
                  val tp: Array[(String, String)] = element match {
                    case tpo: ScTypeParametersOwner => tpo.typeParameters.map(p => (p.name, ScalaPsiUtil.getPsiElementId(p))).toArray
                    case ptpo: PsiTypeParameterListOwner => ptpo.getTypeParameters.map(p => (p.name, ScalaPsiUtil.getPsiElementId(p)))
                    case _ => return ScSubstitutor.empty
                  }
                  val typeArgs: Seq[ScTypeElement] = gen.arguments
                  val map = new collection.mutable.HashMap[(String, String), ScType]
                  for (i <- 0 to Math.min(tp.length, typeArgs.length) - 1) {
                    map += ((tp(i), typeArgs(i).calcType))
                  }
                  new ScSubstitutor(Map(map.toSeq: _*), Map.empty, None)
                }
                def collectForType(typez: ScType) {
                  ScType.extractClassType(typez, Some(file.getProject)) match {
                    case Some((clazz: PsiClass, subst: ScSubstitutor)) => {
                      for{
                        sign <- ScalaPsiUtil.getApplyMethods(clazz)
                        if ResolveUtils.isAccessible(sign.method, args)
                      } {
                        val subst1 = {
                          val signSubst = sign.substitutor
                          val collectSubst = collectSubstitutor(sign.method)
                          signSubst.followed(subst).followed(collectSubst)
                        }
                        res += ((new PhysicalSignature(sign.method, subst1), 0))
                      }
                      if (canBeUpdate) {
                        for{
                          sign <- ScalaPsiUtil.getUpdateMethods(clazz)
                          if ResolveUtils.isAccessible(sign.method, args)
                        } {
                          res += ((new PhysicalSignature(sign.method, subst.followed(sign.
                                  substitutor).followed(collectSubstitutor(sign.method))), -1))
                        }
                      }
                    }
                    case _ =>
                  }
                }
                args.callReference match {
                  case Some(ref: ScReferenceExpression) => {
                    if (count > 1) {
                      //todo: missed case with last implicit call
                      ref.bind() match {
                        case Some(ScalaResolveResult(function: ScFunction, subst: ScSubstitutor)) if function.
                                effectiveParameterClauses.length >= count => {
                          res += ((new PhysicalSignature(function, subst.followed(collectSubstitutor(function))), count - 1))
                          return
                        }
                        case _ => {
                          for (typez <- call.getEffectiveInvokedExpr.getType(TypingContext.empty)) //todo: implicit conversions
                          {collectForType(typez)}
                        }
                      }
                    } else {
                      val variants: Array[ResolveResult] = ref.getSameNameVariants
                      for {
                        variant <- variants
                        if !variant.getElement.isInstanceOf[PsiMember] ||
                            ResolveUtils.isAccessible(variant.getElement.asInstanceOf[PsiMember], ref)
                      } {
                        variant match {
                          //todo: Synthetic function
                          case ScalaResolveResult(method: PsiMethod, subst: ScSubstitutor) => {
                            res += ((new PhysicalSignature(method, subst.followed(collectSubstitutor(method))), 0))
                          }
                          /*case ScalaResolveResult(clazz: ScClass, subst: ScSubstitutor) if clazz.isCase => {
                            clazz.constructor match {
                              case Some(constructor) => res += ((constructor, subst.followed(collectSubstitutor(clazz)), 0))
                              case None => res += ""
                            }
                          }*/
                          case ScalaResolveResult(typed: ScTypedDefinition, subst: ScSubstitutor) => {
                            val typez = subst.subst(typed.getType(TypingContext.empty).getOrNothing) //todo: implicit conversions
                              collectForType(typez)
                          }
                          case _ =>
                        }
                      }
                    }
                  }
                  case None => {
                    for (typez <- call.getEffectiveInvokedExpr.getType(TypingContext.empty)) { //todo: implicit conversions
                      collectForType(typez)
                    }
                  }
                }
              }
              collectResult()
              context.setItemsToShow(res.toArray)
            }
            case constr: ScConstructor => {
              val res: ArrayBuffer[Object] = new ArrayBuffer[Object]
              val typeElement = constr.typeElement
              val i = constr.arguments.indexOf(args)
              ScType.extractClassType(typeElement.calcType, Some(file.getProject)) match {
                case Some((clazz: PsiClass, subst: ScSubstitutor)) => {
                  clazz match {
                    case clazz: ScClass => {
                      clazz.constructor match {
                        case Some(constr: ScPrimaryConstructor) if i < constr.effectiveParameterClauses.length => {
                          typeElement match {
                            case gen: ScParameterizedTypeElement => {
                              val tp = clazz.typeParameters.map(p => (p.name, ScalaPsiUtil.getPsiElementId(p)))
                              val typeArgs: Seq[ScTypeElement] = gen.typeArgList.typeArgs
                              val map = new collection.mutable.HashMap[(String, String), ScType]
                              for (i <- 0 to Math.min(tp.length, typeArgs.length) - 1) {
                                map += ((tp(i), typeArgs(i).calcType))
                              }
                              val substitutor = new ScSubstitutor(Map(map.toSeq: _*), Map.empty, None)
                              res += ((constr, substitutor.followed(subst), i))
                            }
                            case _ => res += ((constr, subst, i))
                          }
                        }
                        case Some(constr) if i == 0 => res += ""
                        case None => res += ""
                        case _ =>
                      }
                      for (constr <- clazz.functions if !constr.isInstanceOf[ScPrimaryConstructor] &&
                              constr.isConstructor && ((constr.clauses match {
                          case Some(x) => x.clauses.length
                          case None => 1
                        }) > i))
                        res += ((new PhysicalSignature(constr, subst), i))
                    }
                    case clazz: PsiClass if clazz.isAnnotationType => {
                      val resulting: (Seq[(String, ScType, PsiAnnotationMemberValue)], Int) =
                        ((clazz.getMethods.toSeq.filter(_.isInstanceOf[PsiAnnotationMethod]).map(meth => ((meth.name,
                          ScType.create(meth.getReturnType, meth.getProject, meth.getResolveScope),
                          meth.asInstanceOf[PsiAnnotationMethod].getDefaultValue))), i))
                      res += resulting
                    }
                    case clazz: PsiClass if !clazz.isInstanceOf[ScTypeDefinition] => {
                      for (constructor <- clazz.getConstructors) {
                        typeElement match {
                          case gen: ScParameterizedTypeElement => {
                            val tp = clazz.getTypeParameters.map(p => (p.name, ScalaPsiUtil.getPsiElementId(p)))
                            val typeArgs: Seq[ScTypeElement] = gen.typeArgList.typeArgs
                            val map = new collection.mutable.HashMap[(String, String), ScType]
                            for (i <- 0 to Math.min(tp.length, typeArgs.length) - 1) {
                              map += ((tp(i), typeArgs(i).calcType))
                            }
                            val substitutor = new ScSubstitutor(Map(map.toSeq: _*), Map.empty, None)
                            res += ((new PhysicalSignature(constructor, substitutor.followed(subst)), i))
                          }
                          case _ => res += ((new PhysicalSignature(constructor, subst), i))
                        }
                      }
                    }
                    case _ =>
                  }
                }
                case _ =>
              }
              context.setItemsToShow(res.toArray)
            }
            case self: ScSelfInvocation =>
              val res: ArrayBuffer[Object] = new ArrayBuffer[Object]
              val i = self.arguments.indexOf(args)
              val clazz = PsiTreeUtil.getParentOfType(self, classOf[ScClass], true)
              clazz match {
                case clazz: ScClass =>
                  clazz.constructor match {
                    case Some(constr: ScPrimaryConstructor) if i < constr.effectiveParameterClauses.length =>
                      res += ((constr, ScSubstitutor.empty, i))
                    case Some(constr) if i == 0 => res += ""
                    case None => res += ""
                    case _ =>
                  }
                  for {
                    constr <- clazz.functions
                    if !constr.isInstanceOf[ScPrimaryConstructor] &&
                      constr.isConstructor &&
                      constr.clauses.map(_.clauses.length).getOrElse(1) > i
                  } {
                    if (!PsiTreeUtil.isAncestor(constr, self, true) &&
                      constr.getTextRange.getStartOffset < self.getTextRange.getStartOffset) {
                      res += ((new PhysicalSignature(constr, ScSubstitutor.empty), i))
                    }
                  }
                case _ =>
              }
              context.setItemsToShow(res.toArray)
          }
        }
        case context: UpdateParameterInfoContext => {
          var el = element
          while (el.getParent != args) el = el.getParent
          var index = 1
          for (expr <- args.exprs if expr != el) index += 1
          context.setCurrentParameter(index)
          context.setHighlightedParameter(el)
        }
        case _ =>
      }
    }
    args
  }
}

object ParameterInfoUtil {
  /**
   * Light green colour. Used for current resolve context showing.
   */
  val highlightedColor = new Color(231, 254, 234)
}
