//--------------------------------------
//
// RemoteCommandTest.scala
// Since: 2013/04/11 1:50 PM
//
//--------------------------------------

package xerial.silk.cluster


object A {
  val f1 : Function1[Int, Float] = (i:Int) => i.toFloat

  def F1(i:Int) = i.toFloat

}

/**
 * @author Taro L. Saito
 */
class RemoteCommandTestMultiJvm1 extends Cluster2Spec {

  import xerial.silk.cluster.SilkCluster._

  "start" in {
    start { client =>
      implicit val weaver = client
      var v = 1024
      for(h <- client.hosts) {
        at(h) {
          println(s"hello $v")
        }
        v += 1
      }
      Thread.sleep(3000
      )
    }
  }
}

class RemoteCommandTestMultiJvm2 extends Cluster2Spec {
  "start" in {
    start { weaver =>  }
  }
}
