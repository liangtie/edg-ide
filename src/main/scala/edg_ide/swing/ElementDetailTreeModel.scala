package edg_ide.swing

import com.intellij.ui.treeStructure.treetable.TreeTableModel
import edg.elem.elem
import edg.init.init
import edg.schema.schema
import edg_ide.EdgirUtils
import edg.wir._
import edg.compiler.{Compiler, ExprValue}

import javax.swing.JTree
import javax.swing.event.TreeModelListener
import javax.swing.tree._


trait ElementDetailNode {
  def getChildren: Seq[ElementDetailNode]
  def getColumns(index: Int): String = ""
}


object ElementDetailNode {
  class Dummy(text: String) extends ElementDetailNode {
    override def getChildren: Seq[ElementDetailNode] = Seq()
    override def toString: String = text
  }

  class PortNode(path: DesignPath, port: elem.PortLike, compiler: Compiler) extends ElementDetailNode {
    override def getChildren: Seq[ElementDetailNode] = port.is match {
        // TODO add CONNECTED_LINK
      case elem.PortLike.Is.Port(port) =>
        port.params.map { case (name, param) => new ParamNode(IndirectDesignPath.fromDesignPath(path) + name, param, compiler)}
            .toSeq
      case elem.PortLike.Is.Bundle(port) => port.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
        (   port.ports.map { case (name, subport) => new PortNode(path + name, subport, compiler) } ++
            port.params.map { case (name, param) => new ParamNode(IndirectDesignPath.fromDesignPath(path) + name, param, compiler)}
        ).toSeq
      case elem.PortLike.Is.Array(port) =>
        port.ports.map { case (name, subport) => new PortNode(path + name, subport, compiler) }
            .toSeq
      case _ => Seq()
    }

    override def toString: String = path.steps.last

    override def getColumns(index: Int): String = port.is match {
      case elem.PortLike.Is.Port(port) => port.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
      case elem.PortLike.Is.Bundle(port) => port.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
      case elem.PortLike.Is.Array(port) =>
        val superclasses = port.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
        s"Array[$superclasses]"
      case elem.PortLike.Is.LibElem(port) => "unelaborated " + EdgirUtils.SimpleLibraryPath(port)
      case _ => s"Unknown"
    }
  }

  class BlockNode(path: DesignPath, block: elem.BlockLike, compiler: Compiler) extends ElementDetailNode {
    override def getChildren: Seq[ElementDetailNode] = block.`type` match {
      case elem.BlockLike.Type.Hierarchy(block) =>
        (block.ports.map { case (name, port) => new PortNode(path + name, port, compiler) } ++
            block.links.map { case (name, sublink) =>
              new LinkNode(path + name, IndirectDesignPath.fromDesignPath(path) + name, sublink, compiler) } ++
            block.params.map { case (name, param) => new ParamNode(IndirectDesignPath.fromDesignPath(path) + name, param, compiler) }
            ).toSeq
      case _ => Seq()
    }

    override def toString: String = path.steps.last

    override def getColumns(index: Int): String = block.`type` match {
      case elem.BlockLike.Type.Hierarchy(block) => block.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
      case elem.BlockLike.Type.LibElem(block) => "unelaborated " + EdgirUtils.SimpleLibraryPath(block)
      case _ => s"Unknown"
    }
  }

