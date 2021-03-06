package xerial.silk.cluster

import xerial.silk._
import xerial.core.log.Logger
import java.util.UUID
import scala.util.Random
import scala.collection.parallel.ParSeq
import xerial.core.util.DataUnit
import xerial.silk.cluster.closure.ClosureCleaner
import xerial.silk.framework._
import xerial.silk.core._
import xerial.silk.core.Partitioner
import xerial.silk.core.ScatterSeq
import xerial.silk.framework.StageInfo
import xerial.silk.core.ShuffleReduceSortOp
import xerial.silk.core.ReadLine
import xerial.silk.core.GroupByOp
import xerial.silk.core.FlatMapOp
import xerial.silk.core.FlatMapSeqOp
import xerial.silk.framework.StageFinished
import xerial.silk.core.ShuffleOp
import xerial.silk.core.SortOp
import xerial.silk.core.ConcatOp
import xerial.silk.core.RawSeq
import xerial.silk.framework.StageStarted
import xerial.silk.core.ReduceOp
import xerial.silk.core.FilterOp
import xerial.silk.framework.StageAborted
import xerial.silk.core.SizeOp
import xerial.silk.core.SamplingOp
import xerial.silk.core.MapOp
import xerial.silk.framework.Slice


trait DefaultExecutor extends ExecutorComponent {
  self: Weaver
    with LocalTaskManagerComponent
    with ClassBoxComponent
    with SliceStorageComponent =>

  type Executor = ExecutorImpl
  def executor: Executor = new ExecutorImpl

  class ExecutorImpl extends ExecutorAPI with ExecutorBase {}
}


/**
 * Executor receives a silk, optimize a plan, then submit evaluation tasks to the local task manager
 */
trait ExecutorComponent {
  self: Weaver
    with LocalTaskManagerComponent
    with ClassBoxComponent
    with SliceStorageComponent =>

  type Executor <: ExecutorAPI with ExecutorBase
  def executor: Executor

  trait ExecutorBase extends FunctionWrap with Logger {

    def eval[A](op: Silk[A]) {
      // Clean closures
      val silk = ClosureCleaner.clean(op)
      for (future <- getSlices(silk))
        future.get
    }

    def run[A](session: SilkSession, op: Silk[A]): Seq[A] = {

      // Clean closures
      val silk = ClosureCleaner.clean(op)

      val dataSeq: ParSeq[Seq[A]] = for {
        future <- getSlices(silk)
      }
      yield {
        val slice = future.get
        //debug(s"Fetch slice: $slice in $silk")
        sliceStorage.retrieve(silk.id, slice).asInstanceOf[Seq[A]]
      }

      val result = dataSeq.seq.flatten
      result
    }

    /**
     * Get Stage information
     * @param op
     * @tparam A
     * @return
     */
    def getStage[A](op: Silk[A]): StageInfo = {
      sliceStorage.getStageInfo(op.id).map {
        si =>
          si.status match {
            case StageStarted(ts) => si
            case StageFinished(ts) => si
            case StageAborted(cause, ts) => // TODO restart
              SilkException.error(s"stage of [${op.idPrefix}] has been aborted: $cause")
          }
      } getOrElse {
        // Start a stage for evaluating the Silk
        executor.startStage(op)
      }
    }

    private def startStage[A, Out](op: Silk[A], in: Silk[A], f: Seq[_] => Out) = {
      val inputStage = getStage(in)
      val N = inputStage.numSlices
      val stageInfo = StageInfo(0, N, StageStarted(System.currentTimeMillis()))
      sliceStorage.setStageInfo(op.id, stageInfo)
      for (i <- (0 until N).par) {
        // Get an input slice
        val inputSlice = sliceStorage.get(in.id, i).get
        // Send the slice processing task to a node close to the inputSlice location
        localTaskManager.submit(EvalSliceTask(s"eval slice ${inputSlice} of ${op}", UUID.randomUUID, classBox.classBoxID, op.id, in.id, inputSlice, f.toF1, Seq(inputSlice.nodeName)))
      }
      stageInfo
    }


