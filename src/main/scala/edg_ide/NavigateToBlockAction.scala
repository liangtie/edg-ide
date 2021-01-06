package edg_ide

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

import com.intellij.notification.{NotificationGroup, NotificationType, Notification}


class NavigateToBlockAction() extends AnAction() {
  val notificationGroup: NotificationGroup = NotificationGroup.balloonGroup("my.group.id")

  override def actionPerformed(event: AnActionEvent): Unit = {
    val (editor, psiFile) = (event.getData(CommonDataKeys.EDITOR), event.getData(CommonDataKeys.PSI_FILE)) match {
      case (null, _) | (null, _) => notificationGroup
          .createNotification("No editor", NotificationType.WARNING)
          .notify(event.getProject)
        return
      case (editor, psiFile) => (editor, psiFile)
    }

    val offset = editor.getCaretModel.getOffset
    val element = psiFile.findElementAt(offset) match {
      case null => notificationGroup.createNotification("No element at code", NotificationType.WARNING)
          .notify(event.getProject)
      case element => element
    }

    val notification: Notification = notificationGroup.createNotification(
      s"PsiTest", s"$element at $offset", "Content",
      NotificationType.INFORMATION)

    notification.notify(event.getProject)
  }

  override def update(e: AnActionEvent): Unit = {
    // TODO only enabled when SplitFileEditor is open
    e.getPresentation.setEnabledAndVisible(true)
  }
}