  class LinkNode(path: DesignPath, relpath: IndirectDesignPath, link: elem.LinkLike, compiler: Compiler)
      extends ElementDetailNode {
    override def getChildren: Seq[ElementDetailNode] = link.`type` match {
      case elem.LinkLike.Type.Link(link) =>
        val portNodes = if (IndirectDesignPath.fromDesignPath(path) != relpath) {
          // Don't display ports if this is a CONNECTED_LINK
          Seq()
        } else {
          link.ports.map { case (name, port) => new PortNode(path + name, port, compiler) }
        }
        (portNodes ++
            link.links.map { case (name, sublink) => new LinkNode(path + name, relpath + name, sublink, compiler) } ++
            link.params.map { case (name, param) => new ParamNode(relpath + name, param, compiler) }
            ).toSeq
      case _ => Seq()
    }

    override def toString: String = path.steps.last

    override def getColumns(index: Int): String = {
      val className = link.`type` match {
        case elem.LinkLike.Type.Link(link) => link.superclasses.map(EdgirUtils.SimpleLibraryPath).mkString(", ")
        case elem.LinkLike.Type.LibElem(link) => "unelaborated " + EdgirUtils.SimpleLibraryPath(link)
        case _ => s"Unknown"
      }
      if (IndirectDesignPath.fromDesignPath(path) != relpath) {
        s"$className @ ${path}"  // show the path for CONNECTED_LINKS
      } else {
        className
      }
    }
  }

  class ParamNode(path: IndirectDesignPath, param: init.ValInit, compiler: Compiler) extends ElementDetailNode {
    override def getChildren: Seq[ElementDetailNode] = Seq()

    override def toString: String = path.steps.last.toString

    override def getColumns(index: Int): String = {
      import edg.compiler.{FloatValue, BooleanValue, IntValue, RangeValue, TextValue}
      val typeName = param.`val` match {
        case init.ValInit.Val.Floating(_) => "float"
        case init.ValInit.Val.Boolean(_) => "boolean"
        case init.ValInit.Val.Integer(_) => "integer"
        case init.ValInit.Val.Range(_) => "range"
        case init.ValInit.Val.Text(_) => "text"
        case param => s"unknown ${param.getClass}"
      }
      val value = compiler.getAllSolved.get(path) match {
        case Some(FloatValue(value)) => value  // TODO better formatting using SI prefixes
        case Some(IntValue(value)) => value
        case Some(RangeValue(lower, upper)) => s"($lower, $upper)"
        case Some(TextValue(value)) => value
        case Some(value) => value
        case None => "Unsolved"
      }
      s"$value ($typeName)"
    }
  }
}


class ElementDetailTreeModel(path: DesignPath, root: schema.Design, compiler: Compiler) extends SeqTreeTableModel[ElementDetailNode] {
  val rootNode: ElementDetailNode = EdgirUtils.resolveFromBlock(path, root.getContents) match {
    case Some(block: elem.BlockLike) => new ElementDetailNode.BlockNode(path, block, compiler)
    case Some(port: elem.PortLike) => new ElementDetailNode.PortNode(path, port, compiler)
    case Some(link: elem.LinkLike) => new ElementDetailNode.LinkNode(path, IndirectDesignPath.fromDesignPath(path), link, compiler)
    case Some(target) =>
      new ElementDetailNode.Dummy(s"Unknown $target @ $path")
    case None =>
      new ElementDetailNode.Dummy(s"Invalid path @ $path")
  }
  val COLUMNS = Seq("Item", "Value")

  // TreeView abstract methods
  //
  override def getRootNode: ElementDetailNode = rootNode

  override def getNodeChildren(node: ElementDetailNode): Seq[ElementDetailNode] = node.getChildren

  // These aren't relevant for trees that can't be edited
  override def valueForPathChanged(path: TreePath, newValue: Any): Unit = {}
  override def addTreeModelListener(l: TreeModelListener): Unit = {}
  override def removeTreeModelListener(l: TreeModelListener): Unit = {}

  // TreeTableView abstract methods
  //
  override def getColumnCount: Int = COLUMNS.length

  override def getColumnName(column: Int): String = COLUMNS(column)

  override def getColumnClass(column: Int): Class[_] = column match {
    case 0 => classOf[TreeTableModel]
    case _ => classOf[String]
  }

  override def getNodeValueAt(node: ElementDetailNode, column: Int): Object = node.getColumns(column)

  // These aren't relevant for trees that can't be edited
  override def isNodeCellEditable(node: ElementDetailNode, column: Int): Boolean = false
  override def setNodeValueAt(aValue: Any, node: ElementDetailNode, column: Int): Unit = {}

  def setTree(tree: JTree): Unit = { }  // tree updates ignored
}
