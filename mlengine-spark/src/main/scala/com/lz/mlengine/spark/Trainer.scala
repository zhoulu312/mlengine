package com.lz.mlengine.spark

import java.nio.charset.Charset
import javax.imageio.ImageIO

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.ml.{classification => cl}
import org.apache.spark.ml.{Estimator, Model => SparkModel}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.{regression => rg}
import org.apache.spark.ml.util.MLWritable
import org.apache.spark.sql.{Dataset, SparkSession}

import com.lz.mlengine.core._
import com.lz.mlengine.spark.Converter._

case class LabeledSparkFeature(id: String, features: Vector, label: Double)

abstract class Trainer[E <: Estimator[M], M <: SparkModel[M] with MLWritable](val trainer: E,
                                                                              val featurePrefixes: Seq[String])
                                                                             (implicit spark: SparkSession) {

  import spark.implicits._

  private[mlengine] def getFeatureToIndexMap(features: Dataset[FeatureSet]): Map[String, Int] = {
    val featurePrefixSet = featurePrefixes.toSet
    features
      .flatMap(f => f.features.toSeq.map(_._1))
      .filter(name => {
        if (featurePrefixSet.isEmpty) {
          true
        } else {
          featurePrefixSet.exists(prefix => name.startsWith(prefix))
        }
      })
      .distinct
      .collect
      .sorted
      .zipWithIndex
      .toMap
  }

}

class ClassificationTrainer[E <: Estimator[M], M <: SparkModel[M] with MLWritable]
  (override val trainer: E, override val featurePrefixes: Seq[String] = Seq[String]())
  (implicit spark: SparkSession) extends Trainer[E, M](trainer, featurePrefixes) {

  import spark.implicits._

  private[mlengine] def getLabelToIndexMap(labels: Dataset[(String, String)]): Map[String, Int] = {
    labels
      .map(_._2)
      .distinct
      .collect
      .sorted
      .zipWithIndex
      .toMap
  }

  private[mlengine] def getLabeledSparkFeature(features: Dataset[FeatureSet], labels: Dataset[(String, String)],
                                               featureToIndexMap: Map[String, Int],
                                               labelToIndexMap: Map[String, Int]): Dataset[LabeledSparkFeature] = {
    features
      .joinWith(labels, features.col("id") === labels.col("_1"))
      .map { case (feature, (_, label)) => {
        val values = feature.features.toSeq.flatMap(kv => {
          featureToIndexMap.get(kv._1) match {
            case Some(index) => Seq((index, kv._2))
            case None => Seq()
          }
        }).sortBy(_._1)
        val sparkFeature = Vectors.sparse(featureToIndexMap.size, values)
        val sparkLabel = labelToIndexMap.get(label).get.toDouble
        LabeledSparkFeature(feature.id, sparkFeature, sparkLabel)
      }}
  }

  def fit(features: Dataset[FeatureSet], labels: Dataset[(String, String)]): ClassificationModel = {
    implicit val featureToIndexMap = getFeatureToIndexMap(features)
    val labelToIndexMap = getLabelToIndexMap(labels)
    val labeledVectors = getLabeledSparkFeature(features, labels, featureToIndexMap, labelToIndexMap)
    implicit val indexToLabelMap = labelToIndexMap.map(_.swap)

    trainer match {
      case _: cl.DecisionTreeClassifier => {
        trainer.fit(labeledVectors).asInstanceOf[cl.DecisionTreeClassificationModel]
      }
      case _: cl.GBTClassifier => {
        trainer.fit(labeledVectors).asInstanceOf[cl.GBTClassificationModel]
      }
      case _: cl.LinearSVC => {
        trainer.fit(labeledVectors).asInstanceOf[cl.LinearSVCModel]
      }
      case _: cl.LogisticRegression => {
        trainer.fit(labeledVectors).asInstanceOf[cl.LogisticRegressionModel]
      }
      case _: cl.RandomForestClassifier => {
        trainer.fit(labeledVectors).asInstanceOf[cl.RandomForestClassificationModel]
      }
      case _ => {
        throw new IllegalArgumentException(s"Unsupported model: ${trainer.getClass}")
      }
    }
  }

}

class RegressionTrainer[E <: Estimator[M], M <: SparkModel[M] with MLWritable]
  (override val trainer: E, override val featurePrefixes: Seq[String] = Seq[String]())
  (implicit spark: SparkSession) extends Trainer[E, M](trainer, featurePrefixes) {

  import spark.implicits._

  private[mlengine] def getLabeledSparkFeature(features: Dataset[FeatureSet], labels: Dataset[(String, Double)],
                                               featureToIndexMap: Map[String, Int]): Dataset[LabeledSparkFeature] = {
    features
      .joinWith(labels, features.col("id") === labels.col("_1"))
      .map(row => {
        val values = row._1.features.toSeq.map(kv => (featureToIndexMap.get(kv._1).get, kv._2)).sortBy(_._1)
        val feature = Vectors.sparse(featureToIndexMap.size, values)
        val label = row._2._2
        LabeledSparkFeature(row._1.id, feature, label)
      })
  }

  def fit(features: Dataset[FeatureSet], labels: Dataset[(String, Double)]): RegressionModel = {
    implicit val featureToIndexMap = getFeatureToIndexMap(features)
    val labeledVectors = getLabeledSparkFeature(features, labels, featureToIndexMap)

    trainer match {
      case _: rg.DecisionTreeRegressor => {
        trainer.fit(labeledVectors).asInstanceOf[rg.DecisionTreeRegressionModel]
      }
      case _: rg.GBTRegressor => {
        trainer.fit(labeledVectors).asInstanceOf[rg.GBTRegressionModel]
      }
      case _: rg.LinearRegression => {
        trainer.fit(labeledVectors).asInstanceOf[rg.LinearRegressionModel]
      }
      case _: rg.RandomForestRegressor => {
        trainer.fit(labeledVectors).asInstanceOf[rg.RandomForestRegressionModel]
      }
      case _ => {
        throw new IllegalArgumentException(s"Unsupported model: ${trainer.getClass}")
      }
    }
  }

}

