package org.jetbrains.plugins.scala
package lang
package psi
package stubs
package elements

import api.base.ScModifierList
import com.intellij.util.io.StringRef
import impl.ScTemplateParentsStubImpl
import com.intellij.psi.stubs.{StubElement, IndexSink, StubOutputStream, StubInputStream}
import com.intellij.psi.PsiElement
import api.toplevel.templates.{ScClassParents, ScTemplateParents}
import com.intellij.openapi.diagnostic.Logger

/**
 * User: Alexander Podkhalyuzin
 * Date: 17.06.2009
 */

abstract class ScTemplateParentsElementType[Func <: ScTemplateParents](debugName: String)
        extends ScStubElementType[ScTemplateParentsStub, ScTemplateParents](debugName) {
  def serialize(stub: ScTemplateParentsStub, dataStream: StubOutputStream) {
    val array = stub.getTemplateParentsTypesTexts
    dataStream.writeByte(array.length)
    for (s <- array) {
      dataStream.writeName(s)
    }
    stub.getConstructor match {
      case Some(str) =>
        dataStream.writeBoolean(true)
        dataStream.writeName(str)
      case _ =>
        dataStream.writeBoolean(false)
    }
  }

  def createStubImpl[ParentPsi <: PsiElement](psi: ScTemplateParents, parentStub: StubElement[ParentPsi]): ScTemplateParentsStub = {
    val constr = psi match {
      case p: ScClassParents => p.constructor.map(_.getText)
      case _ => None
    }
    new ScTemplateParentsStubImpl(parentStub, this, constr, psi.typeElementsWithoutConstructor.map(_.getText).toArray)
  }

  def deserializeImpl(dataStream: StubInputStream, parentStub: Any): ScTemplateParentsStub = {
    val length = dataStream.readByte
    if (length >= 0) {
      val res = new Array[StringRef](length)
      for (i <- 0 until length) {
        res(i) = dataStream.readName
      }
      val constr = dataStream.readBoolean() match {
        case true => Some(dataStream.readName())
        case false => None
      }
      new ScTemplateParentsStubImpl(parentStub.asInstanceOf[StubElement[PsiElement]], this, constr, res)
    } else {
      ScTemplateParentsElementType.LOG.error("Negative byte deserialized for array")
      new ScTemplateParentsStubImpl(parentStub.asInstanceOf[StubElement[PsiElement]], this, None, Array.empty[StringRef])
    }
  }

  def indexStub(stub: ScTemplateParentsStub, sink: IndexSink) {}
}

object ScTemplateParentsElementType {
  private val LOG = Logger.getInstance("#org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScTemplateParentsElementType")
}