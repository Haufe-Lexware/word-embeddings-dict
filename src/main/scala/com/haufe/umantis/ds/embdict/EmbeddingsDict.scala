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

import akka.actor.Actor
import akka.event.Logging
import com.github.fommil.netlib.BLAS.{getInstance => blas}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import scala.collection.immutable.ListMap

import com.haufe.umantis.ds.embdict.messages._


trait EmbeddingsDict extends Actor {

  val wordIndex: Map[String, Int] = Map[String, Int]()

  val vectorSize = 0

  val minNgramSize = 3
  val maxNgramSize = 6

  val log = Logging(context.system, this)

  override def receive: Receive = {
    case word: String => sender ! VectorResponse(transform(word))
    case query: WordVectorQuery => sender ! VectorResponse(transform(query.word))
    case query: WordVectorQueryWithIndex => sender ! transformWithIndex(query.word)
    case query: WordsVectorsQuery =>
      val wordVectors = query.words.map(w => w -> transform(w)).toMap
      sender ! VectorsMapResponse(wordVectors)
    case query: WordExistsQuery => sender ! wordExists(query.word)
    case syn: SynonymQueryWord => sender ! findSynonyms(syn.word, syn.size)
    case syn: SynonymQueryVector => sender ! findSynonyms(syn.vector, syn.size, None)
    case syn: SynonymQueryArray => sender ! findSynonyms(syn.vector, syn.size, None)
    case analogyQuery: AnalogyQuery => sender ! analogy(analogyQuery)
    case x => log.warning("Received unknown message: {}", x)
  }

  def analogy(analogyQuery: AnalogyQuery): SynonymResponse = {
    val sum = Array.fill[Float](vectorSize)(0)

    analogyQuery.positive.foreach(word =>
      transform(word) match {
        case Some(v) =>
          blas.saxpy(vectorSize, 1.0f, v, 1, sum, 1)
        case None => ;
      })
    analogyQuery.negative.foreach(word =>
      transform(word) match {
        case Some(v) =>
          blas.saxpy(vectorSize, -1.0f, v, 1, sum, 1)
        case None => ;
      })
    val norm = blas.snrm2(vectorSize, sum, 1)
    blas.sscal(vectorSize, 1.0f / norm, sum, 1)

    val wordsToFilter = analogyQuery.positive ++ analogyQuery.negative
    findSynonyms(sum, analogyQuery.size, Some(wordsToFilter))
  }

  def wordExists(word: String): Boolean = {
    wordIndex.contains(word)
  }

  def transform(str: String): Option[Array[Float]] = transformSubword(str)

  def transformWithIndex(str: String): VectorResponseWithIndex = {
    val index = wordIndex.get(str)
    val vector = index match {
      case Some(ind) =>
        Some(getVector(ind))
      case None => None
    }

    VectorResponseWithIndex(index, vector)
  }

  def getVector(ind: Int): Array[Float]

  @inline
  def transformSimple(word: String): Option[Array[Float]] = {
    wordIndex.get(word) match {
      case Some(ind) =>
        Some(getVector(ind))
      case None => None
    }
  }

  def transformSubword(word: String): Option[Array[Float]] = {
    wordIndex.get(word) match {
      case Some(ind) =>
        Some(getVector(ind))
      case None =>
        val sum = Array.fill[Float](vectorSize)(0)
        for (n <- minNgramSize to maxNgramSize) {
          for (idx <- 0 to word.length - n) {
            val subword = word.slice(idx, idx + n)
            transformSimple(subword) match {
              case Some(v) =>
                blas.saxpy(vectorSize, 1.0f, v, 1, sum, 1)
              case None => ;
            }
          }
        }
        val norm = blas.snrm2(vectorSize, sum, 1)
        if (norm > 0) {
          blas.sscal(vectorSize, 1.0f / norm, sum, 1)
          Some(sum)
        }
        else
          None
    }
  }

  /**
    * Find synonyms of a word; do not include the word itself in results.
    * @param word a word
    * @param num number of synonyms to find
    * @return array of (word, cosineSimilarity)
    */
  def findSynonyms(word: String, num: Int): SynonymResponse = {
    transform(word) match {
      case Some(vec) =>
        val vector = Vectors.dense(vec.map(_.toDouble))
        SynonymResponse(Some(doFindSynonyms(vector, num, Some(Array(word)))))
      case None => SynonymResponse(None)
    }
  }

  /**
    * Find synonyms of the vector representation of a word, possibly
    * including any words in the model vocabulary whose vector respresentation
    * is the supplied vector.
    * @param vector vector representation of a word
    * @param num number of synonyms to find
    * @return array of (word, cosineSimilarity)
    */
  def findSynonyms(vector: Array[Float],
                   num: Int,
                   wordOpt: Option[Array[String]]): SynonymResponse = {
    val vec = Vectors.dense(vector.map(_.toDouble))
    SynonymResponse(Some(doFindSynonyms(vec, num, wordOpt)))
  }

  /**
    * Find synonyms of the vector representation of a word, possibly
    * including any words in the model vocabulary whose vector respresentation
    * is the supplied vector.
    * @param vector vector representation of a word
    * @param num number of synonyms to find
    * @return array of (word, cosineSimilarity)
    */
  def findSynonyms(vector: Vector,
                   num: Int,
                   wordOpt: Option[Array[String]]): SynonymResponse = {
    SynonymResponse(Some(doFindSynonyms(vector, num, wordOpt)))
  }

  def doFindSynonyms( vector: Vector,
                      num: Int,
                      wordOpt: Option[Array[String]]): ListMap[String, Float]
}
