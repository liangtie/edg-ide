package edg_ide.dse

import edg.EdgirUtils.SimpleLibraryPath
import edg.compiler.{Compiler, BooleanValue, ExprValue, FloatValue, IntValue, PartialCompile, RangeValue, TextValue, ArrayValue}
import edg.util.Errorable
import edgir.ref.ref
import edg.wir.{DesignPath, Refinements}
import edg_ide.util.ExceptionNotifyImplicits.{ExceptBoolean, ExceptErrorable, ExceptOption, ExceptSeq}
import edg_ide.util.IterableExtensions.IterableExtension
import edg_ide.util.{ExceptionNotifyException, exceptable, requireExcept}

import scala.collection.{SeqMap, mutable}


object DseConfigElement {
  def valueToString(value: Any): String = value match {
    case value: ref.LibraryPath => value.toSimpleString
    case value: ExprValue => value.toStringValue
    case Some(value) => valueToString(value) // drop the "Some" for simplicity
    case value => value.toString
  }

  def configMapToString(configMap: SeqMap[DseConfigElement, Any]): String = {
    configMap.map { case (config, value) =>
      f"${config.configToString} -> ${valueToString(value)}"
    }.mkString(", ")
  }
}


// Abstract base class for a design space search configuration element - some independent
// parameter to scan through, eg "all refinements of superclass" or "try these parameter values
//
// Must be serializable so configs can be saved and persist across IDE restarts
sealed trait DseConfigElement { self: Serializable =>
  def getPartialCompile: PartialCompile

  def configToString: String  // short human-friendly string describing this configuration, excluding values
}


// Abstract trait for a config that is static, that is, the search space does not depend on a compilation run.
sealed trait DseStaticConfig extends DseConfigElement { self: Serializable =>
}


// DSE element that generates into a set of refinements with no dynamic dependencies
sealed trait DseRefinementElement[+ValueType] extends DseStaticConfig { self: Serializable =>
  // Returns a list of possibilities, as both a raw value and a refinement
  def getValues: Seq[(ValueType, Refinements)]
}


// DSE element that is associated with a single path
sealed trait DseInstanceRefinementElement[+ValueType] extends DseRefinementElement[ValueType] { self: Serializable =>
  val path: DesignPath
}


object DseParameterSearch {
  // Splits a list of ranges, ignoring commas within parens
  def splitRange(str: String): Seq[String] = {
    val builder = new mutable.StringBuilder()
    val comps = mutable.ListBuffer[String]()
    var inParens: Boolean = false
    str.foreach { char =>
      if (inParens) {
        builder += char
        if (char == ')') {
          inParens = false
        }
      } else {
        if (char == ',') {
          comps.append(builder.toString())
          builder.clear()
        } else {
          builder += char
          if (char == '(') {
            inParens = true
          }
        }
      }
    }
    comps.append(builder.toString())
    comps.toSeq
  }

  // Splits a list of strings, ignoring commas within quotes
  def splitString(str: String): Errorable[Seq[String]] = exceptable {
    val builder = new mutable.StringBuilder()
    val comps = mutable.ListBuffer[String]()
    var inEscape: Boolean = false
    var inQuotes: Boolean = false
    var mustEnd: Boolean = false
    str.foreach { char =>
      if (inEscape) {
        (char == '"' || char == '\\').exceptFalse(s"bad escaped character $char")
        builder += char
        inEscape = false
      } else if (char == '\\') {
        inEscape = true
      } else if (char == '"') {
        if (inQuotes) {
          mustEnd = true
        } else {
          builder.isEmpty.exceptFalse("unexpected open quote, may only be at start of element or escaped")
        }
        inQuotes = !inQuotes
      } else if (char == ',' && !inQuotes) {
        comps.append(builder.toString())
        builder.clear()
        mustEnd = false
      } else {
        mustEnd.exceptTrue("unexpected close quote, may only be at end of element or escaped")
        builder += char
      }
    }
    inQuotes.exceptTrue("missing end quote")
    inEscape.exceptTrue("missing escaped character")
    comps.append(builder.toString())
    comps.toSeq
  }

  protected lazy val rangeParseRegex = raw"^\s*\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)\s*$$".r
}


