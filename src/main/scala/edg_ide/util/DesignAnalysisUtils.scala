package edg_ide.util

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.types.TypeEvalContext
import com.jetbrains.python.psi._
import edg.ref.ref
import edg.schema.schema
import edg.util.Errorable
import edg.wir.DesignPath
import edg_ide.EdgirUtils
import edg_ide.actions.InsertConnectAction
import edg_ide.util.ExceptionNotifyImplicits.{ExceptErrorable, ExceptOption, ExceptSeq}

import scala.collection.mutable


object DesignAnalysisUtils {
  /** Returns the PyClass of a LibraryPath
    */
  def pyClassOf(path: ref.LibraryPath, project: Project): Errorable[PyClass] = {
    val pyPsi = PyPsiFacade.getInstance(project)
    Errorable(pyPsi.findClass(path.getTarget.getName), "no class")
  }

  /** Returns whether an element is after another element accounting for EDG function call semantics.
    * If within the same function, does a simple after analysis without accounting for runtime
    * behavior.
    *
    * It is assumed both are in the same class.
    */
  def elementAfterEdg(beforeElement: PsiElement, afterElement: PsiElement, project: Project): Option[Boolean] = {
    val beforeElementFunction = PsiTreeUtil.getParentOfType(beforeElement, classOf[PyFunction])
    val afterElementFunction = PsiTreeUtil.getParentOfType(afterElement, classOf[PyFunction])
    if (beforeElementFunction == null || afterElementFunction == null) {  // this generally shouldn't happen
      return None
    }
    val beforeElementClass = PsiTreeUtil.getParentOfType(beforeElementFunction, classOf[PyClass])
    val afterElementClass = PsiTreeUtil.getParentOfType(afterElementFunction, classOf[PyClass])
    if (beforeElementClass == null || afterElementClass == null) {  // this generally shouldn't happen
      return None
    }

    if (beforeElementClass != afterElementClass) {  // compare class relationships
      Some(afterElementClass.isSubclass(beforeElementClass, TypeEvalContext.codeCompletion(project, null)))
    } else if (beforeElementFunction != afterElementFunction) {  // compare function positions
      // TODO authoritative data structure for function name constants
      if (afterElementFunction.getName == "__init__") {
        Some(false)  // note that the equal case is handled above
      } else if (afterElementFunction.getName == "contents") {
        Some(beforeElementFunction.getName == "__init__")
      } else {
        Some(true)
      }
    } else {
      // compare positions within a function
      // the length is used so after includes the contents of the entire sub-tree
      Some(beforeElement.getTextOffset + beforeElement.getTextLength <
          afterElement.getTextOffset + afterElement.getTextLength)
    }
  }

  /** Returns all assigns to some path, by searching its parent classes for assign statements
    */
  def allAssignsTo(path: DesignPath, topDesign: schema.Design,
                   project: Project): Errorable[Seq[PyAssignmentStatement]] = exceptable {
    requireExcept(path.steps.nonEmpty, "node at top")
    val (parentPath, blockName) = path.split
    val parentBlock = EdgirUtils.resolveExactBlock(parentPath, topDesign)
        .exceptNone(s"no block at parent path $parentPath")
    requireExcept(parentBlock.superclasses.length == 1,
      s"invalid parent class ${EdgirUtils.SimpleSuperclass(parentBlock.superclasses)}")
    val parentPyClass = pyClassOf(parentBlock.superclasses.head, project).exceptError
    val assigns = findAssignmentsTo(parentPyClass, blockName, project).filter(_.canNavigateToSource)
        .exceptEmpty(s"no assigns to $blockName found in ${parentPyClass.getName}")
    assigns
  }

