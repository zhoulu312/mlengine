package com.lz.mlengine.spark

import java.nio.file.{Files, Paths}

import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.Await
import scala.concurrent.duration._

import com.holdenkarau.spark.testing.DatasetSuiteBase
import org.apache.spark.ml.{classification => cl}
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.{regression => rg}
import org.junit.Assert._
import org.junit.{Rule, Test}
import org.junit.rules.TemporaryFolder
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitSuite

import com.lz.mlengine.core.FeatureSet
import com.lz.mlengine.core.classification.LogisticRegressionModel
import com.lz.mlengine.core.regression.LinearRegressionModel

class ClassificationTrainerTest extends FlatSpec with Matchers with DatasetSuiteBase {

  import spark.implicits._

  "getFeatureToIndexMap" should "generate a map from feature name to index" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val featureToIndexMap = getTrainer().getFeatureToIndexMap(features)

    featureToIndexMap should be(Map("feature1" -> 0, "feature2" -> 1, "feature3" -> 2))
  }

  "getFeatureToIndexMap" should "filter feature based on feature prefixes" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1-1" -> 1.0, "feature2-1" -> 1.0)),
      FeatureSet("2", MutableMap("feature2-1" -> 1.0, "feature3-1" -> 1.0)),
      FeatureSet("3", MutableMap("feature1-1" -> 1.0, "feature3-1" -> 1.0))
    ).toDS

    val featureToIndexMap = getTrainer(Seq("feature1", "feature2")).getFeatureToIndexMap(features)

    featureToIndexMap should be(Map("feature1-1" -> 0, "feature2-1" -> 1))
  }

  "getLabelToIndexMap" should "generate a map from label to index" in {
    val labels = Seq(
      ("1", "positive"),
      ("2", "positive"),
      ("3", "negative")
    ).toDS

    val t = getTrainer()
    val labelToIndexMap = t.getLabelToIndexMap(labels)

    labelToIndexMap should be(Map("negative" -> 0, "positive" -> 1))
  }

  "getLabeledSparkFeature" should "map string valued prediction to label with label to index map" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val labels = Seq(("1", "positive"), ("2", "positive"), ("3", "negative")).toDS

    val featureToIndexMap = Map("feature1" -> 0, "feature2" -> 1, "feature3" -> 2)
    val labelToIndexMap = Map("negative" -> 0, "positive" -> 1)

    val labeledFeatures = getTrainer().getLabeledSparkFeature(features, labels, featureToIndexMap, labelToIndexMap)

    val expected = Seq(
      LabeledSparkFeature("1", Vectors.sparse(3, Seq((0, 1.0), (1, 1.0))), 1.0),
      LabeledSparkFeature("2", Vectors.sparse(3, Seq((1, 1.0), (2, 1.0))), 1.0),
      LabeledSparkFeature("3", Vectors.sparse(3, Seq((0, 1.0), (2, 1.0))), 0.0)
    ).toDS

    assertDatasetEquals(expected, labeledFeatures)
  }

  "getLabeledSparkFeature" should "skip feature not in feature to index map" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val labels = Seq(("1", "positive"), ("2", "positive"), ("3", "negative")).toDS

    val featureToIndexMap = Map("feature1" -> 0, "feature2" -> 1)
    val labelToIndexMap = Map("negative" -> 0, "positive" -> 1)

    val labeledFeatures = getTrainer().getLabeledSparkFeature(features, labels, featureToIndexMap, labelToIndexMap)

    val expected = Seq(
      LabeledSparkFeature("1", Vectors.sparse(2, Seq((0, 1.0), (1, 1.0))), 1.0),
      LabeledSparkFeature("2", Vectors.sparse(2, Seq((1, 1.0))), 1.0),
      LabeledSparkFeature("3", Vectors.sparse(2, Seq((0, 1.0))), 0.0)
    ).toDS

    assertDatasetEquals(expected, labeledFeatures)
  }

  "fit" should "train classification model with index to label map" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val labels = Seq(("1", "positive"), ("2", "positive"), ("3", "negative")).toDS

    val trainer = getTrainer()
    val model = trainer.fit(features, labels).asInstanceOf[LogisticRegressionModel]

    model.coefficients.rows should be (1)
    model.coefficients.cols should be (3)
    model.intercept.size should be (1)
    model.featureToIndexMap should be (Map("feature1" -> 0, "feature2" -> 1, "feature3" -> 2))
    model.indexToLabelMap should be (Map(0 -> "negative", 1 -> "positive"))
  }

  def getTrainer(featurePrefixes: Seq[String] = Seq[String]()
                ): ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel] = {
    new ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel](
      new cl.LogisticRegression(), featurePrefixes)(spark)
  }

}

