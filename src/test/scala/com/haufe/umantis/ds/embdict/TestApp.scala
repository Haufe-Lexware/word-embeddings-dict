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

import akka.actor.{Actor, ActorSelection, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.haufe.umantis.ds.embdict.messages._
import com.haufe.umantis.ds.utils.ConfigGetter
import com.typesafe.config.Config
import org.apache.spark.ml.linalg.{DenseVector, Vector}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
 * Test actor which listens on any free port
 */
class TestApp(languages: List[String], dictHostname: String) extends Actor{
  val remoteActors: Map[String, ActorSelection] = languages.map(lang => lang -> {
    context.actorSelection(s"akka.tcp://EmbeddingsDict@$dictHostname:5150/user/${lang}_dict")
  }).toMap

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    implicit val timeout: Timeout = Timeout(5 seconds)

    println("That's remote_en:" + remoteActors("en"))
    val result = Await.result(remoteActors("en") ? "dog", timeout.duration)
      .asInstanceOf[VectorResponse]
    result.vector match {
      case Some(v) => println("dog" + " " + v.deep.mkString(" "))
      case None => ;
    }

    println("That's remote_de:" + remoteActors("de"))
    val result2 = Await.result(remoteActors("de") ? "hund", timeout.duration)
      .asInstanceOf[VectorResponse]
    result2.vector match {
      case Some(v) => println("hund" + " " + v.deep.mkString(" "))
      case None => ;
    }
  }

  override def receive: Receive = {
    case msg: Array[Float] =>
      println("receive: got message from remote" + msg.deep.mkString(" "))
  }
}


object TestApp {
  import collection.JavaConversions._

  val localAkkaConfig: Config = ConfigGetter.getConfig("/akka_local_app.conf")
  val remoteAkkaConfig: Config = ConfigGetter.getConfig("/akka_dict_server_java.conf")
  val dictConfig: Config = ConfigGetter.getConfig("/embeddings_dict.conf")

  val dictHostname: String = remoteAkkaConfig.getString("akka.remote.netty.tcp.hostname")

  val system: ActorSystem = {
    ActorSystem("ClientSystem", localAkkaConfig)
  }

  val languages: List[String] = dictConfig.getStringList("embdict.languages").toList
  val remoteActors: Map[String, ActorSelection] = languages.map(lang => lang -> {
    system.actorSelection(s"akka.tcp://EmbeddingsDict@$dictHostname:5150/user/${lang}_dict")
  }).toMap

  implicit val timeout: Timeout = Timeout(5 seconds)

  def main(args: Array[String]) {
    val localActor = system.actorOf(Props(new TestApp(languages, dictHostname)), name = "local")

    query("en", "cat")
    query("de", "auto")

    getVector("en", "plumber") match {
      case Some(v) => synonyms("de", v)
      case None => ;
    }

    wordExists("en", "lawyer")
    wordExists("de", "alssdshdlksjhdflkjashdfalkjdf")

    synonyms("en", "dog")

    getVector("en", "cat") match {
      case Some(v) =>
        val vec = new DenseVector(v.map(_.toDouble))
        synonyms("de", vec)
      case None => ;
    }

    val vecMap = getVectors("en", Set("horse", "donkey", "cow")).vectors
    vecMap.foreach(item => item._2 match {
      case Some(v) => println(item._1 + " " + v.deep.mkString(" "))
      case None => println(s"${item._1} None")
    })

    println("\n\n")
    analogy("en", positive=Array("king", "woman"), negative = Array("man"))
    println("\n\n")
    analogy("en", positive=Array("paris", "germany"), negative = Array("france"))
  }

  def getVector(language: String, word: String): Option[Array[Float]] = {
    val query = WordVectorQuery(word)
    val future = remoteActors(language) ? query
    Await.result(future, timeout.duration).asInstanceOf[VectorResponse].vector
  }

  def getVectors(language: String, words: Set[String]): VectorsMapResponse = {
    val query = WordsVectorsQuery(words)
    val future = remoteActors(language) ? query
    Await.result(future, timeout.duration).asInstanceOf[VectorsMapResponse]
  }

  def query(language: String, word: String): Unit = {
    println(s"\nquerying $word")
    getVector(language, word) match {
      case Some(v) => println(word + " " + v.deep.mkString(" "))
      case None => ;
    }
    println("query completed\n")
  }

  def wordExists(language: String, word: String) {
    val query = WordExistsQuery(word)
    val future = remoteActors(language) ? query
    val result = Await.result(future, timeout.duration).asInstanceOf[Boolean]
    println(s"$word exists: $result")
  }

  def synonyms(language: String, input: String) {
    printSyn(remoteActors(language) ? SynonymQueryWord(input, 10))
  }

  def synonyms(language: String, vector: Vector) {
    printSyn(remoteActors(language) ? SynonymQueryVector(vector, 10))
  }

  def synonyms(language: String, vec: Array[Float]) {
    printSyn(remoteActors(language) ? SynonymQueryArray(vec, 10))
  }

  def analogy(language: String, positive: Array[String], negative: Array[String], topN: Int = 10) {
    printSyn(remoteActors(language) ? AnalogyQuery(positive, negative, topN))
  }

  def printSyn(future: Future[Any]) {
    val result = Await.result(future, timeout.duration).asInstanceOf[SynonymResponse]
    result.synonyms match {
      case Some(r) => r.foreach(res => println(res._1 + " " + res._2.toString))
      case None => println("word not found")
    }
  }
}