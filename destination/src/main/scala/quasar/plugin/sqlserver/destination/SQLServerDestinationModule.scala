/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.sqlserver.destination

import quasar.plugin.sqlserver.{ConnectionConfig, DriverParameter}

import scala._, Predef._
import scala.concurrent.duration._

import java.net.URI

import argonaut._, Argonaut._

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._

import doobie.Transactor

import org.slf4s.Logger

import quasar.api.destination.DestinationType
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{Destination, PushmiPullyu}
import quasar.lib.jdbc._
import quasar.lib.jdbc.destination._

object SQLServerDestinationModule extends JdbcDestinationModule[DestinationConfig] {

  val DefaultConnectionMaxConcurrency: Int = 16
  val DefaultConnectionMaxLifetime: FiniteDuration = 5.minutes

  val destinationType = DestinationType("sql-server", 1L)

  def sanitizeDestinationConfig(config: Json): Json =
    config.as[DestinationConfig].toOption.fold(jEmptyObject)(_.sanitized.asJson)

  def transactorConfig(config: DestinationConfig)
      : Either[NonEmptyList[String], TransactorConfig] =
    for {
      cc <- config.connectionConfig.validated.toEither.leftMap(NonEmptyList.one(_))

      maxConcurrency = cc.maxConcurrency getOrElse DefaultConnectionMaxConcurrency
      maxLifetime = cc.maxLifetime getOrElse DefaultConnectionMaxLifetime

      connectionString =
        ConnectionConfig.Optics.parameters
          .modify(
            DriverParameter.SendTemporalDataTypesAsStringForBulkCopy ::
              DriverParameter.SendTimeAsDatetime ::
              DriverParameter.LoginTimeout ::
              _)(cc)
          .jdbcUrl

      jdbcUrl <- {
        val prefix = "jdbc:sqlserver://"
        if (connectionString.take(prefix.length) === prefix) {
          Right(new URI("jdbc", connectionString.drop(5), null))
        }
        else {
          Left(NonEmptyList.one("Malformed JDBC connection string, ensure that the prefix is 'jdbc:'"))
        }
      }

      tc =
        TransactorConfig
          .withDefaultTimeouts(
            JdbcDriverConfig.JdbcDriverManagerConfig(
              jdbcUrl,
              Some("com.microsoft.sqlserver.jdbc.SQLServerDriver")),
            connectionMaxConcurrency = maxConcurrency,
            connectionReadOnly = false)
      txConfig = 
        tc.copy(poolConfig = tc.poolConfig.map(_.copy(connectionMaxLifetime = maxLifetime)))
    } yield txConfig

  def jdbcDestination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      config: DestinationConfig,
      transactor: Transactor[F],
      pushPull: PushmiPullyu[F],
      log: Logger)
      : Resource[F, Either[InitError, Destination[F]]] = {
    val schema = config.schema.getOrElse("dbo")

    (new SQLServerDestination[F](config.writeMode, schema, transactor, log): Destination[F])
      .asRight[InitError]
      .pure[Resource[F, ?]]
  }
}
