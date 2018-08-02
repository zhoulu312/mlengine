package com.lz.mlengine

import org.apache.spark.ml.classification._
import org.apache.spark.ml.clustering.{GaussianMixture, GaussianMixtureModel, KMeans, KMeansModel}
import org.apache.spark.ml.regression._
import org.apache.spark.sql.{Dataset, SparkSession}

object SparkMLPipeline {

  // Classification.
  val DECISION_TREE = "DecisionTreeClassifier"
  val GBT_CLASSIFIER = "GBTClassifier"
  val LINEAR_SVC = "LinearSVC"
  val LOGISTIC_REGRESSION = "LogisticRegression"
  val MULTILAYER_PERCEPTRON = "MultilayerPerceptronClassifier"
  val NAIVE_BAYES = "NaiveBayes"
  val RANDOM_FOREST_CLASSIFIER = "RandomForestClassifier"

  // Regression.
  val DECISION_TREE_REGRESSOR = "DecisionTreeRegressor"
  val GBT_REGRESSOR = "GBTRegressor"
  val GENERALIZED_LINEAR_REGRESSION = "GeneralizedLinearRegression"
  val ISOTONIC_REGRESSION = "IsotonicRegression"
  val LINEAR_REGRESSION = "LinearRegression"
  val RANDOM_FOREST_REGRESSOR = "RandomForestRegressor"

  // Clustering.
  val K_MEANS = "KMeans"
  val GAUSSIAN_MIXTURE = "GaussianMixture"

  val CLASSIFICATION_MODELS = Seq(
    DECISION_TREE, GBT_CLASSIFIER, LINEAR_SVC, LOGISTIC_REGRESSION, NAIVE_BAYES, RANDOM_FOREST_CLASSIFIER
  )
  val REGRESSION_MODELS = Seq(
    DECISION_TREE_REGRESSOR, GBT_REGRESSOR, GENERALIZED_LINEAR_REGRESSION, ISOTONIC_REGRESSION,
    LINEAR_REGRESSION, RANDOM_FOREST_REGRESSOR
  )
  val CLUSTERING_MODELS = Seq(
    K_MEANS, GAUSSIAN_MIXTURE
  )

  val MODE_TRAIN = "train"
  val MODE_PREDICT = "predict"

  def main(args: Array[String]): Unit = {
    if (args.length < 4) {
      println(
        "Wrong number of args! Correct args: <mode> <model type> <model path> <feature path> [<label | prediction path>]"
      )
      return
    }
    val mode = args(0)
    val modelType = args(1)
    val modelPath = args(2)
    val featurePath = args(3)
    val predictionPath = if (args.length == 4) args(4) else ""
    val labelPath = if (args.length == 4) args(4) else ""

    implicit val spark = SparkSession
      .builder()
      .appName("Spark ML Pipeline")
      .getOrCreate()

    mode match {
      case MODE_TRAIN => train(modelType, modelPath, featurePath, labelPath)
      case MODE_PREDICT => predict(modelType, modelPath, featurePath, predictionPath)
    }
  }

  def loadFeatures(path: String)(implicit spark: SparkSession): Dataset[FeatureSet] = {
    import spark.implicits._
    spark.read.schema(FeatureSet.schema).json(path).as[FeatureSet]
  }

  def loadClassificationLabels(path: String)(implicit spark: SparkSession): Dataset[PredictionSet] = {
    import spark.implicits._
    spark.read.csv(path).map(l => PredictionSet(l.getString(0), Seq(Prediction(Some(l.getString(1)), None))))
  }

  def loadRegressionLabels(path: String)(implicit spark: SparkSession): Dataset[PredictionSet] = {
    import spark.implicits._
    spark.read.csv(path).map(l => PredictionSet(l.getString(0), Seq(Prediction(None, Some(l.getString(1).toDouble)))))
  }

  def loadLabels(modelName: String, labelPath: String)(implicit spark: SparkSession): Option[Dataset[PredictionSet]] = {
    if (CLASSIFICATION_MODELS.contains(modelName)) {
      Some(loadClassificationLabels(labelPath))
    } else if (REGRESSION_MODELS.contains(modelName)) {
      Some(loadRegressionLabels(labelPath))
    } else if (CLUSTERING_MODELS.contains(modelName)) {
      None
    } else {
      throw new IllegalArgumentException(s"Unsupported model: ${modelName}")
    }
  }

