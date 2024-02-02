package bmaso

import zio._
import zio.http._
import zio.json._

/**
 * Implementation of the HTTP endpoints in ZIO HTTP
 *
 * TBD: integration of ZIO JSON, so some Scala JSON lib
 */
object NanobankServiceRoutes {

  def apply(): Http[NanobankRepo, Nothing, Request, Response] =
    Http.collectZIO[Request] {
      // GET /account/:accountId
      case Method.GET -> Root / "account" / accountId =>
        (for {
          dbResult <- NanobankRepo.lookupAccount(accountId)
          result <- dbResult match {
            case None =>
              ZIO.succeed(Response.status(Status.NotFound))
            case Some((account, balance)) =>
              ZIO.succeed(Response.json {
                s"""{ "id": "${account.uuid}", "name": "${account.name}", "owner": "${account.owner}", "balance": $balance }"""
              })
          }
        } yield result).orDie

      // GET /transaction/history/:accountId
      case Method.GET -> Root / "transaction" / "history" / accountId =>
        (for {
          dbResult <- NanobankRepo.accountTransactions(accountId)
          jsonStrs = dbResult.map(_.map(tx =>
                    s"""{"id": "${tx.uuid}", "amount": ${tx.amount}, "recorded_epoch_time": ${tx.recorded_et}, "description": "${tx.description}"}"""))
          result   <- ZIO.succeed(jsonStrs.map(_.mkString("[",  "," , "]")) match {
            case Some(json) => Response.json(json)
            case None       => Response.status(Status.NotFound)
          })
        } yield result).orDie

      // POST /transaction
      case req @ (Method.POST -> Root / "transaction") =>
        (for {
          jsonConversionResult <- req.body.asString.map(_.fromJson[NewTransactionRequest])
          tx <- ZIO.fromEither(jsonConversionResult).mapError(_ => BusinessErrors.GarbledRequest)
          optionalAccount <- NanobankRepo.lookupAccount(tx.account_id)
          account <- ZIO.fromOption(optionalAccount).mapError(_ => BusinessErrors.NoSuchAccount(tx.account_id))
          newTransactionOperationResults <- NanobankRepo.newTransaction(tx.account_id, tx.amount, tx.description)
          _ <- ZIO.fromEither(newTransactionOperationResults)
        } yield Response.status(Status.Created))
        .catchAll {
          case BusinessErrors.NoSuchAccount(acct) => ZIO.succeed(Response.status(Status.NotFound))
          case BusinessErrors.GarbledRequest => ZIO.succeed(Response.status(Status.BadRequest))
          case BusinessErrors.OperationRuleViolation => ZIO.succeed(Response.status(Status.UnprocessableEntity))
          case throwable: Throwable => ZIO.fail(throwable)
        }
        // ...this final case should never happen...
        .orDieWith(obj => new Exception(s"Unknown problem... $obj"))
    }

}
