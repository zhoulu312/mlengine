package com.lz.mlengine.spark

import java.io.{File, FileInputStream, FileOutputStream, InputStream}

import com.holdenkarau.spark.testing.DatasetSuiteBase
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.{Model => SparkModel}
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.functions.monotonically_increasing_id
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.scalatest.junit.JUnitSuite

import com.lz.mlengine.core.Model
import com.lz.mlengine.spark.Converter._

case class SVMData(id: Long, label: String, features: Vector)

case class TestRawPredictionVector(id: Long, rawPrediction: Vector)

case class TestProbabilityVector(id: Long, probability: Vector)

case class TestPredictionScalar(id: Long, prediction: Double)

trait ModelTestBase extends JUnitSuite with DatasetSuiteBase {

  import spark.implicits._

  implicit val _ = spark

  val _temporaryFolder = new TemporaryFolder

  @Rule
  def temporaryFolder = _temporaryFolder


  def binaryClassificationData = {
    val path = getClass.getClassLoader.getResource("sample_binary_classification_data.txt").getFile()
    spark.read.format("libsvm").load(path).withColumn("id", monotonically_increasing_id).as[SVMData]
  }

  def multiClassificationData = {
    val path = getClass.getClassLoader.getResource("sample_multiclass_classification_data.txt").getFile()
    spark.read.format("libsvm").load(path).withColumn("id", monotonically_increasing_id).as[SVMData]
  }

  def regressionData = {
    val path = getClass.getClassLoader.getResource("sample_linear_regression_data.txt").getFile()
    spark.read.format("libsvm").load(path).withColumn("id", monotonically_increasing_id).as[SVMData]
  }

  def assertBinaryClassificationModelRawSame[M <: SparkModel[M]](data: Dataset[SVMData], sparkModel: SparkModel[M],
                                                                 model: Model) = {
    val expected = sparkModel.transform(data).select("id", "rawPrediction").as[TestRawPredictionVector]
      .map(row => (row.id, row.rawPrediction(0), row.rawPrediction(1)))
    val predictions = data.map(row => new TestRawPredictionVector(row.id, model.predictImpl(row.features)))
      .map(row => (row.id, row.rawPrediction(0), row.rawPrediction(1)))
    assertDatasetApproximateEquals(expected, predictions, 0.001)
  }

  def assertMultiClassificationModelRawSame[M <: SparkModel[M]](data: Dataset[SVMData], sparkModel: SparkModel[M],
                                                                model: Model) = {
    val expected = sparkModel.transform(data).select("id", "rawPrediction").as[TestRawPredictionVector]
      .map(row => (row.id, row.rawPrediction(0), row.rawPrediction(1), row.rawPrediction(2)))
    val predictions = data.map(row => new TestRawPredictionVector(row.id, model.predictImpl(row.features)))
      .map(row => (row.id, row.rawPrediction(0), row.rawPrediction(1), row.rawPrediction(2)))
    assertDatasetApproximateEquals(expected, predictions, 0.001)
  }

  def assertBinaryClassificationModelProbabilitySame[M <: SparkModel[M]](data: Dataset[SVMData],
                                                                         sparkModel: SparkModel[M], model: Model) = {
    val expected = sparkModel.transform(data).select("id", "probability").as[TestProbabilityVector]
      .map(row => (row.id, row.probability(0), row.probability(1)))
    val predictions = data.map(row => new TestProbabilityVector(row.id, model.predictImpl(row.features)))
      .map(row => (row.id, row.probability(0), row.probability(1)))
    assertDatasetApproximateEquals(expected, predictions, 0.001)
  }

  def assertMultiClassificationModelProbabilitySame[M <: SparkModel[M]](data: Dataset[SVMData],
                                                                        sparkModel: SparkModel[M], model: Model) = {
    val expected = sparkModel.transform(data).select("id", "probability").as[TestProbabilityVector]
      .map(row => (row.id, row.probability(0), row.probability(1), row.probability(2)))
    val predictions = data.map(row => new TestProbabilityVector(row.id, model.predictImpl(row.features)))
      .map(row => (row.id, row.probability(0), row.probability(1), row.probability(2)))
    assertDatasetApproximateEquals(expected, predictions, 0.001)
  }

  def assertRegressionModelSame[M <: SparkModel[M]](data: Dataset[SVMData], sparkModel: SparkModel[M], model: Model) = {
    val expected = sparkModel.transform(data).select("id", "prediction").as[TestPredictionScalar]
    val predictions = data.map(row => new TestPredictionScalar(row.id, model.predictImpl(row.features)(0)))
    assertDatasetApproximateEquals(expected, predictions, 0.001)
  }

  def saveAndLoadModel(model: Model, path: String, modelLoadingFun: (InputStream) => Model): Model = {
    val os = new FileOutputStream(new File(path))
    try {
      model.save(os)
    } finally {
      os.close
    }
    val is = new FileInputStream(new File(path))
    try {
      modelLoadingFun(is)
    } finally {
      is.close
    }
  }

}
