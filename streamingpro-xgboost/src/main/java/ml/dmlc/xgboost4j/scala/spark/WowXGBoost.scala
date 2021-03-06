package ml.dmlc.xgboost4j.scala.spark

import java.nio.file.Files


import ml.dmlc.xgboost4j.scala.{Booster, EvalTrait, ObjectiveTrait}
import org.apache.commons.logging.LogFactory
import org.apache.spark.{SparkContext, SparkParallelismTracker, TaskContext}
import org.apache.spark.rdd.RDD
import ml.dmlc.xgboost4j.java.{IRabitTracker, Rabit, XGBoostError, RabitTracker => PyRabitTracker}
import ml.dmlc.xgboost4j.scala.rabit.RabitTracker
import ml.dmlc.xgboost4j.scala.{XGBoost => SXGBoost, _}
import ml.dmlc.xgboost4j.{LabeledPoint => XGBLabeledPoint}

import scala.collection.mutable

object WowXGBoost extends Serializable {
  private val logger = LogFactory.getLog("XGBoostSpark")

  private[spark] def removeMissingValues(
                                          xgbLabelPoints: Iterator[XGBLabeledPoint],
                                          missing: Float): Iterator[XGBLabeledPoint] = {
    if (!missing.isNaN) {
      xgbLabelPoints.map { labeledPoint =>
        val indicesBuilder = new mutable.ArrayBuilder.ofInt()
        val valuesBuilder = new mutable.ArrayBuilder.ofFloat()
        for ((value, i) <- labeledPoint.values.zipWithIndex if value != missing) {
          indicesBuilder += (if (labeledPoint.indices == null) i else labeledPoint.indices(i))
          valuesBuilder += value
        }
        labeledPoint.copy(indices = indicesBuilder.result(), values = valuesBuilder.result())
      }
    } else {
      xgbLabelPoints
    }
  }

  private def fromBaseMarginsToArray(baseMargins: Iterator[Float]): Option[Array[Float]] = {
    val builder = new mutable.ArrayBuilder.ofFloat()
    var nTotal = 0
    var nUndefined = 0
    while (baseMargins.hasNext) {
      nTotal += 1
      val baseMargin = baseMargins.next()
      if (baseMargin.isNaN) {
        nUndefined += 1 // don't waste space for all-NaNs.
      } else {
        builder += baseMargin
      }
    }
    if (nUndefined == nTotal) {
      None
    } else if (nUndefined == 0) {
      Some(builder.result())
    } else {
      throw new IllegalArgumentException(
        s"Encountered a partition with $nUndefined NaN base margin values. " +
          s"If you want to specify base margin, ensure all values are non-NaN.")
    }
  }

  private[spark] def buildDistributedBoosters(
                                               data: RDD[XGBLabeledPoint],
                                               params: Map[String, Any],
                                               rabitEnv: java.util.Map[String, String],
                                               round: Int,
                                               obj: ObjectiveTrait,
                                               eval: EvalTrait,
                                               useExternalMemory: Boolean,
                                               missing: Float,
                                               prevBooster: Booster
                                             ): RDD[(Booster, Map[String, Array[Float]])] = {

    val partitionedBaseMargin = data.map(_.baseMargin)
    // to workaround the empty partitions in training dataset,
    // this might not be the best efficient implementation, see
    // (https://github.com/dmlc/xgboost/issues/1277)
    data.zipPartitions(partitionedBaseMargin) { (labeledPoints, baseMargins) =>
      if (labeledPoints.isEmpty) {
        throw new XGBoostError(
          s"detected an empty partition in the training data, partition ID:" +
            s" ${TaskContext.getPartitionId()}")
      }
      val taskId = TaskContext.getPartitionId().toString
      val cacheDirName = if (useExternalMemory) {
        val dir = Files.createTempDirectory(s"${TaskContext.get().stageId()}-cache-$taskId")
        Some(dir.toAbsolutePath.toString)
      } else {
        None
      }
      rabitEnv.put("DMLC_TASK_ID", taskId)
      Rabit.init(rabitEnv)
      val watches = Watches(params,
        removeMissingValues(labeledPoints, missing),
        fromBaseMarginsToArray(baseMargins), cacheDirName)

      try {
        val numEarlyStoppingRounds = params.get("num_early_stopping_rounds")
          .map(_.toString.toInt).getOrElse(0)
        val metrics = Array.tabulate(watches.size)(_ => Array.ofDim[Float](round))
        val booster = SXGBoost.train(watches.train, params, round,
          watches.toMap, metrics, obj, eval,
          earlyStoppingRound = numEarlyStoppingRounds, prevBooster)
        Iterator(booster -> watches.toMap.keys.zip(metrics).toMap)
      } finally {
        Rabit.shutdown()
        watches.delete()
      }
    }.cache()
  }

  private def overrideParamsAccordingToTaskCPUs(
                                                 params: Map[String, Any],
                                                 sc: SparkContext): Map[String, Any] = {
    val coresPerTask = sc.getConf.getInt("spark.task.cpus", 1)
    var overridedParams = params
    if (overridedParams.contains("nthread")) {
      val nThread = overridedParams("nthread").toString.toInt
      require(nThread <= coresPerTask,
        s"the nthread configuration ($nThread) must be no larger than " +
          s"spark.task.cpus ($coresPerTask)")
    } else {
      overridedParams = params + ("nthread" -> coresPerTask)
    }
    overridedParams
  }

