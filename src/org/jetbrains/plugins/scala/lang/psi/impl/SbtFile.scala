package org.jetbrains.plugins.scala
package lang
package psi
package impl

import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.{PsiClass, ResolveState, PsiElement}
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.openapi.module.{Module, ModuleManager}
import com.intellij.openapi.project.Project
import api.toplevel.typedef.ScObject
import api.ScalaFile
import extensions.toPsiNamedElementExt

// TODO Add a proper file type?
object SbtFile {
  def isSbtFile(file: ScalaFile): Boolean = {
    file.isScriptFile() && file.name != null && file.name.endsWith("." + ScalaFileType.SBT_FILE_EXTENSION)
  }

  def findSbtProjectModule(project: Project): Option[Module] = {
    val moduleManager = ModuleManager.getInstance(project)
    moduleManager.getModules.find(_.getName == "project")
  }

  def processDeclarations(file: ScalaFileImpl,
                          processor: PsiScopeProcessor,
                          state: ResolveState,
                          lastParent: PsiElement,
                          place: PsiElement): Boolean = {

    if (isSbtFile(file)) {
      findSbtProjectModule(file.getProject).foreach {
        mod =>
          val sbtProjectModuleScope = mod.getModuleWithDependenciesAndLibrariesScope(false)
          val buildAndPluginImports: String = {
            def objectInheritorsOf(fqn: String): Seq[ScObject] = {
              val cls: PsiClass = ScalaPsiManager.instance(file.getProject).getCachedClass(fqn, sbtProjectModuleScope, ScalaPsiManager.ClassCategory.OBJECT)
              if (cls == null) Seq()
              else ClassInheritorsSearch.search(cls, sbtProjectModuleScope, true).toArray(PsiClass.EMPTY_ARRAY).collect {
                case x: ScObject => x
              }
            }
            val objectsToImport = Seq("sbt.Plugin", "sbt.Build").flatMap(objectInheritorsOf)
            objectsToImport.map(_.qualifiedName).map("import %s._" format _).mkString(";")
          }

          // See https://github.com/harrah/xsbt/wiki/Basic-Configuration
          val dummyFileText = "import sbt._;import Process._;import Keys._;" + buildAndPluginImports + ";object Dummy"

          val dummyFile = ScalaPsiElementFactory.parseFile(dummyFileText, file.getManager)
          val dummyObject = dummyFile.lastChild.get

          if (!dummyFile.processDeclarations(processor, state, dummyObject, place)) return false
      }
    }
    true
  }
}