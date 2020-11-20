package edg_ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor._
import com.intellij.openapi.editor.event._
import com.intellij.openapi.fileEditor._
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import org.jdom.Attribute
import org.jdom.Element


object SplitFileEditorProviderUtils {
  def getBuilderFromEditorProvider(provider: FileEditorProvider, project: Project, file: VirtualFile) = {
    if (provider.isInstanceOf[AsyncFileEditorProvider]) {
      provider.asInstanceOf[AsyncFileEditorProvider].createEditorAsync(project, file)
    } else {
      new AsyncFileEditorProvider.Builder() {
        def build: FileEditor = provider.createEditor(project, file)
      }
    }
  }
}


class SplitFileEditorProvider extends AsyncFileEditorProvider with DumbAware {
  val textProvider = new PsiAwareTextEditorProvider
  val editorTypeId = "pycharm-edg"

  override def accept(project: Project, file: VirtualFile): Boolean =
    textProvider.accept(project, file) && "py" == file.getExtension

  override def createEditor(project: Project, file: VirtualFile): FileEditor =
    createEditorAsync(project, file).build

  override def getEditorTypeId: String = editorTypeId

  override def getPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

  override def createEditorAsync(project: Project, file: VirtualFile): AsyncFileEditorProvider.Builder = {
    val documentManager = FileDocumentManager.getInstance
    val mainDocument = documentManager.getDocument(file)
    // TODO maybe clean up this line?
    val textBuilder = SplitFileEditorProviderUtils.getBuilderFromEditorProvider(textProvider, project, file)

    return new AsyncFileEditorProvider.Builder() {
      private var mainEditor: Option[Editor] = None

      def build: FileEditor = {
        val disposable: Disposable = () => {}
        EditorFactory.getInstance.addEditorFactoryListener(new EditorFactoryListener() {
          // TODO is this even necessary since we're not intercepting the scrolling?
          override def editorCreated(event: EditorFactoryEvent): Unit = {
            val editor = event.getEditor
            val document = editor.getDocument
            require(mainEditor == None)
            mainEditor = Some(editor)
          }
        }, disposable)

        val editor = new SplitFileEditor(textBuilder.build(), file)

        require(mainEditor != None)

        editor
      }
    }
  }
}