  private def startTracker(nWorkers: Int, trackerConf: TrackerConf): IRabitTracker = {
    val tracker: IRabitTracker = trackerConf.trackerImpl match {
      case "scala" => new RabitTracker(nWorkers)
      case "python" => new PyRabitTracker(nWorkers)
      case _ => new PyRabitTracker(nWorkers)
    }

    require(tracker.start(trackerConf.workerConnectionTimeout), "FAULT: Failed to start tracker")
    tracker
  }

  /**
    * @return A tuple of the booster and the metrics used to build training summary
    */
  @throws(classOf[XGBoostError])
  private[spark] def trainDistributed(
                                       trainingData: RDD[XGBLabeledPoint],
                                       params: Map[String, Any],
                                       round: Int,
                                       nWorkers: Int,
                                       obj: ObjectiveTrait = null,
                                       eval: EvalTrait = null,
                                       useExternalMemory: Boolean = false,
                                       missing: Float = Float.NaN): (Booster, Map[String, Array[Float]]) = {
    if (params.contains("tree_method")) {
      require(params("tree_method") != "hist", "xgboost4j-spark does not support fast histogram" +
        " for now")
    }
    require(nWorkers > 0, "you must specify more than 0 workers")
    if (obj != null) {
      require(params.get("obj_type").isDefined, "parameter \"obj_type\" is not defined," +
        " you have to specify the objective type as classification or regression with a" +
        " customized objective function")
    }
    val trackerConf = params.get("tracker_conf") match {
      case None => TrackerConf()
      case Some(conf: TrackerConf) => conf
      case _ => throw new IllegalArgumentException("parameter \"tracker_conf\" must be an " +
        "instance of TrackerConf.")
    }
    val timeoutRequestWorkers: Long = params.get("timeout_request_workers") match {
      case None => 0L
      case Some(interval: Long) => interval
      case _ => throw new IllegalArgumentException("parameter \"timeout_request_workers\" must be" +
        " an instance of Long.")
    }
    val (checkpointPath, checkpointInterval) = CheckpointManager.extractParams(params)
    val partitionedData = repartitionForTraining(trainingData, nWorkers)

    val sc = trainingData.sparkContext
    val checkpointManager = new CheckpointManager(sc, checkpointPath)
    checkpointManager.cleanUpHigherVersions(round)

    var prevBooster = checkpointManager.loadCheckpointAsBooster
    // Train for every ${savingRound} rounds and save the partially completed booster
    checkpointManager.getCheckpointRounds(checkpointInterval, round).map {
      checkpointRound: Int =>
        val tracker = startTracker(nWorkers, trackerConf)
        try {
          val overriddenParams = overrideParamsAccordingToTaskCPUs(params, sc)
          val parallelismTracker = new SparkParallelismTracker(sc, timeoutRequestWorkers, nWorkers)
          val boostersAndMetrics = buildDistributedBoosters(partitionedData, overriddenParams,
            tracker.getWorkerEnvs, checkpointRound, obj, eval, useExternalMemory, missing,
            prevBooster)
          val sparkJobThread = new Thread() {
            override def run() {
              // force the job
              boostersAndMetrics.foreachPartition(() => _)
            }
          }
          sparkJobThread.setUncaughtExceptionHandler(tracker)
          sparkJobThread.start()
          val trackerReturnVal = parallelismTracker.execute(tracker.waitFor(0L))
          logger.info(s"Rabit returns with exit code $trackerReturnVal")
          val (booster, metrics) = postTrackerReturnProcessing(trackerReturnVal, boostersAndMetrics,
            sparkJobThread)
          if (checkpointRound < round) {
            prevBooster = booster
            checkpointManager.updateCheckpoint(prevBooster)
          }
          (booster, metrics)
        } finally {
          tracker.stop()
        }
    }.last
  }


  private[spark] def repartitionForTraining(trainingData: RDD[XGBLabeledPoint], nWorkers: Int) = {
    if (trainingData.getNumPartitions != nWorkers) {
      logger.info(s"repartitioning training set to $nWorkers partitions")
      trainingData.repartition(nWorkers)
    } else {
      trainingData
    }
  }

  private def postTrackerReturnProcessing(
                                           trackerReturnVal: Int,
                                           distributedBoostersAndMetrics: RDD[(Booster, Map[String, Array[Float]])],
                                           sparkJobThread: Thread): (Booster, Map[String, Array[Float]]) = {
    if (trackerReturnVal == 0) {
      // Copies of the final booster and the corresponding metrics
      // reside in each partition of the `distributedBoostersAndMetrics`.
      // Any of them can be used to create the model.
      val (booster, metrics) = distributedBoostersAndMetrics.first()
      distributedBoostersAndMetrics.unpersist(false)
      (booster, metrics)
    } else {
      try {
        if (sparkJobThread.isAlive) {
          sparkJobThread.interrupt()
        }
      } catch {
        case _: InterruptedException =>
          logger.info("spark job thread is interrupted")
      }
      throw new XGBoostError("XGBoostModel training failed")
    }
  }

}
