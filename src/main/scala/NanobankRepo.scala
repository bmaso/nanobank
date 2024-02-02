package bmaso

import zio._
import zio.jdbc._

import java.util.UUID

trait NanobankRepo {
  /**
   * Retrieve account and account balance -- balance MUST be equal to sum of txs within backing store transaction boundary
   */
  def lookupAccount(account_uuid: String): ZIO[Any, Throwable, Option[(Account, Double)]]

  /**
   * Retrieve account transactions  in reverse-chronological order. `None` and `Some(List())` mean different things:
   * * `None` means there is no such account
   * * `Some(List())` means the account exists, but has no associated transactions
   */
  def accountTransactions(account_uuid: String): ZIO[Any, Throwable, Option[List[Transaction]]]

  /**
   * Request to add transaction to the transaction log. Returns nominal value on success, or business error indicator
   * if fails. Note: This method is implemented to assume the target account has already been verified to exist. If
   * does not exist, this method's return value will only indicate that the system state is incompatible with the
   * completion of the request. This is the same result as will be returned if the transaction would overdraw the target account.
   */
  def newTransaction(account_uuid: String, amount: Double, description: String): ZIO[Any, Throwable, Either[BusinessErrors.BusinessError, Unit]]
}

object NanobankRepo {

  /*
   * Mirroring NanobankRepo trait methods
   */
  def lookupAccount(account_uuid: String): ZIO[NanobankRepo,  Throwable, Option[(Account,Double)]] =
    ZIO.serviceWithZIO[NanobankRepo](_.lookupAccount(account_uuid))

  def accountTransactions(account_uuid: String): ZIO[NanobankRepo, Throwable, Option[List[Transaction]]] =
    ZIO.serviceWithZIO[NanobankRepo](_.accountTransactions(account_uuid))

  def newTransaction(account_uuid: String, amount: Double, description: String):
      ZIO[NanobankRepo, Throwable, Either[BusinessErrors.BusinessError, Unit]] =
    ZIO.serviceWithZIO[NanobankRepo](_.newTransaction(account_uuid, amount, description))

  /**
   * A `NanobankRepo` impl that never returns any accounts and never accepts any transactions
   */
  def emptyRepoImpl(): ZLayer[Any, Throwable, NanobankRepo] =
    ZLayer.succeed {
      new NanobankRepo {
        override def lookupAccount(account_uuid: String): ZIO[Any, Throwable, Option[(Account, Double)]] =
          ZIO.succeed(None)

        override def accountTransactions(account_uuid: String): ZIO[Any, Throwable, Option[List[Transaction]]] =
          ZIO.succeed(None)

        override def newTransaction(account_uuid: String, amount: Double, description: String):
            ZIO[Any, Throwable, Either[BusinessErrors.BusinessError, Unit]] = ZIO.succeed(Left(BusinessErrors.OperationRuleViolation))
      }
    }

  /**
   * An in-memory `NanobankRepo` impl with initial accounts and transactions, which accepts new transactions
   * as well
   */
  def inMemoryRepoImpl(accountsByUUIDMap: Map[String, Account], transactions: List[Transaction]): ZLayer[Any, Throwable, NanobankRepo] =
    ZLayer.succeed {
      new NanobankRepo {
        override def lookupAccount(account_uuid: String): ZIO[Any, Throwable, Option[(Account, Double)]] =
          ZIO.succeed(accountsByUUIDMap.get(account_uuid).map((_, 0.0)))

        override def accountTransactions(account_uuid: String): ZIO[Any, Throwable, Option[List[Transaction]]] =
          ZIO.succeed(
            transactions.filter(_.account_uuid == account_uuid) match {
              case txs if accountsByUUIDMap.isDefinedAt(account_uuid) => Some(txs)
              case _                                                  => None
            })

        /** New transactions are not accepted by this implementation yet */
        override def newTransaction(account_uuid: String, amount: Double, description: String):
            ZIO[Any, Throwable, Either[BusinessErrors.BusinessError, Unit]] = ZIO.succeed(Left(BusinessErrors.OperationRuleViolation))
      }
    }

