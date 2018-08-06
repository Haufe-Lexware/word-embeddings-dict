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

package com.haufe.umantis.ds.embdict.messages

import org.apache.spark.ml.linalg.Vector

import scala.collection.immutable.ListMap


case class WordVectorQuery(word: String)
case class WordVectorQueryWithIndex(word: String)
case class WordExistsQuery(word: String)
case class WordsVectorsQuery(words: Set[String])

case class SynonymQueryWord(word: String, size: Int)
case class SynonymQueryVector(vector: Vector, size: Int)
case class SynonymQueryArray(vector: Array[Float], size: Int)

case class VectorResponse(vector: Option[Array[Float]])
case class VectorResponseWithIndex(index: Option[Int], vector: Option[Array[Float]])
case class VectorsMapResponse(vectors: Map[String, Option[Array[Float]]])
case class SynonymResponse(synonyms: Option[ListMap[String, Float]])

case class AnalogyQuery(positive: Array[String], negative: Array[String], size: Int)
