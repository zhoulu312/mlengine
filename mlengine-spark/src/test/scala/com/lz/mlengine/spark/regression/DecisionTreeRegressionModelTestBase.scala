package com.lz.mlengine.spark.regression

import com.lz.mlengine.core.regression.DecisionTreeRegressionModel
import com.lz.mlengine.spark.{Converter, ModelTestBase}
import org.apache.spark.ml.{regression => rg}
import org.junit.Test

class DecisionTreeRegressionModelTestBase extends ModelTestBase {

  @Test def testRegression() = {
    val sparkModel = getTrainer.fit(regressionData)
    val model = Converter.convert(sparkModel)(Map[String, Int]())

    val path = s"${temporaryFolder.getRoot.getPath}/decision_tree_regression"
    val modelLoaded = saveAndLoadModel(model, path, DecisionTreeRegressionModel.load)

    assertRegressionModelSame[rg.DecisionTreeRegressionModel](regressionData, sparkModel, modelLoaded)
  }

  def getTrainer = {
    new rg.DecisionTreeRegressor()
      .setMaxDepth(4)
  }

}
