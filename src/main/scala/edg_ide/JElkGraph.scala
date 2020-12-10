package edg_ide

import scala.collection.JavaConverters._
import java.awt.{BasicStroke, Dimension, Graphics, Graphics2D, Rectangle}
import java.awt.event.{MouseEvent, MouseMotionListener}
import com.intellij.util.ui.UIUtil

import javax.swing.{JComponent, Scrollable}
import org.eclipse.elk.graph._

import java.awt.geom.AffineTransform


class JElkGraph(var rootNode: ElkNode) extends JComponent with Scrollable with Zoomable
    with MouseMotionListener {
  var zoomLevel: Float = 1.0f

  override def setZoom(zoom: Float): Unit = {
    zoomLevel = zoom
  }
  override def getZoom = zoomLevel


  setGraph(rootNode)

  // support for mouse drag: https://docs.oracle.com/javase/tutorial/uiswing/components/scrollpane.html
  setAutoscrolls(true)
  addMouseMotionListener(this)

  def setGraph(newGraph: ElkNode): Unit = {
    rootNode = newGraph
    validate()
    repaint()
  }

  override def paintComponent(paintGraphics: Graphics): Unit = {
    val scaling = new AffineTransform()
    scaling.scale(zoomLevel, zoomLevel)
    val g = paintGraphics.create().asInstanceOf[Graphics2D]
    g.transform(scaling)
    g.setStroke(new BasicStroke(1/zoomLevel))  // keep stroke at 1px

    // Keep the real font size constant, regardless of zoom
    val currentFont = g.getFont
    val newFont = currentFont.deriveFont(currentFont.getSize / zoomLevel)
    g.setFont(newFont)

    val fontMetrics = g.getFontMetrics(g.getFont)

    def paintBlock(node: ElkNode, parentX: Int, parentY: Int): Unit = {
      val nodeX = parentX + node.getX.toInt
      val nodeY = parentY + node.getY.toInt

      val rectG = g.create()
      rectG.setColor(UIUtil.shade(rectG.getColor, 1, 0.15))
      rectG.fillRect(nodeX, nodeY,
        node.getWidth.toInt, node.getHeight.toInt)

      g.drawRect(nodeX, nodeY,
        node.getWidth.toInt, node.getHeight.toInt)

      node.getLabels.asScala.foreach { label =>
        // convert the center x, y to top left aligned coordinates
        val labelX = (label.getX + label.getWidth / 2).toInt - fontMetrics.stringWidth(label.getText) / 2
        val labelY = (label.getY + label.getHeight / 2).toInt + fontMetrics.getHeight / 2

        g.drawString(label.getText, labelX + nodeX, labelY + nodeY)
      }

      node.getPorts.asScala.foreach { port =>
        g.drawRect(nodeX + port.getX.toInt, nodeY + port.getY.toInt,
          port.getWidth.toInt, port.getHeight.toInt)

        port.getLabels.asScala.foreach { label =>
          // convert the center x, y to top left aligned coordinates
          val labelX = (label.getX + label.getWidth / 2).toInt - fontMetrics.stringWidth(label.getText) / 2
          val labelY = (label.getY + label.getHeight / 2).toInt + fontMetrics.getHeight / 2

          g.drawString(label.getText, labelX + nodeX + port.getX.toInt, labelY + nodeY + port.getY.toInt)
        }
      }

      node.getChildren.asScala.foreach { childNode =>
        paintBlock(childNode, nodeX, nodeY)
      }

      node.getContainedEdges.asScala.foreach { edge =>
        paintEdge(edge, nodeX, nodeY)
      }
    }

    def paintEdge(edge: ElkEdge, parentX: Int, parentY: Int): Unit = {
      edge.getSections.asScala.foreach { section =>
        // these are still in parent-relative coordinates
        val start = (section.getStartX.toInt, section.getStartY.toInt)
        val end = (section.getEndX.toInt, section.getEndY.toInt)
        val bends = section.getBendPoints.asScala.map { elkBendPoint =>
          (elkBendPoint.getX.toInt, elkBendPoint.getY.toInt)
        }
        val allPoints = Seq(start) ++ bends ++ Seq(end)

        allPoints.sliding(2).foreach { case point1 :: point2 :: Nil =>
          g.drawLine(point1._1 + parentX, point1._2 + parentY,
            point2._1 + parentX, point2._2 + parentY
          )
        }
      }
    }

    // we don't just call paintBlock on the root because we don't want the containing frame
    rootNode.getChildren.asScala.foreach { childNode =>
      paintBlock(childNode, rootNode.getX.toInt, rootNode.getY.toInt)
    }
    rootNode.getContainedEdges.asScala.foreach{ edge =>
      paintEdge(edge, rootNode.getX.toInt, rootNode.getY.toInt)
    }
  }

  // Mouse drag handler
  override def mouseMoved(e: MouseEvent): Unit = { }  // nothing happens on mouse motion
  override def mouseDragged(e: MouseEvent): Unit = {
    // TODO perhaps change to keep clicked viewport point on cursor?
    val r = new Rectangle(e.getX, e.getY, 1, 1)
    scrollRectToVisible(r)
  }

  // Scrollable APIs
  //
  override def getPreferredSize: Dimension =
    new Dimension((rootNode.getWidth * zoomLevel).toInt, (rootNode.getHeight * zoomLevel).toInt)

  override def getPreferredScrollableViewportSize: Dimension = getPreferredSize

  override def getScrollableBlockIncrement(rectangle: Rectangle, i: Int, i1: Int): Int = 1
  override def getScrollableUnitIncrement(rectangle: Rectangle, i: Int, i1: Int): Int = 1

  override def getScrollableTracksViewportWidth: Boolean = false
  override def getScrollableTracksViewportHeight: Boolean = false
}
