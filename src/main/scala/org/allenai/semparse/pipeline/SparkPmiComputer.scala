package org.allenai.semparse.pipeline

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext

import com.mattg.util.FileUtil
import com.mattg.util.JsonHelper
import com.mattg.pipeline.Step

import org.json4s._

class SparkPmiComputer(
  params: JValue,
  fileUtil: FileUtil = new FileUtil
) extends Step(Some(params), fileUtil) {
  implicit val formats = DefaultFormats

  val dataName = (params \ "data name").extract[String]
  val outDir = s"data/${dataName}/"
  val runningLocally = JsonHelper.extractWithDefault(params, "run locally", true)
  val midOrPair = JsonHelper.extractOption(params, "mid or pair", Seq("mid", "mid_pair"))

  // If we've seen a MID too many times, it blows up the word-feature computation.  The US, God,
  // and TV all show up more than 12k times in the large dataset.  I think we can get a good enough
  // PMI computation without these really frequent MIDs.  These parameters throw out a few
  // very-expensive instances.
  val maxWordCount = JsonHelper.extractWithDefault(params, "max word count", 1000)
  val maxFeatureCount = JsonHelper.extractWithDefault(params, "max feature count", 50000)

  // This parameter, on the other hand, filters out a large number of infrequently seen features.
  val minFeatureCountDefault = midOrPair match { case "mid" => 2000; case "mid_pair" => 100 }
  val minFeatureCount = JsonHelper.extractWithDefault(params, "min feature count", minFeatureCountDefault)

  // How many features should we keep per word?
  val featuresPerWord = JsonHelper.extractWithDefault(params, "features per word", 100)

  val useSquaredPmi = JsonHelper.extractWithDefault(params, "use squared pmi", false)

  // TODO(matt): maybe this should be a parameter...?  But this one is purely computational, and
  // changing it does not change the output, so I don't really want it to get saved in the param
  // file...
  val numPartitions = 2000

  val matrixFile = if (runningLocally) {
    s"$outDir/pre_filtered_${midOrPair}_features.tsv"
  } else {
    (params \ "matrix file").extract[String]  // some S3 URI
  }

  val midWordFile = if (runningLocally) {
    s"$outDir/training_${midOrPair}_words.tsv"
  } else {
    (params \ "mid word file").extract[String]  // some S3 URI
  }

  val wordFeatureFile = if (runningLocally) {
    midOrPair match {
      case "mid" => s"$outDir/cat_word_features.tsv"
      case "mid_pair" => s"$outDir/rel_word_features.tsv"
    }
  } else {
    (params \ "word feature file").extract[String]  // some S3 URI
  }

  val filteredFeatureFile = if (runningLocally) {
    s"$outDir/${midOrPair}_features.tsv"
  } else {
    (params \ "filtered feature file").extract[String]  // some S3 URI
  }

  val processor = new TrainingDataProcessor(params \ "training data", fileUtil)
  val featureComputer = new TrainingDataFeatureComputer(params \ "training data features", fileUtil)
  override def paramFile = s"$outDir/pmi_${midOrPair}_params.json"
  override def name = "Spark PMI computer"
  override def inputs = Set(
    (matrixFile, if (runningLocally) Some(featureComputer) else None),
    (midWordFile, if (runningLocally) Some(processor) else None)
  )
  override def outputs = Set(
    wordFeatureFile,
    filteredFeatureFile
  )

  /**
   * Here we select a set of features to use for each word in our data.  The main steps in the
   * pipeline are these:
   * 1. Compute PMI for each (word, feature) pair
   *   a. Get a map of (mid, Set(word)) from the training data
   *   b. Get a map of (feature, count) from the input feature matrix and the map in (a)
   *   c. Get a map of (word, count) from the input feature matrix and the map in (a)
   *   d. Get a map of ((word, feature), count) from the input feature matrix and the map in (a)
   *   e. Use the results from b, c, and d to compute PMI for each (word, feature) pair
   * 2. Select the top k features per word, by PMI
   * 3. Filter the training matrix file to only have features that were selected by some word, so
   *    that we don't have to keep around and load a huge file with millions of features.
   */
  override def _runStep() {
    val conf = new SparkConf().setAppName(s"Compute PMI ($midOrPair)")
      .set("spark.driver.maxResultSize", "0")
      .set("spark.network.timeout", "1000000")
      .set("spark.akka.frameSize", "1028")

    if (runningLocally) {
      conf.setMaster("local[*]")
    }

    val sc = new SparkContext(conf)

    // PB here and below is "pre-broadcast" - it's a sign that you probably shouldn't be using this
    // variable anywhere except in a call to sc.broadcast()

    // Step 1a
    val midWordCountsPB = sc.textFile(midWordFile).map(line => {
      val fields = line.split("\t")
      val mid = fields(0).replace(",", " ")
      val words = fields.drop(1).toSeq
      // The last .map(identity) is because of a scala bug - Map#mapValues is not serializable.
      val wordCounts = words.groupBy(identity).mapValues(_.size).map(identity)
      (mid -> wordCounts.toMap)
    }).filter(_._2.map(_._2).foldLeft(0)(_+_) < maxWordCount)
    println(s"Read words for ${midWordCountsPB.count} mids")
    val midWordCounts = sc.broadcast(midWordCountsPB.collectAsMap)

    // Step 1b
    val featureCountsPB = sc.textFile(matrixFile, numPartitions).flatMap(line => {
      val fields = line.split("\t")
      val mid = fields(0)
      val words = midWordCounts.value.getOrElse(mid, Seq.empty)
      val features = if (fields.length > 1) fields(1).trim.split(" -#- ") else Array[String]()
      if (features.length < maxFeatureCount) {
        val totalCount = words.map(_._2).foldLeft(0)(_+_).toDouble
        if (totalCount > 0) {
          for (feature <- features) yield (feature -> totalCount)
        } else {
          Seq()
        }
      } else {
        Seq()
      }
    }).reduceByKey(_+_).filter(_._2 > minFeatureCount)
    val featureCounts = sc.broadcast(featureCountsPB.collectAsMap)

    // Step 1c
    val wordCountsPB = sc.textFile(matrixFile, numPartitions).flatMap(line => {
      val fields = line.split("\t")
      val mid = fields(0)
      val words = midWordCounts.value.getOrElse(mid, Seq.empty)
      val features = if (fields.length > 1) fields(1).trim.split(" -#- ") else Array[String]()
      val numFeatures = features.length.toDouble
      if (numFeatures < maxFeatureCount) {
        for ((word, wordCount) <- words) yield (word -> wordCount * numFeatures)
      } else {
        Seq()
      }
    }).reduceByKey(_+_)
    val wordCounts = sc.broadcast(wordCountsPB.collectAsMap)

    // Step 1d
    val wordFeatureCounts = sc.textFile(matrixFile, numPartitions).flatMap(line => {
      val fields = line.split("\t")
      val mid = fields(0)
      val words = midWordCounts.value.getOrElse(mid, Seq.empty)
      val features = if (fields.length > 1) fields(1).trim.split(" -#- ") else Array[String]()
      if (features.length < maxFeatureCount) {
        val counts = featureCounts.value
        for ((word, wordCount) <- words;
             feature <- features;
             if counts.getOrElse(feature, 0.0) > minFeatureCount) yield ((word, feature) -> wordCount.toDouble)
      } else {
        Seq()
      }
    }).reduceByKey(_+_)
    wordFeatureCounts.persist()

    // Step 1e
    val pmiScores = wordFeatureCounts.map(entry => {
      val ((word, feature), wordFeatureCount) = entry
      val wordCount = wordCounts.value(word)
      val featureCount = featureCounts.value.getOrElse(feature, 0.0)
      val pmi = if (featureCount < minFeatureCount) {
        0
      } else {
        if (useSquaredPmi) {
          (wordFeatureCount * wordFeatureCount) / (wordCount * featureCount)
        } else {
          (wordFeatureCount) / (wordCount * featureCount)
        }
      }
      (word, (feature, pmi))
    })

    // Step 2
    val pmiFailures = sc.accumulator(0, "PMI failures")
    val keptFeatures = pmiScores.aggregateByKey(List[(String, Double)]())((partialList, item) => item :: partialList, (list1, list2) => list1 ::: list2)
      .map(entry => {
        try {
          val (word, featureScores) = entry
          // Always include CONNECTED and bias as potential features.  bias will get automatically
          // added to every entity / entity pair, and CONNECTED means that two entities have a direct
          // connection in the graph.
          val defaultFeatures = if (midOrPair == "mid pair") Seq(("bias", 1.0), ("CONNECTED", 1.0)) else Seq(("bias", 1.0))
          val grouped = featureScores.groupBy(_._2).toSeq.sortBy(-_._1).take(featuresPerWord)
          val kept = grouped.flatMap(entry => {
            val score = entry._1
            if (score > 0.0) {
              val features = entry._2.map(_._1)
              val shortest_feature = features.sortBy(_.length).head
              Seq((shortest_feature, score)) ++ defaultFeatures
            } else {
              Seq() ++ defaultFeatures
            }
          })
          (word, kept)
        } catch {
          case e: Exception => {
            pmiFailures += 1
            (entry._1, Seq(("ERROR IN PMI COMPUTATION!", 0.0)))
          }
        }
      })
    keptFeatures.persist()

    // Saving the results of Step 2
    val records = keptFeatures.map(entry => {
      val (word, features) = entry
      val f = features.map(_._1).toSet
      val featureStr = f.mkString("\t")
      s"$word\t$featureStr"
    })
    val results = records.collect()
    if (runningLocally) {
      new FileUtil().writeLinesToFile(wordFeatureFile, results)
    } else {
      sc.parallelize(results, 1).saveAsTextFile(wordFeatureFile)
    }


    // Step 3
    val allAllowedFeatures = sc.broadcast(keptFeatures.flatMap(_._2.map(_._1)).collect.toSet)
    val filteredMatrix = sc.textFile(matrixFile, numPartitions).map(line => {
      val allowedFeatures = allAllowedFeatures.value
      val defaultFeatures = Seq("bias")
      val fields = line.split("\t")
      val mid = fields(0)
      val features = if (fields.length > 1) fields(1).trim.split(" -#- ") else Array[String]()
      val filteredFeatures = features.flatMap(feature => {
        if (allowedFeatures.contains(feature)) {
          Seq(feature)
        } else {
          Seq()
        }
      })
      val featureStr = (filteredFeatures ++ defaultFeatures).mkString(" -#- ")
      s"${mid}\t${featureStr}"
    })

    // Saving the results of Step 3
    val finalMatrix = filteredMatrix.collect()
    if (runningLocally) {
      new FileUtil().writeLinesToFile(filteredFeatureFile, finalMatrix)
    } else {
      sc.parallelize(finalMatrix, 1).saveAsTextFile(filteredFeatureFile)
    }
  }
}
