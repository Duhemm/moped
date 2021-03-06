package tests.progressbars

import java.io.PrintWriter

import moped.json.JsonArray
import moped.json.JsonNumber
import moped.progressbars.InteractiveProgressBar
import moped.progressbars.ProgressRenderer
import moped.progressbars.ProgressStep
import munit.FunSuite
import org.typelevel.paiges.Doc

class InteractiveProgressBarSuite extends FunSuite {
  test("basic".ignore) {
    val renderer =
      new ProgressRenderer() {
        var i = 0
        override def renderStart(): Doc = {
          Doc.text("static start")
        }
        override def renderStop(): Doc = {
          Doc.text("static stop")
        }
        override def renderStep(): ProgressStep = {
          i += 1
          val N = math.max(i, 10)
          val progress = ("#" * i).padTo(N, ' ')
          val hash = Seq.fill(i)(Doc.char('#'))
          val open = Doc.text("[")
          val close = Doc.text(s"${i.toString().padTo(2, ' ')}/$N]")
          val bar = Doc.fill(Doc.lineOrEmpty, hash).tightBracketBy(open, close)
          val list = JsonArray(1.to(i).map(i => JsonNumber(i)).toList).toDoc
          ProgressStep(
            static = Doc.empty, //Doc.text(i.toString()),
            dynamic = bar + Doc.line + list
          )
        }
      }
    val p =
      new InteractiveProgressBar(
        new PrintWriter(System.out),
        renderer
        // intervalDuration = Duration.ofMillis(100)
      )
    p.start()
    Thread.sleep(3000)
    p.stop()
  }

}
