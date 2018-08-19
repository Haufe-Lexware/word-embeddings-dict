/**
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
  */

package com.haufe.umantis.ds.embdict

import java.io.File

import akka.actor.{ActorSystem, Props}
import com.github.fommil.netlib.BLAS.{getInstance => blas}
import com.haufe.umantis.ds.utils.ConfigGetter
import com.typesafe.config.Config
import org.apache.spark.sql.functions.{array, not}
import org.apache.spark.sql.types.{FloatType, StringType, StructField, StructType}
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.io
import scala.util.Try

case class Data(word: String, vector: Array[Float])


object EmbeddingsDictReader {
  val config: Config = ConfigGetter.getConfig("/embeddings_dict.conf")
  val rootPath: String = config.getString("embdict.root-path")

  val vectorSize: Int = config.getInt("embdict.vector-size")
  val dictionarySizeString: String = config.getString("embdict.dictionary-size")
  val languages: List[String] =
    Try(config.getString("embdict.languages").split(",").toList).getOrElse(List("en", "de"))

  val doConversion: Boolean =
    Try(config.getString("embdict.do-conversion").toBoolean).getOrElse(false)
  val rescaleVectors: Boolean =
    Try(config.getString("embdict.rescale-vectors").toBoolean).getOrElse(true)
  val filterWords: Boolean =
    Try(config.getString("embdict.filter-words").toBoolean).getOrElse(true)
  val dictTypes: List[String] =
    Try(config.getString("embdict.dict-types").split(",").toList).getOrElse(List("parallel"))

  val maxDictionarySize: Int = 1000000000

  val spark: SparkSession = createSparkSession()

  val akkaConfigKryo: Config = ConfigGetter.getConfig("/akka_dict_server_kryo.conf")
  val akkaConfigJava: Config = ConfigGetter.getConfig("/akka_dict_server_java.conf")

  val kryoSerializerSystem: ActorSystem = {
    //create an actor system that uses the kryo serializer
    ActorSystem("EmbeddingsDict" , akkaConfigKryo)
  }

  val javaSerializerSystem: ActorSystem = {
    //create an actor system that uses java default serializer
    ActorSystem("EmbeddingsDict" , akkaConfigJava)
  }

  def main(args: Array[String]) {
    println(s"#### Using BLAS library: ${blas.getClass.getName} ####")

    if (doConversion) {
      convertAllPretrainedModels()
      spark.stop()
      System.exit(0)
    }

//    languages.par.foreach(lang => createActor(lang))
    languages.par.foreach(lang => createActor(lang))

    // not needed any longer
    spark.stop()
  }

  def createActor(language: String): Unit = {
    val actorName = language + "_dict"
    val embData = loadVectors(language, dictionarySizeString)

    if (dictTypes.contains("parallel")) {
      val splitEmbData = splitData(embData)

      def getActor = new EmbeddingsDictParallel(
        language,
        rescaleVectors,
        vectorSize,
        splitEmbData.wordIndex,
        splitEmbData.wordVectors,
        splitEmbData.wordVecNorms,
        splitEmbData.wordList
      )

      kryoSerializerSystem.actorOf(Props(getActor), name=actorName)
      javaSerializerSystem.actorOf(Props(getActor), name=actorName)
    }

    if (dictTypes.contains("serial")) {
      def getActor = new EmbeddingsDictSerial(
        language,
        rescaleVectors,
        vectorSize,
        embData.wordIndex,
        embData.wordVectors,
        embData.wordVecNorms,
        embData.wordList
      )

      kryoSerializerSystem.actorOf(Props(getActor), name = actorName + "_serial")
      javaSerializerSystem.actorOf(Props(getActor), name = actorName + "_serial")
    }
  }

  case class Boundaries(start: Int, var end: Int)

  def getBoundaries(numElements: Int, numSplits: Int): Array[Boundaries] = {
    val partitionSize = math.floor(numElements.toDouble / numSplits).toInt
    val toAddToLastPartition = numElements - partitionSize * numSplits
    val boundaries = (0 until numSplits)
      .map(i => Boundaries(i * partitionSize, (i + 1) * partitionSize))
    boundaries(numSplits - 1).end += toAddToLastPartition
    boundaries.toArray
  }

  def splitData(data: EmbeddingsData): EmbeddingsDataParallel = {
    println("#### PARTITIONING VECTORS ####")
    val numCores = Runtime.getRuntime.availableProcessors
    val boundaries = getBoundaries(data.wordList.length, numCores)
    val splitWordVectors =
      boundaries.map(b => data.wordVectors.slice(b.start * vectorSize, b.end * vectorSize))
    val splitWordVectorNorms = boundaries.map(b => data.wordVecNorms.slice(b.start, b.end))
//    splitWordVectors.foreach(x => println(x.length))
//    splitWordVectorNorms.foreach(x => println(x.length))
    EmbeddingsDataParallel(data.wordIndex, splitWordVectors, splitWordVectorNorms, data.wordList)
  }