  def getTrainer(modelName: String)(implicit spark: SparkSession):SparkTrainer[_, _] = {
    modelName match {
      // Classification models.
      case DECISION_TREE =>
        new SparkTrainer[DecisionTreeClassifier, DecisionTreeClassificationModel](new DecisionTreeClassifier())
      case GBT_CLASSIFIER =>
        new SparkTrainer[GBTClassifier, GBTClassificationModel](new GBTClassifier())
      case LINEAR_SVC =>
        new SparkTrainer[LinearSVC, LinearSVCModel](new LinearSVC())
      case LOGISTIC_REGRESSION =>
        new SparkTrainer[LogisticRegression, LogisticRegressionModel](new LogisticRegression())
      case MULTILAYER_PERCEPTRON =>
        new SparkTrainer[MultilayerPerceptronClassifier, MultilayerPerceptronClassificationModel](
          new MultilayerPerceptronClassifier())
      case NAIVE_BAYES =>
        new SparkTrainer[NaiveBayes, NaiveBayesModel](new NaiveBayes())
      case RANDOM_FOREST_CLASSIFIER =>
        new SparkTrainer[RandomForestClassifier, RandomForestClassificationModel](new RandomForestClassifier())

      // Regression models.
      case DECISION_TREE_REGRESSOR =>
        new SparkTrainer[DecisionTreeRegressor, DecisionTreeRegressionModel](new DecisionTreeRegressor())
      case GBT_REGRESSOR =>
        new SparkTrainer[GBTRegressor, GBTRegressionModel](new GBTRegressor())
      case GENERALIZED_LINEAR_REGRESSION =>
        new SparkTrainer[GeneralizedLinearRegression, GeneralizedLinearRegressionModel](
          new GeneralizedLinearRegression())
      case ISOTONIC_REGRESSION =>
        new SparkTrainer[IsotonicRegression, IsotonicRegressionModel](new IsotonicRegression())
      case LINEAR_REGRESSION =>
        new SparkTrainer[LinearRegression, LinearRegressionModel](new LinearRegression())
      case RANDOM_FOREST_REGRESSOR =>
        new SparkTrainer[RandomForestRegressor, RandomForestRegressionModel](new RandomForestRegressor())

      // Clustering models.
      case K_MEANS =>
        new SparkTrainer[KMeans, KMeansModel](new KMeans())
      case GAUSSIAN_MIXTURE =>
        new SparkTrainer[GaussianMixture, GaussianMixtureModel](new GaussianMixture())

      case _ => throw new IllegalArgumentException(s"Unsupported model: ${modelName}")
    }
  }

  def getModel(modelName: String, modelPath: String)(implicit spark: SparkSession):SparkModel[_] = {
    modelName match {
      // Classification models.
      case DECISION_TREE => SparkLoader.decisionTreeClassificationModel(modelPath)
      case GBT_CLASSIFIER => SparkLoader.gBTClassificationModel(modelPath)
      case LINEAR_SVC => SparkLoader.linearSVCModel(modelPath)
      case LOGISTIC_REGRESSION => SparkLoader.logisticRegressionModel(modelPath)
      case MULTILAYER_PERCEPTRON => SparkLoader.multilayerPerceptronClassificationModel(modelPath)
      case NAIVE_BAYES => SparkLoader.naiveBayesModel(modelPath)
      case RANDOM_FOREST_CLASSIFIER => SparkLoader.randomForestClassificationModel(modelPath)

      // Regression models.
      case DECISION_TREE_REGRESSOR => SparkLoader.decisionTreeRegressorModel(modelPath)
      case GBT_REGRESSOR => SparkLoader.gBTRegressionModel(modelPath)
      case GENERALIZED_LINEAR_REGRESSION => SparkLoader.generalizedLinearRegressionModel(modelPath)
      case ISOTONIC_REGRESSION => SparkLoader.isotonicRegressionModel(modelPath)
      case LINEAR_REGRESSION => SparkLoader.linearRegressionModel(modelPath)
      case RANDOM_FOREST_REGRESSOR => SparkLoader.randomForestRegressionModel(modelPath)

      // Clustering models.
      case K_MEANS => SparkLoader.kMeansModel(modelPath)
      case GAUSSIAN_MIXTURE => SparkLoader.gaussianMixtureModel(modelPath)

      case _ => throw new IllegalArgumentException(s"Unsupported model: ${modelName}")
    }
  }

  def train(modelName: String, modelPath: String, featurePath: String, labelPath: String)
           (implicit spark: SparkSession): Unit = {
    val trainer = getTrainer(modelName)
    val features = loadFeatures(featurePath)
    val labels = loadLabels(modelName, labelPath)
    trainer.fit(features, labels).save(modelPath)
  }

  def predict(modelName: String, modelPath: String, featurePath: String, predictionPath: String)
             (implicit spark: SparkSession): Unit = {
    val model = getModel(modelName, modelPath)
    val features = loadFeatures(featurePath)
    model.predict(features).write.format("json").save(predictionPath)
  }

}
