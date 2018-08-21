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

import com.github.fommil.netlib.BLAS.{getInstance => blas}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.util.BoundedPriorityQueueFactory

import scala.collection.immutable.ListMap
//import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.mutable.ParArray


case class EmbeddingsDataParallel(wordIndex: Map[String, Int],
                                  wordVectors: Array[Array[Float]],
                                  wordVecNorms: Array[Array[Float]],
                                  wordList: Array[String])



class EmbeddingsDictParallel(language: String,
                             private  val rescaledVectors: Boolean,
                             override val vectorSize: Int,
                             override val wordIndex: Map[String, Int],
                             private val wordVectors: Array[Array[Float]],
                             private val wordVecNorms: Array[Array[Float]],
                             private val wordList: Array[String])
  extends EmbeddingsDict {

  // last partition is bigger
  private val numVecsPerPartition = wordVectors(0).length / vectorSize
  private val numSplits = wordVectors.length
  private val numWordsSplit = wordVectors.map(partition => partition.length / vectorSize)

  val parallelCollectionHelper: ParArray[Int] = (0 until numSplits).toArray.par

  // No difference in performance
//  val numCores: Int = Runtime.getRuntime.availableProcessors
//  parallelCollectionHelper.tasksupport =
//    new ForkJoinTaskSupport(new scala.concurrent.forkjoin.ForkJoinPool(numCores))

  override def preStart(): Unit = {
    println(s"#### PARALLEL $language DICTIONARY IS READY ####")
  }

  @inline
  override def getVector(ind: Int): Array[Float] = {
    val (partition, index) = {
      val part = ind / numVecsPerPartition

      if (part < numSplits) {
        (part, ind % numVecsPerPartition)
      } else {
        // because last partition is bigger
        (numSplits - 1, numVecsPerPartition + (ind % numVecsPerPartition))
      }
    }
    val begin = index * vectorSize
    val end = begin + vectorSize

//    println(
//      s"PARALLEL: ind: $ind, partition: $partition, index: $index, " +
//      s"begin: $begin, end: $end")

//    println(
//      s"PARALLEL: ind: $ind, partition: $partition, index: $index, " +
//      s"partSize: ${wordVectors(partition).length}, begin: $begin, end: $end")

    wordVectors(partition).slice(begin, end)
  }

  /**
    * Find synonyms of the vector representation of a word, rejecting
    * words identical to the value of wordOpt, if one is supplied.
    * @param vector vector representation of a word
    * @param num number of synonyms to find
    * @param wordOpt optionally, a word to reject from the results list
    * @return array of (word, cosineSimilarity)
    */
  override def doFindSynonyms( vector: Vector,
                              num: Int,
                              wordOpt: Option[Array[String]])
  : ListMap[String, Float] = {

    require(num > 0, "Number of similar words should > 0")

    val numWordsTofilter = wordOpt match {
      case Some(words) => words.length
      case None => 1
    }

    val fVector = vector.toArray.map(_.toFloat)
    val alpha: Float = 1
    val beta: Float = 0
    // Normalize input vector before blas.sgemv to avoid Inf value
    val vecNorm = blas.snrm2(vectorSize, fVector, 1)
    if (vecNorm != 0.0f) {
      blas.sscal(vectorSize, 1 / vecNorm, fVector, 0, 1)
    }

    val globalPriorityQueue = parallelCollectionHelper.map(partition => {

      val thisPartitionSize = numWordsSplit(partition)
      val cosineVec = new Array[Float](thisPartitionSize)

      blas.sgemv(
        "T",
        vectorSize,
        thisPartitionSize,
        alpha,
        wordVectors(partition),
        vectorSize,
        fVector,
        1,
        beta,
        cosineVec,
        1
      )

      if (! rescaledVectors) {
          var i = 0
          while (i < thisPartitionSize) {
            val norm = wordVecNorms(partition)(i)
            if (norm == 0.0f) {
              cosineVec(i) = 0.0f
            } else {
              cosineVec(i) /= norm
            }
            i += 1
          }
      }

      val localPriorityQueue =
        BoundedPriorityQueueFactory.get[(String, Float)](num + numWordsTofilter)(Ordering.by(_._2))

      val wordListBaseIndex = numVecsPerPartition * partition
      var j = 0
      while (j < thisPartitionSize) {
        localPriorityQueue += Tuple2(wordList(wordListBaseIndex + j), cosineVec(j))
        j += 1
      }
      localPriorityQueue

    })
      .reduce(_ ++= _)

    val scored = globalPriorityQueue.toSeq.sortBy(-_._2)

    val filtered = wordOpt match {
      case Some(words) => scored.filter(tup => ! words.contains(tup._1))
      case None => scored
    }

    ListMap(
      filtered
        .take(num)
        .sortWith(_._2 > _._2)
        :_*
    )
  }
}