// Tries all values for some parameter
case class DseParameterSearch(path: DesignPath, values: Seq[ExprValue])
    extends DseInstanceRefinementElement[ExprValue] with Serializable {
  override def toString = f"${this.getClass.getSimpleName}($path, ${values.map(_.toStringValue).mkString(",")})"
  override def configToString: String = f"Param($path)"

  override def getPartialCompile: PartialCompile = {
    PartialCompile(params=Seq(path))
  }

  override def getValues: Seq[(ExprValue, Refinements)] = values.map { value =>
    (value, Refinements(instanceValues=Map(path -> value)))
  }

  // Returns the values as a string, that will parse back with valuesStringToConfig.
  // This contains special-case code to handle the TextValue case
  def valuesToString(): String = {
    values.map {
      case TextValue(str) =>
        val escaped = str.replace("\\", "\\\\").replace("\"", "\\\"")
        if (escaped.contains(',')) {
          f"\"$escaped\""
        } else {
          escaped
        }
      case value => value.toStringValue
    }.mkString(",")
  }

  // Parses a string specification of values into a new DseParameterSearch (of the same path and type).
  // The existing object is required to determine the path and value type.
  // May fail with an error message that can be propagated back to the user.
  def valuesStringToConfig(str: String): Errorable[DseParameterSearch] = exceptable {
    val valueClass = values.map(_.getClass).allSameValue.exceptNone("internal error, inconsistent values")

    val newValues = valueClass match {
      case v if v == classOf[BooleanValue] =>
        str.split(',').toSeq.zipWithIndex.map { case (str, index) =>
          BooleanValue(str.strip().toBooleanOption.exceptNone(f"invalid value ${index + 1} '$str': not an bool"))
        }
      case v if v == classOf[IntValue] =>
        str.split(',').toSeq.zipWithIndex.map { case (str, index) =>
          IntValue(str.strip().toIntOption.exceptNone(f"invalid value ${index + 1} '$str': not an int"))
        }
      case v if v == classOf[FloatValue] =>
        str.split(',').toSeq.zipWithIndex.map { case (str, index) =>
          FloatValue(str.strip().toFloatOption.exceptNone(f"invalid value ${index + 1} '$str': not a float"))
        }
      case v if v == classOf[TextValue] =>
        DseParameterSearch.splitString(str).exceptError.map {
          TextValue
        }
      case v if v == classOf[RangeValue] =>
        DseParameterSearch.splitRange(str).zipWithIndex.map { case (str: String, index) =>
          val patMatch = DseParameterSearch.rangeParseRegex.findAllMatchIn(str).toSeq
              .onlyExcept(f"invalid value ${index + 1} '$str': not a range: bad format")
          requireExcept(patMatch.groupCount == 2, f"invalid value ${index + 1} '$str': not a range: bad format")
          val min = patMatch.group(1).toFloatOption
              .exceptNone(f"invalid value ${index + 1} '$str': not a range: invalid min")
          val max = patMatch.group(2).toFloatOption
              .exceptNone(f"invalid value ${index + 1} '$str': not a range: invalid max")
          requireExcept(min <= max, f"invalid value ${index + 1}: '$str': lower > upper")
          RangeValue(min, max)
        }
      case v =>
        throw ExceptionNotifyException(f"unknown type of value $v")
    }
    requireExcept(newValues.nonEmpty, "no values specified")
    DseParameterSearch(path, newValues)
  }
}


// Tries all subclasses for some block
case class DseSubclassSearch(path: DesignPath, subclasses: Seq[ref.LibraryPath])
    extends DseInstanceRefinementElement[ref.LibraryPath] with Serializable {
  override def toString = f"${this.getClass.getSimpleName}($path, ${subclasses.map(_.toSimpleString).mkString(", ")})"
  override def configToString: String = f"Subclass($path)"

  override def getPartialCompile: PartialCompile = {
    PartialCompile(blocks = Seq(path))
  }

  override def getValues: Seq[(ref.LibraryPath, Refinements)] = subclasses.map { value =>
    (value, Refinements(instanceRefinements=Map(path -> value)))
  }
}


// Search config where the search space is generated from a compiled design.
// This does not provide additional refinements to the generating compilation,
// but the search space afterwards may add refinements.
// partial_compile here does not apply to the generating compilation,
// only to the inner search loop.
trait DseDerivedConfig extends DseConfigElement { self: Serializable =>
  def configFromDesign(compiledDesign: Compiler): Errorable[DseRefinementElement[Any]]
}


// Search config of all matching parts from a test compile run
case class DseDerivedPartSearch(path: DesignPath) extends DseDerivedConfig with Serializable {
  def configToString: String = f"Parts($path)"

  override def getPartialCompile: PartialCompile = {
    PartialCompile(params=Seq(path + "part"))
  }

  override def configFromDesign(compiledDesign: Compiler): Errorable[DseParameterSearch] = {
    val matchingPartsPath = path.asIndirect + "matching_parts"
    compiledDesign.getParamValue(matchingPartsPath) match {
      case Some(ArrayValue.ExtractText(values)) =>
        Errorable.Success(DseParameterSearch(path + "part", values.map(TextValue)))
      case Some(ArrayValue.Empty(_)) => Errorable.Error(f"no matching parts: $matchingPartsPath")
      case Some(value) => Errorable.Error(f"invalid matching parts: $matchingPartsPath = ${value.toStringValue}")
      case None => Errorable.Error(f"matching parts unavailable: $matchingPartsPath")
    }
  }
}
