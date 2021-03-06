package org.jetbrains.plugins.scala
package components

import com.intellij.psi._
import icons.Icons
import javax.swing.Icon
import com.intellij.ide.IconProvider
import lang.psi.api.ScalaFile
import org.jetbrains.annotations.Nullable
import com.intellij.openapi.progress.ProgressManager

class ScalaIconProvider extends IconProvider {
  @Nullable
  override def getIcon(element: PsiElement, flags: Int): Icon = {
    ProgressManager.checkCanceled()
    if (element == null) return null
    if (element.isInstanceOf[ScalaFile]) {
      val file = element.asInstanceOf[ScalaFile]
      if (file.isWorksheetFile) return Icons.WORKSHEET_LOGO
      if (file.isScriptFile()) return Icons.SCRIPT_FILE_LOGO
      if (file.getVirtualFile == null) return Icons.SCRIPT_FILE_LOGO
      val name = file.getVirtualFile.getNameWithoutExtension
      val defs = file.typeDefinitions
      val clazzIterator = defs.iterator
      while (clazzIterator.hasNext) {
        val clazz = clazzIterator.next()
        if (name.equals(clazz.name)) return clazz.getIcon(flags)
      }
      if (!defs.isEmpty) return defs(0).getIcon(flags)
    }
    null
  }
}