  /**
   * A repo impl that works with a MySQL datasource through JDBC connection pool
   */
  def mySqlRepoImpl(host: String, port: Int, database: String, username: String, password: String, connectionPoolConfig: ZConnectionPoolConfig): ZLayer[Any, Throwable, NanobankRepo] =
    ZLayer.succeed {
      new NanobankRepo {
        override def lookupAccount(account_uuid: String): ZIO[Any, Throwable, Option[(Account, Double)]] =
          transaction  {
            sql"""
              select
                acc.id,
                acc.uuid,
                acc.name,
                acc.owner,
                ifnull(sum(tx.amount), 0.0)
              from
                     account acc
                left outer join transaction tx
                  on (acc.uuid = tx.account_uuid)
              where
                 acc.uuid = $account_uuid
              group by
                 acc.uuid
            """.query[(Long, String, String, String, Double)].selectOne
          }
          .map(_.map(tuple => (Account(tuple._1, tuple._2, tuple._3, tuple._4), tuple._5)))
          .provide(ZLayer.succeed(connectionPoolConfig) >>> ZConnectionPool.mysql(host, port, database, Map("user" -> username, "password" -> password)))
          .tapError(err => ZIO.succeed(err.printStackTrace()))

        override def accountTransactions(account_uuid: String): ZIO[Any, Throwable, Option[List[Transaction]]] = {
          // ...single query retrieves account info from DB left outer joined with all transactions associated with the account (which may number 0).
          //    * if: _acc.id_ col value is `None` in all rows OR there are no rows-- both cases indicate the account does not exist
          //    * else: the account exists and there are zero or more transactions associated
          transaction {
            sql"""
               select
                 acc.id,
                 tx.id,
                 tx.uuid,
                 tx.account_uuid,
                 tx.recorded_et,
                 tx.amount,
                 tx.description
               from
                      account acc
                 left outer join transaction tx
                   on (acc.uuid = tx.account_uuid)
               where
                 acc.uuid = $account_uuid
               order by
                 tx.recorded_et
            """.query[(Option[Long], Long, String, String, Long, Double, String)].selectAll
          }
          .map(_.map(tuple => tuple._1.map(_ => Transaction(tuple._2, tuple._3, tuple._4, tuple._5, tuple._6, tuple._7))))
          .map(_.toList match {
            case None :: _  => None
            case optionalTransactions: List[Option[Transaction]] =>
                Some(optionalTransactions.collect({ case Some(tx) => tx  })) // ...I'm sure there's a much more clever way to transform List[Some[X]] => Some[List[X]], but I don't know it off the top of my head..
          })
          .provide(ZLayer.succeed(connectionPoolConfig) >>> ZConnectionPool.mysql(host, port, database, Map("user" -> username, "password" -> password)))
          .tapError(err => ZIO.succeed(err.printStackTrace()))
        }

        /* !!! TBD -- This should be using TRANSACTION_SERIALIZABLE as the
         * !!!        transaction isolation level. I'm not sure how to do that with ZIO right now. Without
         * !!!        it, multiple simultaneous TXs targeting the same account can be inserted at the same
         * !!!        time, potentially overdrawing the account.
         */
        override def newTransaction(account_uuid: String, amount: Double, description: String):
            ZIO[Any, Throwable, Either[BusinessErrors.BusinessError, Unit]] =
          transaction {
            sql"""
              insert into transaction (
                uuid,
                account_uuid,
                recorded_et,
                amount,
                description
              )
              select
                $freshUuid,
                $account_uuid,
                UNIX_TIMESTAMP(now()),
                $amount,
                $description
              where
                ($amount > 0) OR (0 <= (select sum(tt.amount) + ($amount) from transaction tt where (tt.account_uuid = $account_uuid)))
            """.insert
          }
          .map(x => if(x > 0) Right(()) else Left(BusinessErrors.OperationRuleViolation))
          .provide(ZLayer.succeed(connectionPoolConfig) >>> ZConnectionPool.mysql(host, port, database, Map("user" -> username, "password" -> password)))
          .tapError(err => ZIO.succeed(err.printStackTrace()))
      }
    }

  private def freshUuid: String = UUID.randomUUID.toString
}