  /** Returns all connects to some path, by searching its parent class (for exports) and that parent's parent
    * (for connects).
    * TODO clarify semantics around chain and implicits!
    */
  def allConnectsTo(path: DesignPath, topDesign: schema.Design,
                   project: Project): Errorable[Seq[PyExpression]] = exceptable {
    requireExcept(path.steps.nonEmpty, "path at top")
    val (parentPath, parentBlock) = EdgirUtils.resolveDeepestBlock(path, topDesign)
    requireExcept(path.steps.length > parentPath.steps.length, "path resolves to block")
    val portName = path.steps(parentPath.steps.length)

    requireExcept(parentBlock.superclasses.length == 1,
      s"invalid parent class ${EdgirUtils.SimpleSuperclass(parentBlock.superclasses)}")
    val parentPyClass = pyClassOf(parentBlock.superclasses.head, project).exceptError
    val parentConnects = findGeneralConnectsTo(parentPyClass, ("", portName), project) match {
      case Errorable.Success(connects) => connects
      case _ => Seq()
    }

    val (containingPath, parentName) = parentPath.split
    val containingBlock = EdgirUtils.resolveExactBlock(containingPath, topDesign)
        .exceptNone(s"no block at containing path $containingPath")
    requireExcept(containingBlock.superclasses.length == 1,
      s"invalid containing class ${EdgirUtils.SimpleSuperclass(parentBlock.superclasses)}")
    val containingPyClass = pyClassOf(containingBlock.superclasses.head, project).exceptError
    val containingConnects = findGeneralConnectsTo(containingPyClass, ("", portName), project) match {
      case Errorable.Success(connects) => connects
      case _ => Seq()
    }

    (containingConnects ++ parentConnects)
        .filter(_.canNavigateToSource)
        .exceptEmpty(s"no connects to $parentName.$portName")
  }

  /** Returns all connections involving a port, specified relative from the container as a pair.
    * TODO: dedup w/ InsertConnectAction? But this is more general, and finds (some!) chains
    * TODO needs to be aware of implicit port semantics, including chain, implicit blocks, and export
    */
  def findGeneralConnectsTo(container: PyClass, pair: (String, String),
                     project: Project): Errorable[Seq[PyExpression]] = exceptable {
    val psiElementGenerator = PyElementGenerator.getInstance(project)

    container.getMethods.toSeq.map { psiFunction => exceptable {  //
      val selfName = psiFunction.getParameterList.getParameters.toSeq
          .exceptEmpty(s"function ${psiFunction.getName} has no self")
          .head.getName
      val connectReference = psiElementGenerator.createExpressionFromText(LanguageLevel.forElement(container),
        s"$selfName.connect")
      val chainReference = psiElementGenerator.createExpressionFromText(LanguageLevel.forElement(container),
        s"$selfName.chain")
      val portReference = psiElementGenerator.createExpressionFromText(LanguageLevel.forElement(container),
        InsertConnectAction.elementPairToText(selfName, pair))

      // Traverse w/ recursive visitor to find all port references inside a self.connect
      val references = mutable.ListBuffer[PyExpression]()
      container.accept(new PyRecursiveElementVisitor() {
        override def visitPyCallExpression(node: PyCallExpression): Unit = {
          if (node.getCallee.textMatches(connectReference) || node.getCallee.textMatches(chainReference)) {
            // an optimization to not traverse down other functions
            super.visitPyCallExpression(node)
          }
        }
        override def visitPyExpression(node: PyExpression): Unit = {
          if (node.textMatches(portReference)) {
            references += node
          }
        }
      })

      references.toSeq.map { reference => // from reference to call expression
        PsiTreeUtil.getParentOfType(reference, classOf[PyCallExpression])
      }.filter { call =>
        if (call == null) {
          false
        } else {
          call.getCallee.textMatches(connectReference) || call.getCallee.textMatches(chainReference)
        }
      }
    }}.collect {
      case Errorable.Success(x) => x
    }.flatten
        .distinct
        .exceptEmpty(s"class ${container.getName} contains no prior connects")
  }

  /** Returns all assignment statements targeting some targetName
    */
  def findAssignmentsTo(container: PyClass, targetName: String,
                        project: Project): Seq[PyAssignmentStatement] = {
    val psiElementGenerator = PyElementGenerator.getInstance(project)

    val assigns = container.getMethods.toSeq.collect { method =>
      val parameters = method.getParameterList.getParameters
      if (parameters.nonEmpty) {
        val selfName = parameters(0).getName
        val targetReference = psiElementGenerator.createExpressionFromText(LanguageLevel.forElement(method),
          s"$selfName.$targetName"
        )

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
}
