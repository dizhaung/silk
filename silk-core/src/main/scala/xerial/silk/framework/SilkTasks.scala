//--------------------------------------
//
// SilkTasks.scala
// Since: 2013/08/29 3:11 PM
//
//--------------------------------------

package xerial.silk.framework
import java.util.UUID
import xerial.core.log.Logger
import xerial.silk.core.SilkSerializer
import xerial.silk.{MissingOp, Partitioner, Silk}
import xerial.core.io.IOUtil
import java.net.URL
import xerial.core.util.DataUnit
import scala.language.existentials


trait TaskRequest extends IDUtil {

  def id: UUID

  /**
   * ID of the ClassBox in which execute this task
   * @return
   */
  def classBoxID: UUID

  /**
   * Preferred locations (node names) to execute this task
   * @return
   */
  def locality: Seq[String]


  def description : String
  def execute(localClient:LocalClient)
}

object TaskRequest extends Logger {

  import SilkSerializer._

  private[silk] def deserializeClosure(ser:Array[Byte]) = {
    trace(s"deserializing the closure")
    val closure = deserializeObj[AnyRef](ser)
    trace(s"Deserialized the closure: ${closure.getClass}")
    closure
  }

}


trait Tasks extends IDUtil {

  import SilkSerializer._

  type Task <: TaskRequest

  implicit class RichTaskStatus(status: TaskStatus) {
    def serialize = serializeObj(status)
  }
  implicit class RichTask(task: Task) {
    def serialize = serializeObj(task)
  }

  implicit class TaskDeserializer(b: Array[Byte]) {
    def asTaskStatus: TaskStatus = deserializeObj[TaskStatus](b)
    def asTask: Task = deserializeObj[Task](b)
  }
}

import TaskRequest._

/**
 * Message object for actor
 * @param id
 * @param serializedClosure
 * @param locality
 */
case class TaskRequestF0(id: UUID, classBoxID: UUID, serializedClosure: Array[Byte], locality: Seq[String]) extends TaskRequest {
  override def toString = s"TaskRequestF0(${id.prefix}, locality:${locality.mkString(", ")})"
  def description = "F0"

  def execute(localClient:LocalClient) {
    // Find apply[A](v:A) method.
    // Function0 class of Scala contains apply(v:Object) method, so we avoid it by checking the presence of parameter types.
    val closure = deserializeClosure(serializedClosure)
    val cl = closure.getClass
    cl.getMethods.filter(mt => mt.getName == "apply" && mt.getParameterTypes.length == 0).headOption match {
      case Some(applyMt) =>
        // Run the task in this node
        applyMt.invoke(closure)
      case _ =>
        throw MissingOp(s"missing apply method in $cl")
    }
  }

}


case class TaskRequestF1(description:String, id: UUID, classBoxID: UUID, serializedClosure: Array[Byte], locality: Seq[String]) extends TaskRequest {
  override def toString = s"[${id.prefix}] $description, locality:${locality.mkString(", ")})"

  def execute(localClient:LocalClient) {
    val closure = deserializeClosure(serializedClosure)
    val cl = closure.getClass
    cl.getMethods.filter(mt => mt.getName == "apply" && mt.getParameterTypes.length == 1).headOption match {
      case Some(applyMt) =>
        applyMt.invoke(closure, localClient)
      case _ =>
        throw MissingOp(s"missing apply(x) method in $cl")
    }
  }
}

case class DownloadTask(id:UUID, classBoxID:UUID, resultID:UUID, dataAddress:URL, splitID:Int, locality:Seq[String]) extends TaskRequest with Logger {
  override def toString = s"DownloadTask(${id.prefix}, ${resultID.prefix}, ${dataAddress})"

  def description = "Download task"

  def execute(localClient:LocalClient) {
    try {
      val slice = Slice(localClient.currentNodeName, -1, splitID)
      IOUtil.readFully(dataAddress.openStream) {
        data => info(s"Received the data $dataAddress, size:${DataUnit.toHumanReadableFormat(data.size)}")
          localClient.sliceStorage.putRaw(resultID, splitID, slice, data)
      }
    }
    catch {
      case e:Exception =>
        localClient.sliceStorage.poke(resultID, splitID)
        throw e
    }
  }
}


