//--------------------------------------
//
// SortTest.scala
// Since: 2013/07/26 10:33 AM
//
//--------------------------------------

package xerial.silk.example

import xerial.silk.cluster._
import xerial.silk.weaver.example.ExampleMain

/**
 * @author Taro L. Saito
 */
class SortTestMultiJvm1 extends Cluster3Spec {
  "send sort program" in {
    start {
      service =>

    }
  }
}
class SortTestMultiJvm2 extends Cluster3Spec {

  "send sort program" in {
    start {
      service =>

    }
  }

}

class SortTestMultiJvm3 extends Cluster3UserSpec {

  "send sort program" in {
    start {
      zkAddr =>
        try {
          val ex = new ExampleMain(Some(zkAddr))
          ex.objectSort(N = 64 * 1024, M = 2, R = 2)
        }
        catch {
          case e: Exception => error(e)
        }
    }
  }

}