class RegressionTrainerTest extends FlatSpec with Matchers with DatasetSuiteBase {

  import spark.implicits._

  "getLabeledSparkFeature" should "map double valued prediction to label without label to index map" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val labels = Seq(("1", 0.8), ("2", 0.5), ("3", 0.2)).toDS

    val featureToIndexMap = Map("feature1" -> 0, "feature2" -> 1, "feature3" -> 2)

    val labeledFeatures = getTrainer()
      .getLabeledSparkFeature(features, labels, featureToIndexMap)

    val expected = Seq(
      LabeledSparkFeature("1", Vectors.sparse(3, Seq((0, 1.0), (1, 1.0))), 0.8),
      LabeledSparkFeature("2", Vectors.sparse(3, Seq((1, 1.0), (2, 1.0))), 0.5),
      LabeledSparkFeature("3", Vectors.sparse(3, Seq((0, 1.0), (2, 1.0))), 0.2)
    ).toDS

    assertDatasetEquals(expected, labeledFeatures)
  }

  "fit" should "train regression model without index to label map" in {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS

    val labels = Seq(("1", 0.8), ("2", 0.5), ("3", 0.2)).toDS

    val trainer = getTrainer()
    val model = trainer.fit(features, labels).asInstanceOf[LinearRegressionModel]

    model.coefficients.size should be (3)
    model.featureToIndexMap should be(Map("feature1" -> 0, "feature2" -> 1, "feature3" -> 2))
  }

  def getTrainer(): RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel] = {
    new RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel](new rg.LinearRegression())(spark)
  }

}

class TrainerObjectTest extends JUnitSuite with Matchers with DatasetSuiteBase {

  import spark.implicits._

  val _temporaryFolder = new TemporaryFolder

  @Rule
  def temporaryFolder = _temporaryFolder

