package bmaso

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder}

case class Account(
  internalId: Long,
  uuid: String,
  name: String,
  owner: String //...placeholder for ownership/access privileges hook...
)

case class Transaction(
  internalId: Long,
  uuid: String,
  account_uuid: String,
  recorded_et: Long,
  amount: Double,
  description: String
)

case class NewTransactionRequest(
  account_id: String,
  amount: Double,
  description: String
)

object NewTransactionRequest {
  implicit val jsonDecoder = DeriveJsonDecoder.gen[NewTransactionRequest]
  implicit val jsonEncoder = DeriveJsonEncoder.gen[NewTransactionRequest]
}

object BusinessErrors {
  sealed trait BusinessError
  case class NoSuchAccount(account_uuid: String) extends BusinessError
  object GarbledRequest extends BusinessError
  object OperationRuleViolation extends BusinessError
}
