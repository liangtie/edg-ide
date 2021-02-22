package edg_ide.actions

import com.intellij.notification.{NotificationGroup, NotificationType}
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.WriteCommandAction.writeCommandAction
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThrowableRunnable
import com.jetbrains.python.psi.{LanguageLevel, PyAssignmentStatement, PyClass, PyElementGenerator, PyFunction, PyStatementList}
import edg_ide.ui.BlockVisualizerService
import edg_ide.util.{ExceptionNotifyException, exceptionNotify}
import edg_ide.util.ExceptionNotifyImplicits._


class InsertBlockAction() extends AnAction() {
  val VALID_FUNCTION_NAMES = Set("__init__", "contents")

  override def actionPerformed(event: AnActionEvent): Unit = {
    exceptionNotify("edg_ide.actions.InsertBlockAction", event.getProject) {
      val visualizer = BlockVisualizerService.apply(event.getProject).visualizerPanelOption
          .exceptNull("No visualizer panel")

      val editor = event.getData(CommonDataKeys.EDITOR).exceptNull("No editor")
      val offset = editor.getCaretModel.getOffset
      val psiFile = event.getData(CommonDataKeys.PSI_FILE).exceptNull("No PSI file")
      val psiElement = psiFile.findElementAt(offset).exceptNull("No PSI element")
      val psiClass = PsiTreeUtil.getParentOfType(psiElement, classOf[PyClass])
          .exceptNull("No containing PSI class")

      val psiContainingList = psiElement.getParent.instanceOfExcept[PyStatementList](s"Invalid location to insert block")
      val psiContainingFunction = psiContainingList.getParent.instanceOfExcept[PyFunction]("Not in a function")
      if (!VALID_FUNCTION_NAMES.contains(psiContainingFunction.getName)) {
        throw new ExceptionNotifyException(s"Containing function ${psiContainingFunction.getName} not valid for block insertion")
      }

      val psiElementGenerator = PyElementGenerator.getInstance(event.getProject)
      val newAssign = psiElementGenerator.createFromText(LanguageLevel.forElement(psiElement),
        classOf[PyAssignmentStatement],
        "self.target_name = self.Block(TargetClass())"
      )

      writeCommandAction(event.getProject).withName("Insert Block").run(() => {
        psiContainingList.addAfter(newAssign, psiElement)
      })
    }
  }
}