  @Test def testTrainAndEvaluateClassification = {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("4", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("5", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("6", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS
    val trainLabels = Seq(("1", "a"), ("2", "b"), ("3", "a")).toDS
    val testLabels = Seq(("4", "a"), ("5", "b"), ("6", "a")).toDS
    val trainer = new ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel](
      new cl.LogisticRegression())(spark)
    val path = temporaryFolder.getRoot.toString + "/classification"

    val (model, metrics) = Trainer.trainClassifier(trainer, features, trainLabels, testLabels, Some(path)) (spark)

    assertEquals(3, model.asInstanceOf[LogisticRegressionModel].coefficients.size)
    assertEquals(2, metrics.confusionMatrices.size)
    assertTrue(Files.exists(Paths.get(path + "/model.data")))
    assertTrue(Files.exists(Paths.get(path + "/metrics.csv")))
    assertTrue(Files.exists(Paths.get(path + "/pr-curve.png")))
    assertTrue(Files.exists(Paths.get(path + "/roc-curve.png")))
  }

  @Test def testTrainAndEvaluateRegression = {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("4", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("5", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("6", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS
    val trainLabels = Seq(("1", 0.8), ("2", 0.5), ("3", 0.2)).toDS
    val testLabels = Seq(("4", 0.8), ("5", 0.5), ("6", 0.2)).toDS
    val trainer = new RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel](
      new rg.LinearRegression())(spark)
    val path = temporaryFolder.getRoot.toString + "/regression"

    val (model, metrics) = Trainer.trainRegressor(trainer, features, trainLabels, testLabels, Some(path))(spark)

    assertEquals(3, model.asInstanceOf[LinearRegressionModel].coefficients.size)
    assertTrue(metrics.explainedVariance >= -0.00001)
    assertTrue(metrics.meanAbsoluteError >= -0.00001)
    assertTrue(metrics.meanSquaredError >= -0.00001)
    assertTrue(metrics.r2 >= -0.00001)
    assertTrue(Files.exists(Paths.get(path + "/model.data")))
    assertTrue(Files.exists(Paths.get(path + "/metrics.csv")))
  }

  @Test def testTrainAndEvaluateMultipleClassification = {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("4", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("5", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("6", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS
    val trainLabels = Seq(("1", "a"), ("2", "b"), ("3", "a")).toDS
    val testLabels = Seq(("4", "a"), ("5", "b"), ("6", "a")).toDS
    val trainers = Seq(
      new ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel](
        new cl.LogisticRegression().setRegParam(0.1))(spark),
      new ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel](
        new cl.LogisticRegression().setRegParam(0.01))(spark),
      new ClassificationTrainer[cl.LogisticRegression, cl.LogisticRegressionModel](
        new cl.LogisticRegression().setRegParam(0.001))(spark)
    )
    val path = temporaryFolder.getRoot.toString + "/classifications"

    val future = Trainer.trainMultipleClassifier(trainers, features, trainLabels, testLabels, Some(path))(spark)
    val results = Await.result(future, 100.second)
    results.zipWithIndex.foreach { case ((model, metrics), idx) =>
      assertEquals(3, model.asInstanceOf[LogisticRegressionModel].coefficients.size)
      assertEquals(2, metrics.confusionMatrices.size)
      assertTrue(Files.exists(Paths.get(path + s"/$idx/model.data")))
      assertTrue(Files.exists(Paths.get(path + s"/$idx/metrics.csv")))
      assertTrue(Files.exists(Paths.get(path + s"/$idx/pr-curve.png")))
      assertTrue(Files.exists(Paths.get(path + s"/$idx/roc-curve.png")))
    }
    assertTrue(Files.exists(Paths.get(path + "/summary.csv")))
  }

  @Test def testTrainAndEvaluateMultipleRegression = {
    val features = Seq(
      FeatureSet("1", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("2", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("3", MutableMap("feature1" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("4", MutableMap("feature1" -> 1.0, "feature2" -> 1.0)),
      FeatureSet("5", MutableMap("feature2" -> 1.0, "feature3" -> 1.0)),
      FeatureSet("6", MutableMap("feature1" -> 1.0, "feature3" -> 1.0))
    ).toDS
    val trainLabels = Seq(("1", 0.8), ("2", 0.5), ("3", 0.2)).toDS
    val testLabels = Seq(("4", 0.8), ("5", 0.5), ("6", 0.2)).toDS
    val trainers = Seq(
      new RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel](
        new rg.LinearRegression().setRegParam(0.1))(spark),
      new RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel](
        new rg.LinearRegression().setRegParam(0.01))(spark),
      new RegressionTrainer[rg.LinearRegression, rg.LinearRegressionModel](
        new rg.LinearRegression().setRegParam(0.001))(spark)
    )
    val path = temporaryFolder.getRoot.toString + "/regressions"

    val future = Trainer.trainMultipleRegressor(trainers, features, trainLabels, testLabels, Some(path))(spark)
    val results = Await.result(future, 100.second)
    results.zipWithIndex.foreach { case ((model, metrics), idx) =>
      assertEquals(3, model.asInstanceOf[LinearRegressionModel].coefficients.size)
      assertTrue(metrics.explainedVariance >= -0.00001)
      assertTrue(metrics.meanAbsoluteError >= -0.00001)
      assertTrue(metrics.meanSquaredError >= -0.00001)
      assertTrue(metrics.r2 >= -0.00001)
      assertTrue(Files.exists(Paths.get(path + s"/$idx/model.data")))
      assertTrue(Files.exists(Paths.get(path + s"/$idx/metrics.csv")))
    }
    assertTrue(Files.exists(Paths.get(path + "/summary.csv")))
  }

}