    private def startReduceStage[A](op: Silk[A], in: Silk[A], reducer: Seq[_] => Any, aggregator: Seq[_] => Any) = {
      val inputStage = getStage(in)
      val N = inputStage.numSlices

      // The outer reduce task produces only 1 slice
      val stageInfo = StageInfo(0, 1, StageStarted(System.currentTimeMillis()))
      sliceStorage.setStageInfo(op.id, stageInfo)

      // Evaluate reduce at each slice
      val subStageID = SilkUtil.newUUID
      for (i <- (0 until N).par) {
        // Get an input slice
        val inputSlice = sliceStorage.get(in.id, i).get
        // Locality-aware job submission
        localTaskManager.submit(ReduceTask(s"reduce each ${op}", UUID.randomUUID, classBox.classBoxID, subStageID, in.id, Seq(i), i, reducer, aggregator, Seq(inputSlice.nodeName)))
      }

      // Determine the number of reducers to use. The default is 1/3 of the number of the input slices
      val R = ((N + (3 - 1)) / 3.0).toInt
      val W = (N + (R - 1)) / R
      info(s"num reducers:$R, W:$W")

      // Reduce the previous sub stage using R reducers
      val aggregateStageID = SilkUtil.newUUID
      for ((sliceRange, i) <- (0 until N).sliding(W, W).zipWithIndex) {
        val sliceIndexSet = sliceRange.toIndexedSeq
        localTaskManager.submit(ReduceTask(s"reduce aggregate ${op}", UUID.randomUUID, classBox.classBoxID, aggregateStageID, subStageID, sliceIndexSet, i, aggregator, aggregator, Seq.empty))
      }

      // The final aggregate task
      localTaskManager.submit(ReduceTask(s"reduce final of ${op}", UUID.randomUUID, classBox.classBoxID, op.id, aggregateStageID, (0 until R).toIndexedSeq, 0, aggregator, aggregator, Seq.empty))

      stageInfo
    }


    private def startShuffleStage[A, K](shuffleOp: ShuffleOp[A]) = {
      val inputStage = getStage(shuffleOp.in)
      val N = inputStage.numSlices
      val P = shuffleOp.partitioner.numPartitions
      val stageInfo = StageInfo(P, N, StageStarted(System.currentTimeMillis()))
      sliceStorage.setStageInfo(shuffleOp.id, stageInfo)

      // Materialize the partitioner
      shuffleOp.partitioner.materialize

      // Shuffle each input slice
      for (i <- (0 until N).par) {
        val inputSlice = sliceStorage.get(shuffleOp.in.id, i).get
        localTaskManager.submit(ShuffleTask(s"shuffle ${shuffleOp}", UUID.randomUUID, classBox.classBoxID, shuffleOp.id, shuffleOp.in.id, inputSlice, shuffleOp.partitioner, Seq(inputSlice.nodeName)))
      }
      stageInfo
    }

