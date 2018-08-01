package com.lz.mlengine

import org.apache.spark.ml.classification._
import org.apache.spark.ml.{Estimator, Model}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression._
import org.apache.spark.ml.util.MLWritable
import org.apache.spark.sql.{Dataset, SparkSession}

case class LabeledSparkFeature(id: String, features: Vector, label: Double)

class SparkTrainer[E <: Estimator[M], M <: Model[M] with MLWritable](val trainer: E)(implicit spark: SparkSession) {

  import spark.implicits._

  def fit(features: Dataset[FeatureSet], labels: Dataset[PredictionSet]): SparkModel[M] = {

    val featureToIndexMap = getFeatureToIndexMap(features)

    trainer match {
      // Classification models.
      case _: DecisionTreeClassifier | _: GBTClassifier | _: LinearSVC | _: LogisticRegression |
           _: MultilayerPerceptronClassifier | _: NaiveBayes | _: RandomForestClassifier => {
        val labelToIndexMap = getLabelToIndexMap(labels)
        val labeledVectors = getLabeledSparkFeature(features, labels, featureToIndexMap, Some(labelToIndexMap))
        val sparkModel = trainer.fit(labeledVectors)
        val indexToLabelMap = labelToIndexMap.map(_.swap)
        new SparkModel[M](sparkModel, featureToIndexMap, Some(indexToLabelMap))
      }
      // Regression models.
      case _: AFTSurvivalRegression | _: DecisionTreeRegressor | _: GBTRegressor | _: GeneralizedLinearRegression |
           _: IsotonicRegression | _: LinearRegression | _: RandomForestRegressor => {
        val labeledVectors = getLabeledSparkFeature(features, labels, featureToIndexMap, None)
        val sparkModel = trainer.fit(labeledVectors)
        new SparkModel[M](sparkModel, featureToIndexMap, None)
      }
      // Clustering models.
    }

  }

  private[mlengine] def getFeatureToIndexMap(features: Dataset[FeatureSet]): Map[String, Int] = {
    features
      .flatMap(f => f.features.toSeq.map(_._1))
      .distinct()
      .collect()
      .sorted
      .zipWithIndex
      .toMap
  }

  private[mlengine] def getLabelToIndexMap(labels: Dataset[PredictionSet]): Map[String, Int] = {
    labels
      .flatMap(l => l.predictions(0).label)
      .distinct()
      .collect()
      .sorted
      .zipWithIndex
      .toMap
  }

  private[mlengine] def getLabeledSparkFeature(features: Dataset[FeatureSet], labels: Dataset[PredictionSet],
                                               featureToIndexMap: Map[String, Int],
                                               labelToIndexMapMaybe: Option[Map[String, Int]]
                                              ): Dataset[LabeledSparkFeature] = {
    features
      .joinWith(labels, features.col("id") === labels.col("id"))
      .map(row => {
        val values = row._1.features.toSeq.map(kv => (featureToIndexMap.get(kv._1).get, kv._2)).sortBy(_._1)
        val feature = Vectors.sparse(featureToIndexMap.size, values)
        val label = labelToIndexMapMaybe match {
          case Some(labelToIndexMap) =>
            labelToIndexMap.get(row._2.predictions(0).label.get).get.toDouble
          case None =>
            row._2.predictions(0).value.get
        }
        LabeledSparkFeature(row._1.id, feature, label)
      })
  }

}
