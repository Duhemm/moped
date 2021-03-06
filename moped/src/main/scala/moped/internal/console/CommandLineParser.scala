package moped.internal.console

import scala.collection.immutable.Nil
import scala.collection.mutable
import scala.util.Try

import moped.internal.console.CommandLineParser._
import moped.internal.reporters.Levenshtein
import moped.json._
import moped.macros.ClassShaper
import moped.reporters._

class CommandLineParser[T](
    settings: ClassShaper[T],
    toInline: Map[String, List[ValidOption]]
) {
  private val pendingMembers = mutable.ListBuffer.empty[JsonElement]
  private val pendingArrays = mutable
    .Map
    .empty[List[String], mutable.ListBuffer[JsonElement]]
  private val errors = mutable.ListBuffer.empty[Diagnostic]
  private def flushArrays(): Unit = {
    pendingArrays.foreach { case (keys, values) =>
      pushMember(newMember(keys, JsonArray(values.toList)))
    }
    pendingArrays.clear()
  }
  private def pushMember(member: JsonMember): Unit = {
    pendingMembers += JsonObject(List(member))
  }
  def parseArgs(args: List[String]): Result[JsonObject] = {
    loop(args, NoFlag)
    flushArrays()
    Diagnostic.fromDiagnostics(errors.toList) match {
      case Some(d) =>
        ErrorResult(d)
      case None =>
        JsonElement.merge(pendingMembers) match {
          case o: JsonObject =>
            ValueResult(o)
          case e =>
            ErrorResult(Diagnostic.typeMismatch("Object", DecodingContext(e)))
        }
    }
  }
  private[this] def loop(xs: List[String], s: State): Unit = {
    (xs, s) match {
      case (Nil, NoFlag) =>
        ()
      case (Nil, Flag(settings)) =>
        settings.foreach { setting =>
          errors +=
            Diagnostic.error(
              s"the argument '--${Cases.camelToKebab(setting.shape.name)}' requires a value but none was supplied"
            )
        }
      case ("--" :: tail, NoFlag) =>
        appendValues(TrailingArgument, tail.map(JsonString(_)))
      case (head :: tail, NoFlag) =>
        val equal = head.indexOf('=')
        if (equal >= 0) { // split "--key=value" into ["--key", "value"]
          val key = head.substring(0, equal)
          val value = head.substring(equal + 1)
          loop(key :: value :: tail, NoFlag)
        } else if (head.startsWith("-")) {
          loopFlag(head, tail)
        } else {
          appendValues(PositionalArgument, List(JsonString(head)))
          loop(tail, NoFlag)
        }
      case (head :: tail, Flag(flags)) =>
        flags.foreach { setting =>
          val value: JsonElement =
            if (setting.shape.isNumber)
              Try(JsonNumber(head.toDouble)).getOrElse(JsonString(head))
            else
              JsonString(head)
          if (setting.shape.isRepeated) {
            appendValues(setting.path, List(value))
          } else {
            addMember(setting.path, value)
          }
        }
        loop(tail, NoFlag)
    }
  }

  private def loopFlag(flag: String, tail: List[String]): Unit = {
    tryFlag(flag) match {
      case Left(error) =>
        if (flag.startsWith(negatedPrefix)) {
          // Try to parse flag with "--no-" prefix removed.
          val negatedHead = "--" + flag.stripPrefix(negatedPrefix)
          tryFlag(negatedHead) match {
            case Left(_) =>
              errors += error
            case Right(Nil) =>
              loop(tail, NoFlag)
            case Right(settings) =>
              // --no prefix succeeded, default boolean value is false.
              loopSettings(tail, settings, defaultBooleanValue = false)
          }
        } else {
          errors += error
        }
      case Right(Nil) =>
        loop(tail, NoFlag)
      case Right(settings) =>
        loopSettings(tail, settings, defaultBooleanValue = true)
    }
  }

  private def tryFlag(
      kebabFlag: String
  ): Either[Diagnostic, List[ValidOption]] = {
    val camel = Cases.kebabToCamel(dash.replaceFirstIn(kebabFlag, ""))
    toInline.get(camel) match {
      case None =>
        settings.parametersFlat.find(_.isTreatInvalidFlagAsPositional) match {
          case Some(param) =>
            appendValues(PositionalArgument, List(JsonString(kebabFlag)))
            Right(Nil)
          case None =>
            Left(didYouMean(kebabFlag, camel))
        }
      case Some(settings) =>
        Right(settings)
    }
  }

  def didYouMean(kebabFlag: String, camel: String): Diagnostic = {
    val validCandidates =
      toInline
        .iterator
        .collect {
          case (key, options) if options.exists(_.isCanonical) =>
            key
        }
        .toList
    val closestCandidate = Levenshtein.closestCandidate(camel, validCandidates)
    val didYouMean =
      closestCandidate match {
        case None =>
          ""
        case Some(candidate) =>
          val kebab = Cases.camelToKebab(candidate)
          s"\n\tDid you mean '--$kebab'?"
      }
    Diagnostic.error(
      s"found argument '$kebabFlag' which wasn't expected, or isn't valid in this context.$didYouMean"
    )
  }

  def loopSettings(
      tail: List[String],
      settings: List[ValidOption],
      defaultBooleanValue: Boolean
  ): Unit = {
    require(settings.nonEmpty)
    val hasBoolean = settings.exists(_.shape.isBoolean)
    val isOnlyBoolean = settings.forall(_.shape.isBoolean)
    if (isOnlyBoolean) {
      settings.map { setting =>
        addMember(setting.path, JsonBoolean(defaultBooleanValue))
      }
      loop(tail, NoFlag)
    } else {
      if (hasBoolean) {
        val name = settings.head.shape.name
        val names = settings
          .map(s => s.path.mkString("."))
          .mkString("{", ",", "}")
        errors += Diagnostic.error(s"""
                                      |invalid usage of @Inline. The field name '$name' inlines to conflicting nested parameters $names, which mix boolean and non-boolean parameters.
                                      |You can only fix this problem by changing the source code of this command-line tool.
                                      |To fix this problem, you can try one of the following.
                                      |  1) change the types of the parameters to be only boolean or non-boolean
                                      |  2) remove the @Inline annotation for one of the nested parameters
                                      |""".stripMargin)
      } else {
        loop(tail, Flag(settings))
      }
    }
  }

  private def addMember(key: String, value: JsonElement): Unit = {
    addMember(List(key), value)
  }

  private def addMember(keys: List[String], value: JsonElement): Unit = {
    pushMember(newMember(keys, value))
  }

  private def newMember(keys: List[String], value: JsonElement): JsonMember = {
    keys match {
      case Nil =>
        throw new IllegalArgumentException("Nil")
      case head :: Nil =>
        JsonMember(JsonString(head), value)
      case head :: tail =>
        JsonMember(JsonString(head), JsonObject(List(newMember(tail, value))))
    }
  }

  def appendValues(key: String, values: List[JsonElement]): Unit = {
    appendValues(List(key), values)
  }
  def appendValues(keys: List[String], values: List[JsonElement]): Unit = {
    val buf = pendingArrays.getOrElseUpdate(keys, mutable.ListBuffer.empty)
    buf ++= values
  }
}

