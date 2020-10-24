package moped.reporters

sealed abstract class Position {
  pos =>
  def input: Input
  def start: Int
  def startLine: Int
  def startColumn: Int
  def end: Int
  def endLine: Int
  def endColumn: Int
  def text: String

  final def isNone: Boolean = this == NoPosition

  /** Returns true if this position encloses the other position */
  final def encloses(other: Position): Boolean =
    pos.start <= other.start && pos.end > other.end

  /**
   * Returns a formatted string of this position including filename/line/caret.
   */
  final def pretty(severity: String, message: String): String = {
    // Predef.augmentString = work around scala/bug#11125 on JDK 11
    val content = augmentString(lineContent).lines
    val sb = new StringBuilder()
    // TODO(olafur): check error when column number is huge like in auto-generated JSON
    sb.append(lineInput(severity, message)).append("\n")
    if (content.hasNext) {
      sb.append(content.next()).append("\n").append(lineCaret).append("\n")
    }
    content.foreach { line =>
      sb.append(line).append("\n")
    }
    // TODO(olafur): trim away last newline
    sb.toString()
  }

  final def lineInput(severity: String, message: String): String = {
    val out = new StringBuilder()
    if (!pos.isNone) {
      val path =
        pos.input.path match {
          case Some(path) =>
            path.toString()
          case None =>
            pos.input.filename
        }
      out.append(path).append(":").append(pos.startLine + 1)
      if (pos.startColumn > 0) {
        out.append(":").append(pos.startColumn)
      }
    }

    if (!severity.isEmpty) {
      out
        // TODO(olafur): out can be empty
        .append(" ").append(severity).append(":")
    }
    if (!message.isEmpty) {
      out.append(" ").append(message)
    }
    out.toString()
  }

  final def lineCaret: String =
    pos match {
      case NoPosition =>
        ""
      case range: RangePosition =>
        if (isEndOfFile(range)) {
          endOfFileOffset.lineCaret
        } else {
          val caret =
            if (pos.startLine == pos.endLine)
              "^" * (pos.end - pos.start + 1)
            else
              "^"
          " " * pos.startColumn + caret
        }
    }

  final def lineContent: String =
    pos match {
      case NoPosition =>
        ""
      case range: RangePosition =>
        if (isEndOfFile(range)) {
          endOfFileOffset.lineContent
        } else {
          val start = range.start - range.startColumn
          val endLine = math.min(range.input.lineCount - 1, range.endLine + 1)
          val end = math.max(start, range.input.lineToOffset(endLine) - 1)
          RangePosition(range.input, start, end).text
        }
    }

  def endOfFileOffset: RangePosition = {
    val size = input.chars.length - 1
    RangePosition(input, size, size)
  }
  private def isEndOfFile(range: RangePosition): Boolean = {
    val size = range.input.chars.length
    range.start == size && range.end == size && range.input.chars.last == '\n'
  }
}
object Position {
  def range(
      input: Input,
      startLine: Int,
      startColumn: Int,
      endLine: Int,
      endColumn: Int
  ): Position =
    if (input.isEmpty)
      NoPosition
    else {
      val start = input.lineToOffset(startLine) + startColumn
      val end = input.lineToOffset(endLine) + endColumn
      RangePosition(input, start, end)
    }
  def offset(input: Input, offset: Int): Position =
    if (offset < 0 || input.isEmpty)
      NoPosition
    else
      RangePosition(input, offset, offset)
}

case object NoPosition extends Position {
  def input = Input.none
  def start = -1
  def startLine = -1
  def startColumn = -1
  def end = -1
  def endLine = -1
  def endColumn = -1
  def text = ""
}

final case class RangePosition(input: Input, start: Int, end: Int)
    extends Position {
  def startLine: Int = input.offsetToLine(start)
  def startColumn: Int = start - input.lineToOffset(startLine)
  def endLine: Int = input.offsetToLine(end)
  def endColumn: Int = end - input.lineToOffset(endLine)
  override def text = new String(input.chars, start, end - start)
  override def toString: String = pretty("", "")
}