object Trainer {

  def trainClassifier(trainer: ClassificationTrainer[_, _], features: Dataset[FeatureSet],
                      trainLabels: Dataset[(String, String)], testLabels: Dataset[(String, String)],
                      outputPath: Option[String] = None)
                     (implicit spark: SparkSession): (ClassificationModel, ClassificationMetrics) = {
    val model = trainer.fit(features, trainLabels)
    val metrics = Evaluator.evaluate(features, testLabels, model)
    if (outputPath.isDefined) {
      saveModel(model, outputPath.get)
      saveReport(metrics, outputPath.get)
    }
    (model, metrics)
  }

  def trainRegressor(trainer: RegressionTrainer[_, _], features: Dataset[FeatureSet],
                     trainLabels: Dataset[(String, Double)], testLabels: Dataset[(String, Double)],
                     outputPath: Option[String] = None)
                    (implicit spark: SparkSession): (RegressionModel, RegressionMetrics) = {
    val model = trainer.fit(features, trainLabels)
    val metrics = Evaluator.evaluate(features, testLabels, model)
    if (outputPath.isDefined) {
      saveModel(model, outputPath.get)
      saveReport(metrics, outputPath.get)
    }
    (model, metrics)
  }

  def trainMultipleClassifier(trainers: Seq[ClassificationTrainer[_, _]], features: Dataset[FeatureSet],
                              trainLabels: Dataset[(String, String)], testLabels: Dataset[(String, String)],
                              outputPath: Option[String] = None)
                             (implicit spark: SparkSession
                             ): Future[Seq[(ClassificationModel, ClassificationMetrics)]] = {
    val futures = trainers.zipWithIndex.map { case (trainer, idx) =>
      Future {
        val path = if (outputPath.isDefined) Some((new Path(outputPath.get, idx.toString)).toUri.toString) else None
        trainClassifier(trainer, features, trainLabels, testLabels, path)
      }
    }
    Future.sequence(futures).map {
      results =>
        if (outputPath.isDefined) {
          saveSummary(results.map(_._2), outputPath.get)
        }
        results
    }
  }

  def trainMultipleRegressor(trainers: Seq[RegressionTrainer[_, _]], features: Dataset[FeatureSet],
                             trainLabels: Dataset[(String, Double)], testLabels: Dataset[(String, Double)],
                             outputPath: Option[String] = None)
                            (implicit spark: SparkSession): Future[Seq[(RegressionModel, RegressionMetrics)]] = {
    val futures = trainers.zipWithIndex.map { case (trainer, idx) =>
      Future {
        val path = if (outputPath.isDefined) Some((new Path(outputPath.get, idx.toString)).toUri.toString) else None
        trainRegressor(trainer, features, trainLabels, testLabels, path)
      }
    }
    Future.sequence(futures).map {
      results =>
        if (outputPath.isDefined) {
          saveSummary(results.map(_._2), outputPath.get)
        }
        results
    }
  }

  def saveReport(metrics: ClassificationMetrics, path: String)(implicit spark: SparkSession): Unit = {
    val fs = getFileSystem(path)
    val prCurveStream = fs.create(new Path(path, "pr-curve.png"))
    val rocCurveStream = fs.create(new Path(path, "roc-curve.png"))
    val reportStream = fs.create(new Path(path, "metrics.csv"))
    try {
      val prCurve = Report.generatePrGrpah(metrics)
      ImageIO.write(prCurve, "png", prCurveStream)
      val rocCurve = Report.generateRocGrpah(metrics)
      ImageIO.write(rocCurve, "png", rocCurveStream)
      val report = Report.generateDetail(metrics)
      reportStream.write(report.toString().getBytes(Charset.forName("UTF-8")))
    } finally {
      prCurveStream.close()
      rocCurveStream.close()
      reportStream.close()
    }
  }

  def saveReport(metrics: RegressionMetrics, path: String)(implicit spark: SparkSession): Unit = {
    val fs = getFileSystem(path)
    val reportStream = fs.create(new Path(path, "metrics.csv"))
    try {
      val report = Report.generateSummary(metrics)
      reportStream.write(report.toString().getBytes(Charset.forName("UTF-8")))
    } finally {
      reportStream.close()
    }
  }

  def saveSummary(metrics :Seq[Metrics], path: String)(implicit spark: SparkSession): Unit = {
    val summary = Report.mergeReports(metrics.map(m => Report.generateSummary(m)))
    val fs = getFileSystem(path)
    val reportStream = fs.create(new Path(path, "summary.csv"))
    try {
      reportStream.write(summary.toString().getBytes(Charset.forName("UTF-8")))
    } finally {
      reportStream.close()
    }
  }

  def saveModel(model: Model, path: String)(implicit spark: SparkSession): Unit = {
    val fs = getFileSystem(path)
    val modelStream = fs.create(new Path(path, "model.data"))
    try {
      model.save(modelStream)
    } finally {
      modelStream.close()
    }
  }

  def getFileSystem(path: String)(implicit spark: SparkSession): FileSystem = {
    val hadoopConf = spark.sparkContext.hadoopConfiguration
    new Path(path).getFileSystem(hadoopConf)
  }

}