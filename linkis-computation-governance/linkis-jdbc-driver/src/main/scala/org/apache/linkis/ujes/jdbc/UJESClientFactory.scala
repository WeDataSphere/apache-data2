/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.ujes.jdbc

import org.apache.linkis.common.utils.Logging
import org.apache.linkis.httpclient.dws.authentication.StaticAuthenticationStrategy
import org.apache.linkis.httpclient.dws.config.DWSClientConfigBuilder
import org.apache.linkis.ujes.client.UJESClient
import org.apache.linkis.ujes.jdbc.UJESSQLDriverMain._

import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.StringUtils

import java.nio.charset.StandardCharsets
import java.util
import java.util.Properties

object UJESClientFactory extends Logging {

  private val ujesClients = new util.HashMap[String, UJESClient]

  def getUJESClient(props: Properties): UJESClient = {
    val host = props.getProperty(HOST)
    val port = props.getProperty(PORT)
    val user = props.getProperty(USER)
    val pwd = props.getProperty(PASSWORD)
    val sslEnabled =
      if (
          props
            .containsKey(USE_SSL) && "true".equalsIgnoreCase(props.getProperty(USE_SSL))
      ) {
        true
      } else {
        false
      }
    val prefix = if (sslEnabled) {
      "https"
    } else {
      "http"
    }
    val serverUrl =
      if (StringUtils.isNotBlank(port)) s"$prefix://$host:$port" else "$prefix://" + host
    val uniqueKey = s"${serverUrl}_${user}_${pwd}"
    val uniqueKeyDes = Hex.encodeHexString(uniqueKey.getBytes(StandardCharsets.UTF_8))
    if (ujesClients.containsKey(uniqueKeyDes)) {
      logger.info("Clients with the same JDBC unique key({}) will get it directly", serverUrl)
      ujesClients.get(uniqueKeyDes)
    } else {
      uniqueKeyDes.intern synchronized {
        if (ujesClients.containsKey(uniqueKeyDes)) {
          logger.info("Clients with the same JDBC unique key({}) will get it directly", serverUrl)
          return ujesClients.get(uniqueKeyDes)
        }
        logger.info(
          "The same Client does not exist for the JDBC unique key({}), a new Client will be created",
          serverUrl
        )
        val ujesClient = createUJESClient(serverUrl, props, sslEnabled)
        ujesClients.put(uniqueKeyDes, ujesClient)
        ujesClient
      }
    }
  }

  private def createUJESClient(
      serverUrl: String,
      props: Properties,
      sslEnabled: Boolean
  ): UJESClient = {
    val clientConfigBuilder = DWSClientConfigBuilder.newBuilder()
    clientConfigBuilder.addServerUrl(serverUrl)
    clientConfigBuilder.setAuthTokenKey(props.getProperty(USER))
    clientConfigBuilder.setAuthTokenValue(props.getProperty(PASSWORD))
    clientConfigBuilder.setAuthenticationStrategy(new StaticAuthenticationStrategy())
    clientConfigBuilder.readTimeout(100000)
    clientConfigBuilder.maxConnectionSize(20)
    val params = props.getProperty(PARAMS)
    var versioned = false
    if (StringUtils.isNotBlank(params)) {
      var enableDiscovery = false
      params.split(PARAM_SPLIT).foreach { kv =>
        kv.split(KV_SPLIT) match {
          case Array(VERSION, v) =>
            clientConfigBuilder.setDWSVersion(v)
            versioned = true
          case Array(MAX_CONNECTION_SIZE, v) =>
            clientConfigBuilder.maxConnectionSize(v.toInt)
          case Array(READ_TIMEOUT, v) =>
            clientConfigBuilder.readTimeout(v.toLong)
          case Array(ENABLE_DISCOVERY, v) =>
            clientConfigBuilder.discoveryEnabled(v.toBoolean)
            enableDiscovery = true
          case Array(ENABLE_LOADBALANCER, v) if enableDiscovery =>
            clientConfigBuilder.loadbalancerEnabled(v.toBoolean)
          case _ =>
        }
      }
    }
    if (!versioned) clientConfigBuilder.setDWSVersion("v" + DEFAULT_VERSION)

    if (sslEnabled) {
      clientConfigBuilder.setSSL(sslEnabled)
    }
    UJESClient(clientConfigBuilder.build())
  }

}