object CommandLineParser {
  val PositionalArgument = "moped@positional"
  val TrailingArgument = "moped@trailing"

  def parseArgs[T](
      args: List[String]
  )(implicit settings: ClassShaper[T]): Result[JsonObject] = {
    val toInline = inlinedSettings(settings)
    val parser = new CommandLineParser[T](settings, toInline)
    parser.parseArgs(args)
  }

  val negatedPrefix = "--no-"
  def isNegatedBoolean(flag: String): Boolean = flag.startsWith(negatedPrefix)

  def inlinedSettings(
      settings: ClassShaper[_]
  ): Map[String, List[ValidOption]] = {
    val buf = mutable.Map.empty[String, mutable.ListBuffer[ValidOption]]
    settings
      .allNestedParameters
      .foreach { nested =>
        val param = nested.last
        val keys = nested.map(_.name)
        val inlines = nested.iterator.takeWhile(_.isInline).size
        0.to(inlines)
          .foreach { i =>
            val name = nested.drop(i).map(_.name).mkString(".")
            if (name.nonEmpty) {
              val lst = buf.getOrElseUpdate(name, mutable.ListBuffer.empty)
              val isCanonical = i == inlines
              lst += ValidOption(name, keys, isCanonical, param)
            }
          }
      }
    buf.mapValues(_.toList).toMap
  }

  private sealed trait State
  private case class Flag(flags: List[ValidOption]) extends State {
    require(flags.nonEmpty)
  }
  private case object NoFlag extends State
  private val dash = "--?".r

}