  def loadVectors(language: String, dictSize: String): EmbeddingsData = {
    import spark.implicits._

    val wordFilter = if (filterWords)
      not($"word" rlike """([^\p{Ll}\p{Lu}])""")
    else $"word".isNotNull

    val lang = if (language == "en") language else language + "_en"
    val filename = s"$rootPath/wiki.$lang.model.$dictSize/data"

    println(s"#### READING $language DICTIONARY WITH SIZE: $dictSize FNAME: $filename ####")

    val wordVectorsMap: Map[String, Array[Float]] =
      spark.read.parquet(filename)
        .filter(wordFilter) // filtering non-letters
        .as[Data]
        .collect()
        .map(wordVector => (wordVector.word, wordVector.vector))
        .toMap

    println(s"#### $language DICTIONARY SIZE: ${wordVectorsMap.size} ####")

    val wordIndex = buildWordIndex(wordVectorsMap)
    val wordVectors = buildWordVectors(wordVectorsMap)
    val wordVecNorms = buildWordVecNorms(wordVectors, wordIndex.size, vectorSize)
    val wordList = buildWordList(wordIndex)
    if (rescaleVectors) {
      println("#### USING RESCALED VECTORS ####")
      val rescaledWordVectors =
        rescaleVectors(wordVectors, wordVecNorms, wordIndex.size, vectorSize)
      EmbeddingsData(wordIndex, rescaledWordVectors, wordVecNorms, wordList)
    } else {
      println("#### USING NON-RESCALED VECTORS ####")
      EmbeddingsData(wordIndex, wordVectors, wordVecNorms, wordList)
    }
  }

  private def buildWordIndex(model: Map[String, Array[Float]]): Map[String, Int] = {
    model.keys.zipWithIndex.toMap
  }

  private def buildWordVectors(model: Map[String, Array[Float]]): Array[Float] = {
    require(model.nonEmpty, "Word2VecMap should be non-empty")
    val (vectorSize, numWords) = (model.head._2.length, model.size)
    val wordList = model.keys.toArray
    val wordVectors = new Array[Float](vectorSize * numWords)
    var i = 0
    while (i < numWords) {
      Array.copy(model(wordList(i)), 0, wordVectors, i * vectorSize, vectorSize)
      i += 1
    }
    wordVectors
  }

  // wordVecNorms: Array of length numWords, each value being the Euclidean norm
  //               of the wordVector.
  private def buildWordVecNorms(wordVectors: Array[Float],
                                numWords: Int,
                                vectorSize: Int): Array[Float] = {
    val wordVecNorms = new Array[Float](numWords)
    var i = 0
    while (i < numWords) {
      val vec = wordVectors.slice(i * vectorSize, i * vectorSize + vectorSize)
      wordVecNorms(i) = blas.snrm2(vectorSize, vec, 1)
      i += 1
    }
    wordVecNorms
  }

  private def rescaleVectors(wordVectors: Array[Float],
                             wordVecNorms: Array[Float],
                             numWords: Int,
                             vectorSize: Int,
                             inPlace: Boolean = true):
  Array[Float] = {
    val scaledVectors: Array[Float] = if (inPlace) wordVectors else wordVectors.clone

    var i = 0
    while (i < numWords) {
      blas.sscal(vectorSize, 1f / wordVecNorms(i), scaledVectors, vectorSize * i, 1)
      i += 1
    }
    scaledVectors
  }

  // wordList: Ordered list of words obtained from wordIndex.
  private def buildWordList(wordIndex: Map[String, Int]): Array[String] = {
    val (wl, _) = wordIndex.toSeq.sortBy(_._2).unzip
    wl.toArray
  }

  def convertAllPretrainedModels(): Unit = {
    val dictionaryLimit =
      try {
        dictionarySizeString.toInt
      } catch {
        case _: java.lang.NumberFormatException => maxDictionarySize
      }

    print(rootPath)

    languages.par.foreach(lang => {
      val language = if (lang == "en") lang else lang + "_en"
      convertPretrainedModel(
        inFilename = rootPath + "/wiki." + language + ".vec",
        outFilenameRoot = "/wiki." + language + ".model",
        limit = dictionaryLimit
      )
    })
  }

  def convertPretrainedModel(inFilename: String,
                             outFilenameRoot: String,
                             limit: Int = maxDictionarySize): Unit = {
    // reading from CSV and saving to parquet
    val header = firstLine(inFilename).get.split(" ").map(_.toInt)
    val dictSize = header(0)
    val vectorSize = header(1)

    val baseOutFilename = if (limit < dictSize) outFilenameRoot + "." + limit.toString
    else outFilenameRoot + ".all"

    val wordSchema = Array(StructField("word", StringType, nullable = false))
    val vectorColumnNames = (0 until vectorSize).map(x => "vector" + x.toString).toArray
    val vectorSchema = vectorColumnNames.map(x => StructField(x, FloatType, nullable = false))
    val customSchema = StructType(wordSchema ++ vectorSchema)

    val modelTmp = spark.read.format("csv")
      .option("header", value = true)
      .option("delimiter", " ")
      .option("quote", "")
      .schema(customSchema)
      .load(inFilename)
      .limit(limit)

    val vectorColumns = vectorColumnNames.map(x => modelTmp.col(x))

    val model = modelTmp
      .withColumn("vector", array(vectorColumns:_*))
      .select("word", "vector")

    val outFilename = rootPath + baseOutFilename + "/data"
    println(s"#### WRITING MODEL TO $outFilename ####")
    model.write.mode(SaveMode.Overwrite).format("parquet").save(outFilename)
  }

  def firstLine(filename: String): Option[String] = {
    val f = new File(filename)
    val src = io.Source.fromFile(f)
    try {
      src.getLines.find(_ => true)
    } finally {
      src.close()
    }
  }

  def createSparkSession(): SparkSession = {
    SparkSession.
      builder().
      appName("Word Embeddings Reader").
      master("local[*]").
      config("spark.executor.memory", "12GB").
      config("spark.driver.memory", "12GB").
      config("spark.driver.maxResultSize", "10GB").
      getOrCreate()
  }
}
