package org.apache.spark.ml.example

import org.apache.spark.SparkConf
import org.apache.spark.ml.classification.{GBMClassificationModel, GBMClassifier}
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.sql._

/**
  * spark-submit --class example.GBMClassifierExample --jars spark-gbm/target/spark-gbm-2.3.0.jar examples/target/examples-2.3.0.jar 2>log
  */
object GBMClassifierExample {

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster("local[4]")
    val ss = SparkSession.builder().config(conf).getOrCreate()

    val sc = ss.sparkContext
    // load data
    val input = "./data/agaricus.txt.train"
    val data = ss.read.format("libsvm").load(input)

    data.show(1,false)

    val train = data
    val test = data

    val modelSavePath = s"/tmp/sparkGBM/spark-modelsave-${System.nanoTime}"
    val modelCheckpointPath = s"/tmp/sparkGBM/spark-modelcheckpoint-${System.nanoTime}"

    val gbmc = new GBMClassifier
    gbmc.setBoostType("gbtree")
      .setHistogramComputationType("basic")
      .setStepSize(0.2)
      .setMaxIter(10)
      .setMaxDepth(5)
      .setMaxLeaves(1000)
      .setMaxBins(128)
      .setMinGain(0.0)
      .setSubSampleRate(0.9)
      .setColSampleRateByTree(0.9)
      .setColSampleRateByLevel(0.9)
      .setRegAlpha(0.1)
      .setRegLambda(1.0)
      .setObjectiveFunc("logistic")
      .setEvaluateFunc(Array("logloss", "auroc", "auprc", "error"))
      .setCheckpointInterval(3)
      .setEarlyStopIters(5)
      .setModelCheckpointInterval(4)
      .setModelCheckpointPath(modelCheckpointPath)
      .setZeroAsMissing(true)
      .setGreedierSearch(true)
      .setReduceParallelism(300)
      .setSeed(123L)

    /** train with validation */
    val model = gbmc.fit(train, test)

    /** model save and load */
    model.save(modelSavePath)
    val model2 = GBMClassificationModel.load(modelSavePath)

    /** load the model snapshots saved during training */
    val modelSnapshot4 = GBMClassificationModel.load(s"$modelCheckpointPath/model-4")
    println(s"modelSnapshot4 has ${modelSnapshot4.numTrees} trees")
    val modelSnapshot8 = GBMClassificationModel.load(s"$modelCheckpointPath/model-8")
    println(s"modelSnapshot8 has ${modelSnapshot8.numTrees} trees")

    /** using only 5 tree for the following feature importance computation, prediction and leaf transformation */
    model.setFirstTrees(5)

    /** feature importance */
    println(s"featureImportances of first 5 trees ${model.featureImportances}")

    /** prediction with first 5 trees */
    val predictions = model.transform(test)
    predictions.show(10)

    /** path/leaf transformation with first 5 trees, one-hot encoded */
    val leaves = model.setEnableOneHot(true).leaf(test)
    leaves.show(10)

    /** continuous training with previous model, using setInitialModelPath to set the path of initial model */
    val gbmc2 = new GBMClassifier
    gbmc2.setStepSize(0.1)
      .setMaxIter(10)
      .setMaxDepth(4)
      .setMaxLeaves(32)
      .setMinGain(0.0)
      .setSubSampleRate(0.95)
      .setColSampleRateByTree(0.95)
      .setColSampleRateByLevel(0.95)
      .setRegAlpha(0.05)
      .setRegLambda(0.5)
      .setObjectiveFunc("logistic")
      .setEvaluateFunc(Array("logloss"))
      .setCheckpointInterval(3)
      .setInitialModelPath(modelSavePath)
      .setSeed(456L)

    /** train another 10 trees, without validation */
    val model3 = gbmc2.fit(train)

    /** model3 should have 20 trees, the first 10 trees have weight=0.2, the last 10 ones have weight=0.1 */
    println(s"model3 has ${model3.numTrees} trees, with weights=${model3.weights.mkString("(", ",", ")")}")

    val evaluator = new BinaryClassificationEvaluator()
    evaluator.setLabelCol("label")
      .setRawPredictionCol("rawPrediction")

    /** auroc and auprc of model with all trees */
    evaluator.setMetricName("areaUnderROC")
    val auroc = evaluator.evaluate(model3.transform(test))

    evaluator.setMetricName("areaUnderPR")
    val auprc = evaluator.evaluate(model3.transform(test))

    println(s"test data: AUROC=$auroc, AUPRC=$auprc")

    sc.stop()

  }
}