case class EvalSliceTask(description:String, id: UUID, classBoxID:UUID, opid:UUID, inid: UUID, inputSlice: Slice, f: Seq[_] => Any, locality:Seq[String]) extends TaskRequest {

  def execute(localClient:LocalClient) {
    try {
      val si = inputSlice.index
      // TODO: Error handling when slice is not found in the storage
      val data = localClient.sliceStorage.retrieve(inid, inputSlice)
      val slice = Slice(localClient.currentNodeName, -1, si)

      val result = f(data) match {
        case seq: Seq[_] => seq
        case silk: Silk[_] =>
          // recursively evaluate (for flatMap)
          val nestedResult = for (future <- localClient.executor.getSlices(silk)) yield {
            val nestedSlice = future.get
            localClient.sliceStorage.retrieve(silk.id, nestedSlice)
          }
          nestedResult.seq.flatten
      }
      localClient.sliceStorage.put(opid, si, slice, result)
      // TODO If all slices are evaluated, mark StageFinished
    }
    catch {
      case e: Throwable =>
        localClient.sliceStorage.poke(opid, inputSlice.index)
        throw e
    }
  }
}

case class ReduceTask(description:String, id:UUID, classBoxID:UUID, opid: UUID, inid: UUID, inputSliceIndexes: Seq[Int], outputSliceIndex: Int, reducer: Seq[_] => Any, aggregator: Seq[_] => Any, locality:Seq[String]) extends TaskRequest with Logger {

  def execute(localClient:LocalClient) {
    try {
      debug(s"eval reduce: input slice indexes(${inputSliceIndexes.mkString(", ")}), output slice index:$outputSliceIndex")
      val reduced = for (si <- inputSliceIndexes.par) yield {
        val slice = localClient.sliceStorage.get(inid, si).get
        val data = localClient.sliceStorage.retrieve(inid, slice)
        reducer(data)
      }
      val aggregated = aggregator(reduced.seq)

      val sl = Slice(localClient.currentNodeName, -1, outputSliceIndex)
      localClient.sliceStorage.put(opid, outputSliceIndex, sl, IndexedSeq(aggregated))
      // TODO If all slices are evaluated, mark StageFinished
    }
    catch {
      case e: Throwable =>
        localClient.sliceStorage.poke(opid, outputSliceIndex)
        throw e
    }
  }
}

case class ShuffleTask(description:String, id:UUID, classBoxID:UUID, opid: UUID, inid: UUID, inputSlice: Slice, partitioner: Partitioner[_], locality:Seq[String]) extends TaskRequest {
  def execute(localClient:LocalClient) {
    try {
      val si = inputSlice.index
      // TODO: Error handling when slice is not found in the storage
      val data = localClient.sliceStorage.retrieve(inid, inputSlice)
      val pp = partitioner.asInstanceOf[Partitioner[Any]]
      for ((p, lst) <- data.groupBy(pp.partition(_))) {
        val slice = Slice(localClient.currentNodeName, p, si)
        localClient.sliceStorage.putSlice(opid, p, si, slice, lst)
      }
      // TODO If all slices are evaluated, mark StageFinished
    }
    catch {
      case e: Throwable =>
        // TODO notify all waiters
        localClient.sliceStorage.poke(opid, inputSlice.index)
        throw e
    }

  }
}

case class ShuffleReduceTask(description:String, id:UUID, classBoxID:UUID, opid: UUID, inid: UUID, keyIndex: Int, numInputSlices: Int, ord: Ordering[_], locality:Seq[String]) extends TaskRequest {

  def execute(localClient:LocalClient) {
    try {
      val input = for (i <- (0 until numInputSlices).par) yield {
        val inputSlice = localClient.sliceStorage.getSlice(inid, keyIndex, i).get
        val data = localClient.sliceStorage.retrieve(inid, inputSlice)
        data
      }
      val result = input.flatten.seq.sorted(ord.asInstanceOf[Ordering[Any]])
      localClient.sliceStorage.put(opid, keyIndex, Slice(localClient.currentNodeName, -1, keyIndex), result)
    }
    catch {
      case e: Throwable =>
        localClient.sliceStorage.poke(opid, 0)
        throw e
    }
  }
}
