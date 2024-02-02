package bmaso

import com.typesafe.config.ConfigFactory
import zio.http.Server
import zio.jdbc.ZConnectionPoolConfig
import zio.{Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}

object NanobankMainApp extends ZIOAppDefault  {

  //...TBD: Lock down Environment to more specific resource requirements...
  override def run: ZIO[Environment with ZIOAppArgs with Scope, Throwable, Any] = {
    val httpRoutes = NanobankServiceRoutes()
    val config = ConfigFactory.load()

    lazy val mySqlRepoLayer: ZLayer[Any, Throwable, NanobankRepo] = {
      val connPoolConfig =
        if(config.hasPath("nanobank.connection_pool"))
          ZConnectionPoolConfig(
            minConnections = config.getInt("nanobank.connection_pool.minConnections"),
            maxConnections = config.getInt("nanobank.connection_pool.maxConnections"),
            retryPolicy = Schedule.fromDuration(config.getDuration("nanobank.connection_pool.duration_between_retries")),
            timeToLive = zio.Duration.fromJava(config.getDuration("nanobank.connection_pool.ttl_duration")))
        else
          ZConnectionPoolConfig.default

      NanobankRepo.mySqlRepoImpl(
        host = config.getString("nanobank.mysql_host"),
        port = config.getInt("nanobank.mysql_port"),
        database = config.getString("nanobank.mysql_database"),
        username = config.getString("nanobank.mysql_username"),
        password = config.getString("nanobank.mysql_password"),
        connectionPoolConfig = connPoolConfig
      )
    }

    for {
      server <-
        Server
          .serve(
            httpRoutes.withDefaultErrorResponse
          )
          .provide(
            Server.defaultWithPort(9000),

            //...NanobankRepo...
            mySqlRepoLayer
            // NanobankRepo.emptyRepoImpl()   // alternative
            // NanobankRepo.inMemoryRepoImpl(Map("abc123 -> Account(1, "abc123", "Checking", "Brian Maso"))   // alternative
          )
    } yield (server)
  }
}
