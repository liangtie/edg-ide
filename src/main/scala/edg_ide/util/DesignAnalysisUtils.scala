package edg_ide.util

import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.types.TypeEvalContext
import com.jetbrains.python.psi.{LanguageLevel, PyAssignmentStatement, PyClass, PyElementGenerator, PyFunction, PyPsiFacade, PyRecursiveElementVisitor, PyReferenceExpression, PyStatement}
import edg.ref.ref
import edg.schema.schema
import edg.util.Errorable
import edg.wir.DesignPath
import edg_ide.actions.InsertBlockAction
import edg_ide.{EdgirUtils, PsiUtils}
import edg_ide.util.ExceptionNotifyImplicits.{ExceptErrorable, ExceptNotify, ExceptOption, ExceptSeq}

import scala.collection.mutable


object DesignAnalysisUtils {
  /** Returns the PyClass of a LibraryPath
    */
  def pyClassOf(path: ref.LibraryPath, project: Project): Errorable[PyClass] = {
    val pyPsi = PyPsiFacade.getInstance(project)
    Errorable(pyPsi.findClass(path.getTarget.getName), "no class")
  }

  /** Returns all assigns to some path, by searching its parent classes for assign statements
    */
  def allAssignsTo(path: DesignPath, topDesign: schema.Design,
                   project: Project): Errorable[Seq[PyAssignmentStatement]] = exceptable {
    requireExcept(path.steps.nonEmpty, "node at top")
    val (parentPath, blockName) = path.split
    val parentBlock = EdgirUtils.resolveExactBlock(parentPath, topDesign.getContents)
        .exceptNone(s"no block at parent path $parentPath")
    requireExcept(parentBlock.superclasses.length == 1,
      s"invalid parent class ${EdgirUtils.SimpleSuperclass(parentBlock.superclasses)}")
    val parentPyClass = pyClassOf(parentBlock.superclasses.head, project).exceptError
    val assigns = findAssignmentsTo(parentPyClass, blockName, project).filter(_.canNavigateToSource)
        .exceptEmpty(s"no assigns to $blockName found in ${parentPyClass.getName}")
    assigns
  }

  /** Returns all assignment statements targeting some targetName
    */
  private def findAssignmentsTo(container: PyClass, targetName: String,
                                project: Project): Seq[PyAssignmentStatement] = {
    val psiElementGenerator = PyElementGenerator.getInstance(project)

    val assigns = container.getMethods.toSeq.collect { method =>
      val parameters = method.getParameterList.getParameters
      if (parameters.nonEmpty) {
        val selfName = parameters(0).getName
        val targetReference = psiElementGenerator.createExpressionFromText(LanguageLevel.forElement(method),
          s"$selfName.$targetName"
        ).asInstanceOf[PyReferenceExpression]

        // TODO support ElementDict and array ops
        // TODO search superclasses
        val methodAssigns = mutable.ListBuffer[PyAssignmentStatement]()
        method.accept(new PyRecursiveElementVisitor() {
          override def visitPyAssignmentStatement(node: PyAssignmentStatement): Unit = {
            if (node.getTargets.exists(expr => expr.textMatches(targetReference))) {
              methodAssigns += (node)
            }
          }
        })
        methodAssigns.toSeq
      } else {
        Seq()
      }
    }.flatten

    if (assigns.isEmpty) {  // search up the superclass chain if needed
      container.getSuperClasses(TypeEvalContext.userInitiated(project, null))
          .flatMap(findAssignmentsTo(_, targetName, project))
          .distinct  // TODO also prevent duplicate work in case of multiple inheritance?
    } else {
      assigns
    }
  }

  def findInsertionPoints(container: PyClass, project: Project): Errorable[Seq[PyFunction]] = exceptable {
    val psiFile = container.getContainingFile.exceptNull("no containing file")
    requireExcept(container.isSubclass(InsertBlockAction.VALID_SUPERCLASS,
      TypeEvalContext.codeCompletion(project, psiFile)),
      s"class ${container.getName} is not a subclass of ${InsertBlockAction.VALID_SUPERCLASS}")

    val methods = container.getMethods.toSeq.collect {
      case method if InsertBlockAction.VALID_FUNCTION_NAMES.contains(method.getName) => method
    }.exceptEmpty(s"class ${container.getName} contains no insertion methods")

    methods
  }
}
