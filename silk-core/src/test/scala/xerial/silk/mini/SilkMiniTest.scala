//--------------------------------------
//
// SilkMiniTest.scala
// Since: 2013/05/17 12:36 PM
//
//--------------------------------------

package xerial.silk.mini

import xerial.silk.util.SilkSpec
import xerial.silk.MacroUtil

object SilkMiniTest {
}

/**
 * @author Taro L. Saito
 */
class SilkMiniTest extends SilkSpec {
  import SilkMiniTest._

  "SilkMini" should {

    "construct program" in {
      val sc = new SilkContext()
      val A = sc.newSilk(Seq("x", "y"))
      val B = sc.newSilk(Seq(1, 2, 3))
      val m = for(a <- A; b <- B) yield (a, b)

      val fm = m.asInstanceOf[FlatMapOp[_, _]]
      import scala.reflect.runtime.{universe=>ru}
      debug(ru.showRaw(fm.fe))




      //debug(s"eval: ${m.eval(sc)}")
      //debug(s"sc:\n$sc")
    }

  }
}