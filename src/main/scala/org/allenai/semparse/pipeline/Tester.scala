package org.allenai.semparse.pipeline

import java.io.FileWriter
import java.io.File

import scala.collection.mutable

import com.jayantkrish.jklol.lisp.ConsValue

import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper
import com.mattg.pipeline.Step

import org.json4s._
import org.json4s.native.JsonMethods.parse

// This class performs inference over a test set for a particular experiment configuration.
class Tester(
  params: JValue,
  fileUtil: FileUtil = new FileUtil
) extends Step(Some(params), fileUtil) {
  implicit val formats = DefaultFormats

  // Parameters we take.
  val poolDepth = JsonHelper.extractWithDefault(params, "pool depth", 100)
  val queryFile = (params \ "test query file").extract[String]
  val dataName = (params \ "data name").extract[String]
  val ranking = JsonHelper.extractOptionWithDefault(params, "ranking", Seq("query", "predicate"), "query")
  val modelType = JsonHelper.extractOption(params, "model type", Seq("baseline", "distributional", "formal", "combined"))
  val ensembledEvaluation = JsonHelper.extractWithDefault(params, "ensembled evaluation", false)

  // Original training data file, which we process to get a set of entity names.
  val trainingDataFile = (params \ "training data file").extract[String]

  // These are code files that are common to all models, and just specify the base lisp
  // environment.
  val baseEnvFile = "src/main/lisp/environment.lisp"
  val uschemaEnvFile = "src/main/lisp/uschema_environment.lisp"

  // These are code files, specifying the model we're using.
  val lispModelFiles = if (ensembledEvaluation) {
    if (modelType == "baseline") throw new IllegalStateException("You can't ensemble the baseline...")
    Seq("src/main/lisp/model_baseline", s"src/main/lisp/model_${modelType}.lisp")
  } else {
    Seq(s"src/main/lisp/model_${modelType}.lisp")
  }
  val evalLispFile = modelType match {
    case "baseline" => "eval_baseline.lisp"
    case _ => if (ensembledEvaluation) "eval_ensemble.lisp" else "eval_uschema.lisp"
  }

  val handwrittenLispFiles = Seq(baseEnvFile, uschemaEnvFile) ++ lispModelFiles ++ Seq(evalLispFile)

  // This one is a (hand-written) parameter file that will be passed to lisp code.
  val sfeSpecFile = s"src/main/resources/sfe_spec.json"

  // These are data files, produced by TrainingDataProcessor.
  val entityFile = s"data/${dataName}/entities.lisp"
  val wordsFile = s"data/${dataName}/words.lisp"

  val dataFiles = Seq(entityFile, wordsFile)

  // TODO(matt): might make sense to just grab this path directly from Trainer.
  val serializedModelFile = s"output/$dataName/$modelType/$ranking/model.ser"
  val baselineModelFile = s"output/$dataName/baseline/model.lisp"

  val baseInputFiles = dataFiles ++ handwrittenLispFiles
  val baseExtraArgs = Seq(sfeSpecFile, dataName)

  // Most of the model lisp files assume there is a serialized parameters object passed in as the
  // first extra argument.  The baseline, instead, has a lisp file as its "serialized model", so
  // we have to handle these two cases differently.
  val (inputFiles, extraArgs) = modelType match {
    case "baseline" => (baselineModelFile +: baseInputFiles, baseExtraArgs)
    case other => ensembledEvaluation match {
      case true => (baselineModelFile +: baseInputFiles, baseExtraArgs :+ serializedModelFile)
      case false => (baseInputFiles, baseExtraArgs :+ serializedModelFile)
    }
  }

  val outputFile = {
    val ensemble = if (ensembledEvaluation) "ensemble" else "uschema"
    modelType match {
      // ACK!  I need to make this more general...  The dataset should not be just "large" and
      // "small"
      case "baseline" => s"results/${dataName}/baseline/output.txt"
      case other => s"results/${dataName}/${modelType}/${ranking}/${ensemble}/output.txt"
    }
  }

  // At this point we're finally ready to override the Step methods.  To do this, we first create
  // the Step objects we depend on.
  val processor = new TrainingDataProcessor(params \ "training data", fileUtil)
  val trainer = new Trainer(params \ "trainer", fileUtil)

  override def paramFile = outputFile.replace("output.txt", "params.json")
  override def name = "Model tester"
  override def inputs =
    handwrittenLispFiles.map((_, None)).toSet ++
    Set((sfeSpecFile, None), (trainingDataFile, None), (queryFile, None)) ++
    dataFiles.map((_, Some(processor))).toSet ++
    Set((serializedModelFile, Some(trainer)))
  override def outputs = Set(
    outputFile
  )


  // These query formats are how we interface from the JSON query object that we get to the lisp
  // environment (which holds the model) that we're evaluating.
  val catQueryFormat = "(expression-eval (quote (get-predicate-marginals %s (find-related-entities (list %s) (list %s)))))"

  // NOTE: the test data currently does not have any queries that use this, so this does not get
  // used, and very likely would crash if it did get used (just because a few things have changed,
  // and this hasn't been tested).
  val relQueryFormat = "(expression-eval (quote (get-relation-marginals %s entity-tuple-array)))"

  // To make the results file more human-readable, we show entity names along with MIDs.  This just
  // reads a mapping from mid to entity names from the input training file.  And it's in the Tester
  // object instead of the class because we really just need to do this once, and all Tester
  // objects can reuse this map.
  lazy val entityNames = {
    println(s"Loading entity names from file $trainingDataFile ...")
    val midNames = new mutable.HashMap[String, mutable.HashSet[String]]
    for (line <- fileUtil.getLineIterator(trainingDataFile)) {
      val fields = line.split("\t")
      val mids = fields(0).split(" ")
      val names = fields(1).trim().split("\" \"")
      for ((mid, name) <- mids.zip(names)) {
        midNames.getOrElseUpdate(mid, new mutable.HashSet[String]).add(name.replace("\"", ""))
      }
    }
    println("Done reading entity name file")
    midNames.par.mapValues(_.toSeq.sorted.map(n => "\"" + n + "\"").mkString(" ")).seq.toMap
  }

  def _runStep() {
    val env = new Environment(inputFiles, extraArgs, true)
    val writer = fileUtil.getFileWriter(outputFile)

    // The test file is a JSON object, which is itself a list of objects containing the query
    // information.
    val wholeJson = parse(fileUtil.readLinesFromFile(queryFile).mkString("\n"))
    for (json <- wholeJson.extract[Seq[JValue]]) {
      val sentence = (json \ "sentence").extract[String]
      println(s"Scoring queries for sentence: $sentence")
      val queries = (json \ "queries").extract[Seq[JValue]]
      for (query <- queries) {
        val queryExpression = (query \ "queryExpression").extract[String]
        println(s"Query: $queryExpression")
        val midsInQuery = (query \ "midsInQuery").extract[Seq[String]]
        val midRelationsInQuery = (query \ "midRelationsInQuery").extract[Seq[JValue]]
        val isRelationQuery = (query \ "isRelationQuery") match {
          case JInt(i) => i == 1
          case _ => false
        }
        val expressionToEvaluate = if (isRelationQuery) {
          // This doesn't happen in the current test data, and so I'm not sure this works right.
          // For example, I don't think (get-relation-marginals ...) is even defined.
          println("RELATION QUERY!")
          relQueryFormat.format(queryExpression)
        } else {
          val midStr = midsInQuery.map(m => "\"" + m + "\"").mkString(" ")
          val midRelationStr = midRelationsInQuery.map(jval => {
            val list = jval.extract[Seq[JValue]]
            val word = list(0).extract[String]
            val arg = list(1).extract[String]
            val isSource = list(2).extract[Boolean]
            val isSourceStr = if (isSource) "#t" else "#f"
            s"(list $word $arg $isSourceStr)"
          }).mkString(" ")
          catQueryFormat.format(queryExpression, midStr, midRelationStr)
        }

        // Now run the expression through the evaluation code and parse the result.
        println("Evaluating the expression")
        val result = env.evaluateSExpression(expressionToEvaluate).getValue()
        val entityScoreObjects = result.asInstanceOf[Array[Object]]
        println(s"Done evaluating, ranking ${entityScoreObjects.length} results")
        val entityScores = entityScoreObjects.map(entityScoreObject => {
          val cons = entityScoreObject.asInstanceOf[ConsValue]
          val list = ConsValue.consListToList(cons, classOf[Object])
          val score = list.get(0).asInstanceOf[Double].toDouble
          val entity = list.get(1).asInstanceOf[String]
          (score, entity)
        }).toSeq.sortBy(-_._1).take(poolDepth)

        // Finally, compute statistics (like average precision) and output to a result file.
        writer.write(sentence)
        writer.write("\n")
        writer.write(queryExpression)
        writer.write("\n")
        for ((score, entity) <- entityScores) {
          val names = entityNames.getOrElse(entity, "NO ENTITY NAME!")
          if (score >= 0.0) {
            writer.write(s"$score $entity $names\n")
          }
        }
        writer.write("\n")
      }
    }
    writer.close()
  }
}
