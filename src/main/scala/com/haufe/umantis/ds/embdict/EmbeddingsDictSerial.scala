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


case class EmbeddingsData(wordIndex: Map[String, Int],
                          wordVectors: Array[Float],
                          wordVecNorms: Array[Float],
                          wordList: Array[String])



class EmbeddingsDictSerial(language: String,
                           private val rescaledVectors: Boolean,
                           override val vectorSize: Int,
                           override val wordIndex: Map[String, Int],
                           private val wordVectors: Array[Float],
                           private val wordVecNorms: Array[Float],
                           private val wordList: Array[String])
  extends EmbeddingsDict {

  private val numWords = wordIndex.size

  override def preStart(): Unit = {
    println(s"#### SERIAL $language DICTIONARY IS READY ####")
  }

  @inline
  override def getVector(ind: Int): Array[Float] = {
    val begin = ind * vectorSize
    val end = begin + vectorSize

//    println(s"SERIAL: ind: $ind, vectorIndexSize: ${wordVectors.length}, " +
//      s"begin: $begin, end: $end")

    wordVectors.slice(begin, end)
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
    val cosineVec = new Array[Float](numWords)
    val alpha: Float = 1
    val beta: Float = 0
    // Normalize input vector before blas.sgemv to avoid Inf value
    val vecNorm = blas.snrm2(vectorSize, fVector, 1)
    if (vecNorm != 0.0f) {
      blas.sscal(vectorSize, 1 / vecNorm, fVector, 0, 1)
    }
    blas.sgemv(
      "T", vectorSize, numWords, alpha, wordVectors, vectorSize, fVector, 1, beta, cosineVec, 1)

    if (! rescaledVectors) {
      var i = 0
      while (i < numWords) {
        val norm = wordVecNorms(i)
        if (norm == 0.0f) {
          cosineVec(i) = 0.0f
        } else {
          cosineVec(i) /= norm
        }
        i += 1
      }
    }

    val pq = BoundedPriorityQueueFactory.get[(String, Float)](num + numWordsTofilter)(Ordering.by(_._2))

    var j = 0
    while (j < numWords) {
      pq += Tuple2(wordList(j), cosineVec(j))
      j += 1
    }

    val scored = pq.toSeq.sortBy(-_._2)

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
