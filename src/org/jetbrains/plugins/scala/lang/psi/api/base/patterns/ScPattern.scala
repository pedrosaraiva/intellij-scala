package org.jetbrains.plugins.scala
package lang
package psi
package api
package base
package patterns

import collection.mutable.ArrayBuffer
import expr.{ScBlockExpr, ScCatchBlock, ScMatchStmt}
import psi.types._
import result.{Failure, TypeResult, TypingContext}
import statements.{ScValue, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import com.intellij.psi._

/**
 * @author Alexander Podkhalyuzin
 */

trait ScPattern extends ScalaPsiElement {
  def getType(ctx: TypingContext) : TypeResult[ScType] = Failure("Cannot type pattern", Some(this))

  def bindings : Seq[ScBindingPattern] = {
    val b = new ArrayBuffer[ScBindingPattern]
    _bindings(this, b)
    b
  }

  private def _bindings(p : ScPattern, b : ArrayBuffer[ScBindingPattern]) : Unit = {
    p match {
      case binding: ScBindingPattern => b += binding
      case _ =>
    }

    for (sub <- p.subpatterns) {
      _bindings(sub, b)
    }
  }

  def subpatterns : Seq[ScPattern] = {
    if (this.isInstanceOf[ScReferencePattern]) return Seq.empty
    findChildrenByClassScala[ScPattern](classOf[ScPattern])
  }

  def expectedType: Option[ScType] = getParent match {
    case list : ScPatternList => list.getParent match {
      case _var : ScVariable => Some(_var.getType(TypingContext.empty).getOrElse(return None))
      case _val : ScValue => Some(_val.getType(TypingContext.empty).getOrElse(return None))
    }
    case argList : ScPatternArgumentList => {
      argList.getParent match {
        case constr : ScConstructorPattern => constr.bindParamTypes match {
          case Some(ts) =>
            for ((p, t) <- constr.args.patterns.elements zip ts.elements) {
              if (p == this) return Some(t)
            }
            None
          case _ => None
        }
      }
    }
    case patternList : ScPatterns => patternList.getParent match {
      case tuple : ScTuplePattern => tuple.expectedType match {
        case Some(ScTupleType(comps)) => {
          for((t, p) <- comps.elements.zip(patternList.patterns.elements)) {
            if (p == this) return Some(t)
          }
          None
        }
        case _ => None
      }
    }
    case clause : ScCaseClause => clause.getParent/*clauses*/.getParent match {
      case matchStat : ScMatchStmt => matchStat.expr match {
        case Some(e) => Some(e.getType(TypingContext.empty).getOrElse(Any))
        case _ => None
      }
      case _ : ScCatchBlock => {
        val thr = JavaPsiFacade.getInstance(getProject).findClass("java.lang.Throwable")
        if (thr != null) Some(new ScDesignatorType(thr)) else None
      }
      case b : ScBlockExpr => b.expectedType match { //l1.zip(l2) {case (a,b) =>}
        case Some(tp: ScType) => {
          (BaseTypes.get(tp) ++ Seq(tp)).find(x => x match {
            case ScFunctionType(ret, params) => true
            case ScParameterizedType(tp, _) if (ScType.extractClassType(tp) match {
              case Some((clazz: PsiClass, _)) if clazz.getQualifiedName.startsWith("scala.Function") => true
              case _ => false
            }) => true
            case _ => false
          }) match {
            case Some(tp: ScType) => tp match {
              case ScFunctionType(ret, params) => if (params.length == 1) Some(params(0))
               else Some(new ScTupleType(params, getProject))
              case ScParameterizedType(tp, params) => {
                if (params.length == 0) None
                else if (params.length == 1) Some(Unit)
                else if (params.length == 2) Some(params(0))
                else Some(new ScTupleType(params.slice(0, params.length - 1), getProject))
              }
            }
            case _ => None
          }
        }
        case _ => None
      }
    }
    case _ => None //todo
  }
}