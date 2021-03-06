//--------------------------------------
//
// ResourceManagerImplTest.scala
// Since: 2013/09/04 19:06
//
//--------------------------------------

package xerial.silk.cluster.rm

import xerial.silk.util.{SilkSpec}
import xerial.silk.util.ThreadUtil.ThreadManager
import xerial.silk.TimeOut
import xerial.silk.framework.{ResourceRequest, Node, NodeResource}
import xerial.silk.cluster.Barrier

/**
 * @author Taro L. Saito
 */
class ResourceManagerImplTest extends SilkSpec {
  "ResourceManager" should {

    "durable for multiple requests" in {
      val r = new ResourceManagerImpl()

      val nr = NodeResource("hx00", 2, 100000)
      r.addResource(Node("hx00", "", -1, -1, -1, -1, nr), nr)

      val req = ResourceRequest(None, 1, None)
      val t = new ThreadManager(2)

      val barrier = new Barrier(2)

      t.submit {
        val r1 = r.acquireResource(req)
        debug(s"acquired: $r1")
        val r2 = r.acquireResource(req)
        debug(s"acquired: $r2")

        barrier.enter("prepare")
        Thread.sleep(3000)
        r.releaseResource(r1)
        info("exit")
      }

      t.submit {
        try {
          barrier.enter("prepare")
          val r3 = r.acquireResource(req)
          debug(s"acquired: $r3")
        }
        catch {
          case e:TimeOut =>
          // OK
            debug(s"Request timed out properly $e")
        }
      }

      t.join
    }

  }
}