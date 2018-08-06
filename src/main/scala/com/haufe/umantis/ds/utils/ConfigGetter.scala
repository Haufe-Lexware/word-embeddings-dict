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

package com.haufe.umantis.ds.utils

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.util.stream.Collectors

import com.typesafe.config.{Config, ConfigFactory}


object ConfigGetter {
  def getConfig(filename: String): Config = {
    val configFileString = getConfigString(filename)

    //parse the config
    val config = ConfigFactory.parseString(configFileString)

    // resolving config file with environment variables
    config.resolve()
  }

  def getConfigString(filename: String): String = {
    //get the configuration file from classpath
    val configFileStream: InputStream = getClass.getResourceAsStream(filename)
    try {
      val streamReader = new InputStreamReader(configFileStream)
      val configFileString: String = new BufferedReader(streamReader)
        .lines().collect(Collectors.joining("\n"))
      configFileStream.close()

      configFileString
    } catch {
      case _: java.lang.NullPointerException =>
        println(s"Configuration file not found! ($filename)")
        System.exit(1)

        // returning a default String to make the return type happy...
        // there must be a cleaner solution
        ""
    }
  }
}