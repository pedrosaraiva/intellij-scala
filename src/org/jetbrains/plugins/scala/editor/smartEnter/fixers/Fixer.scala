package org.jetbrains.plugins.scala
package editor.smartEnter.fixers

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import editor.smartEnter.ScalaSmartEnterProcessor

/**
 * @author Ksenia.Sautina
 * @since 1/28/13
 */

trait Fixer {
  def apply(editor: Editor, processor: ScalaSmartEnterProcessor, psiElement: PsiElement)
}