    def startStage[A](op: Silk[A]): StageInfo = {
      info(s"Start stage: $op")
      try {
        val id = op.id
        op match {
          case RawSeq(id, fc, in) =>
            val stageInfo = StageInfo(-1, 1, StageStarted(System.currentTimeMillis()))
            sliceStorage.setStageInfo(id, stageInfo)
            localTaskManager.submit(ScatterTask("scatter data", UUID.randomUUID, classBox.classBoxID, id, in, 1))
            stageInfo
          case ScatterSeq(id, fc, in, numNodes) =>
            // Set SliceInfo first to tell the subsequent tasks how many splits exists
            val stageInfo = StageInfo(-1, numNodes, StageStarted(System.currentTimeMillis()))
            sliceStorage.setStageInfo(id, stageInfo)
            localTaskManager.submit(ScatterTask("scatter data", UUID.randomUUID, classBox.classBoxID, id, in, numNodes))
            stageInfo
          case m@MapOp(id, fc, in, f) =>
            val f1 = f.toF1
            startStage(op, in, {
              _.map(f1)
            })
          case fo@FlatMapOp(id, fc, in, f) =>
            val f1 = f.toF1
            startStage(fo, in, {
              _.map(f1)
            })
          case fs@FlatMapSeqOp(id, fc, in, f) =>
            val f1 = f.toFmap
            startStage(fs, in, {
              _.flatMap(f1)
            })
          case ff@FilterOp(id, fc, in, f) =>
            val fl = f.toFilter
            startStage(op, in, {
              _.filter(fl)
            })
          case ReduceOp(id, fc, in, f) =>
            val fr = f.asInstanceOf[(Any, Any) => Any]
            startReduceStage(op, in, {
              _.reduce(fr)
            }, {
              _.reduce(fr)
            })
          case cc@ConcatOp(id, fc, in) =>
            SilkException.NA
          //startStage(op, in, { f1(_).asInstanceOf[Seq[Seq[_]]].flatten(_.asInstanceOf[Seq[_]]) })
          case SizeOp(id, fc, in) =>
            //startReduceStage(op, in, { _.size.toLong }, { sizes:Seq[Long] => sizes.sum }.asInstanceOf[Seq[_]=>Any] )
            val inputStage = getStage(in)
            val N = inputStage.numSlices
            val stageInfo = StageInfo(0, 1, StageStarted(System.currentTimeMillis()))
            sliceStorage.setStageInfo(id, stageInfo)
            localTaskManager.submit(CountTask(s"count ${op}", UUID.randomUUID, classBox.classBoxID, op.id, in.id, N))
            stageInfo
          case so@SortOp(id, fc, in, ord, partitioner) =>
            val shuffler = ShuffleOp(SilkUtil.newUUIDOf(classOf[ShuffleOp[_]], fc, in), fc, in, partitioner.asInstanceOf[Partitioner[A]])
            val shuffleReducer = ShuffleReduceSortOp(id, fc, shuffler, ord.asInstanceOf[Ordering[A]])
            startStage(shuffleReducer)
          case sp@SamplingOp(id, fc, in, proportion) =>
            startStage(op, in, {
              data: Seq[_] =>
              // Sampling
                val indexedData = data.toIndexedSeq
                val N = data.size
                val m = math.max((N.toDouble * proportion).toInt, 1)
                //println(f"sample size: $m%,d/$N%,d ($proportion%.2f)")
                val r = new Random
                if (N > 0)
                  (for (i <- 0 until m) yield indexedData(r.nextInt(N))).toIndexedSeq
                else
                  Seq.empty
            })
          case ShuffleReduceSortOp(id, fc, shuffleIn, ord) =>
            val inputStage = getStage(shuffleIn)
            val N = inputStage.numSlices
            val P = inputStage.numKeys
            info(s"shuffle reduce: N:$N, P:$P")
            val stageInfo = StageInfo(0, P, StageStarted(System.currentTimeMillis))
            sliceStorage.setStageInfo(op.id, stageInfo)
            for (p <- 0 until P) {
              localTaskManager.submit(ShuffleReduceSortTask(s"${op}", UUID.randomUUID, classBox.classBoxID, id, shuffleIn.id, p, N, ord.asInstanceOf[Ordering[_]], Seq.empty))
            }
            stageInfo
          case so@ShuffleOp(id, fc, in, partitioner) =>
            startShuffleStage(so)
          //          case cmd @ CommandOp(id, fc, sc, args, resource) =>
          //            val inputs = cmd.inputs
          //            val inputStages = inputs.map(getStage(_))
          //

          case GroupByOp(id, fc, in, probe) =>
            // TODO

            StageInfo(0, 0, StageAborted("NA", System.currentTimeMillis()))
          case ReadLine(id, fc, file) =>
            // Determine the number of the resulting slices
            val fileSize = file.length
            import DataUnit._
            val blockSize = 64 * MB
            val numBlocks = ((fileSize + blockSize - 1L) / blockSize).toInt
            val stageInfo = StageInfo(0, numBlocks, StageStarted(System.currentTimeMillis()))
            sliceStorage.setStageInfo(op.id, stageInfo)
            for (i <- 0 until numBlocks) {
              localTaskManager.submit(ReadLineTask(s"($i)${op}", UUID.randomUUID, file, i * blockSize, blockSize, classBox.classBoxID, id, i))
            }
            stageInfo
          case other =>
            SilkException.error(s"unknown op:$other")
        }
      }
      catch {
        case e: Exception =>
          warn(s"aborted evaluation of [${op.idPrefix}]")
          error(e)
          val aborted = StageInfo(-1, -1, StageAborted(e.getMessage, System.currentTimeMillis()))
          sliceStorage.setStageInfo(op.id, aborted)
          aborted
      }
    }


    def getSlices[A](op: Silk[A]): ParSeq[SilkFuture[Slice]] = {
      debug(s"getSlices: $op")

      val si = getStage(op)
      if (si.isFailed)
        SilkException.error(s"failed: ${si}")
      else
        for (i <- (0 until si.numSlices).par) yield sliceStorage.get(op.id, i)
    }

  }
}






