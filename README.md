# Brian Maso's "Nanobank" Coding Assignment

- Instructions how to run
- Implementation plan, thoughts, concerns, etc.
- Original assignment + my notes
- Implementation design,  including operation-state model of the service

## Instructions how to run

TBD

### Build project and create nanobank docker image

### Start nanobank and database servers using docker compose

### Pre-loading initial set of accounts and transactions

### Querying database directly for list of extant account identifiers

### Start the Nanobank server

- Running from the docker image

```
docker run \
  --name nanobank \
  --rm \
  [-e NANOBANK_MYSQL_HOST=... (default "localhost")] \
  [-e NANOBANK_MYSQL_PORT=... (default 3306)] \
  [-e NANOBANK_MYSQL_DATABASE=... (default "nanobank")] \
  [-e NANOBANK_MYSQL_USERNAME=... (default "nanobank")] \
  [-e NANOBANK_MYSQL_PASSWORD=... (default "nanobank")]\
  --rm \
  -d \
  nanobank
```

- Running from CLI using SBT

```
[NANOBANK_MYSQL_HOST=... (default "localhost")] \
[NANOBANK_MYSQL_PORT=... (default 3306)] \
[NANOBANK_MYSQL_DATABASE=... (default "nanobank")] \
[NANOBANK_MYSQL_USERNAME=... (default "nanobank")] \
[NANOBANK_MYSQL_PASSWORD=... (default "nanobank")] \
sbt run
```

## Implementation Plan and Notes

1. ZIO?

- It's been a while since I've used it, but its similar enough to cats, and has a lot of the same concepts as typed Akka, so will give it a go
    - If I'm going to bother being effectful I should just use the lib DirectBooks is using
- Alternative is non-effectful... seems too simplistic

2. Docker

- Use SBT native-packager/docker plugin to create image of the application
- If time allows, can put together a docker compose yaml to start up a service API container and a DB container

3. Storage

- Based on available time, there are 3 diff storage impls
    1. strictly in-mem storage impl -- typed Map instances back a service trait impl
    2. JDBC -- H2 initially, and can upgrade to mysql or pg, use docker-compose
    3. AWS/localstack DynamoDB -- Use docker compose to deploy alongside service impl
    - Probably overkill

4. Sequentialization of transactions targeting the same account

- In all scenarios with multiple interlaced `POST /transaction` and `GET /account/:accountId` and `GET /transaction/history/:account_id`
  the implementation must ensure all operation applications conform to the rules list below
- This really boils down to making sure all operations targeting the same account are sequentialized and atomic
    - if we can't get the DB interactions associated with each operation to be atomic,  then they need to be executed sequentially and not in parallel
- Implementation option: Compute `Account.balance` on read
    - No need to keep a `balance` column updated in the backing DB
        - Instead we re-compute the balance as part of the DB query to retrieve account state
    - This is probably the simplest to implement, and if the average number of transactions/acct isn't huge, and an
      index on _account_id_ column exists in transaction table, then the performance will be fine
        - Normally this would require an additional periodic "snapshotting" mechanism to avoid scanning too many transaction records everytime balance is needed
- Ideally we would use Event Sourcing across a cluster of servers
    - way over designing for this assignment
- The trick is going to be implementing the "Transaction cannot overdraw account" rule
    - See next note

5. Safe implementation of the "Cannot overdraw account" rule

- This is the rule that says you cannot withdraw more money than is in the account (see operation-state model description below)
- Just need to use a clever SQL `INSERT INTO ... SELECT ...` query that will produce NO values to insert when the target condition occurs
- In our case, we can only create a transaction if it will not overdraw an account
- Here is an example how to have the DB determine whether or not the "Cannot overdraw account" rule's condition is being met for a new transaction,
  only inserting the transaction if it does not cause the target account to be overdrawn
    - First, here are the account and transaction table definitions -- we're using `bigint` as the PK col type, and a uuid string as an external identifier
    - The transaction table index supports both querying transactions in an account, and also supports the insertion query, which sums up the account balance to implement an _insert if..._ statement

```sql
create table account (
  id bigint not null auto_increment primary key,
  uuid varchar(64) not null,
  name varchar(255) not null,
  owner varchar(255) not null
);
create unique index account_uuid_idx on account (uuid);

create table transaction (
  id bigint not null auto_increment primary key,
  uuid varchar(64) not null,
  account_uuid varchar(64) not null,
  recorded_et bigint not null,
  amount double not null,
  description varchar(255)  default ''
);
create index transaction_efficient_query_00_idx  on transaction (account_uuid, recorded_et);
```

- The following SQL statement _conditionally_ inserts a new row into the `transaction` table
    - The insertion will _not_ occur if the transaction would make the associated account have a negative balance AND the transaction amount is negative (a withdrawal)
    - Note that account balances are computed rather than stored as a sum; the current balance is the sum of extant transaction amounts

```
insert into transaction (uuid, account_uuid, amount, description)
select 'new-transaction-uuid', 'account-uuid', -50.0, 'withdrawn at the atm for take-out'
where (amount > 0) OR (0 >= (select sum(amount) + (-50.0) from transaction tt where (tt.account_uuid = 'account-uuid')));
```

- The DB always includes the number of inserted rows as part of the success response for any insertion statement
    - Quill and straight-up JDBC both have this behavior
    - Quill does not support the `INSERT INTO ... SELECT ...` SQL action, though it _is_ part of standard SQL for some time
    - If I have time I'll see if I can figure out how to do this with quill
    - But in the meantime will implement simply with ZIO JDBC
- I believe I will need to make sure the insertions are strictly sequentialized  using DB TX isolation levels
    - Need to determine which isolation level is adequate to ensure two simultanoues withdrawals won't cause an account to be overdrawn
    - I think `TRANSACTION_SERIALIZABLE` is adequate; there may be an acceptable lower level,  but its not worth figuring out for this assignment.
- It will also be necessary to ensure that the account exists prior to submitting the `INSERT INTO ... SELECT ...` statement
    - The statement will insert a row if conditions are met, and will insert no rows if conditions are not met _OR if the account does not exist_
    - It just makes sense to ensure the account exists before attempting the insert
    - Doing it any other way would be no more efficient, and would be pretty confusing to anyone reading the code later

6. Querying for transactions AND account existance in one `SELECT` query

- The `GET /transactions/history/:account_id` endpoint requires we differentiate between the cases where an account does not exist (404
  response) vs the account existing but not having any transactions
- The following SQL query demonstrates how a single query can retrieve both all transactions(even zero transactions) AND also retrieve
  the status of the associated account

```
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
 acc.uuid = ${account_uuid}
order by
 tx.recorded_et

```

- If the resultset is empty OR the first column is NULL, then the account does not exist in the _accounts_ table
- If the resultset is non-empty AND the first column of any row (since they will be identical) is non-NULL, then every
  row col 2-7 is a transaction associated with the account identified by `account_uuid`

5. Configuration

- Will possibly need config for: local JDBC or localstack config, maybe # of queues if decide to do an N-queues implementation

## Implementation design

### Nanobank System Operation-state Model

**This is how I like to description microservices: by describing invariant rules that relate the behaviors of the different endpoints to each other
in a formalized way, using first-order-logic-ish psuedo-code.  For example, a rule could state that a "getter" endpoint MUST return the entity value
posted to a "setter" endpoint previously.**

**This description indicates how a working implementation of the service behaves, and is not intended to say anything about how
the service endpoints are implemented.**


- The set of 3 service operations are applicable to a very simple service state model composed of 2 related sets of entities

```
// psuedo-code
NanobankState {
  accounts: Set[Account]
  transactions: Set[Transaction]
}
```

- At each moment in time the system state can be fully represented as a `NanobankState` instance
- A service instance has different states across time, and there is a set of invariant rules (below) relating
  the service states before and after the application of individual service operations


- `Account` type
   - An `Account` has an _id_ value of some comparable, opaque type `AccountID`
     - In my service implementation I assume the `AccountID` type is bidirectionally encodable as a UUID
   - An `Account` has a _balance_ value representable as a decimal value
   - I'm throwing in a _name_ and _owner_ (name) field just because the table schema looks sad and empty without them

```
// psuedo-code
Account {
  id: AccountID
  balance: decimal,
  name: string,
  owner: string
}
```

- `Transaction` type
  - A `Transaction` has a _recorded_et_ value of some ordered `Timestamp` type
    - In my service implementation I assume the `Timestamp` type is bidirectionally encodable as a `long` valued Unix epoch time
    - _recorded_et_ is used to order a service's `transactions` set, and to make sure earlier transactions cannot be inserted after a later transaction has been recorded for the same account 
  - A `Transaction` has an _account_id_ value of type `AccountId`, associating the transaction with one account
  - A `Transaction` has an _amount_ value representable as a decimal value
  - A `Transaction` has a _description_ value that is bidirectionally encodable as a text string of length <= 255 characters
  - A `Transaction` has an _id_  value of some comparable type `TransactionID`
    - In my service implementation I assume the `TransactionID` type is bidirectionally encodable as a UUID

```
// psuedo-code
Transaction {
  id: TransactionID
  recorded_et: Timestamp
  account_id: AccountID
  amount: decimal
  description: string
}
```

#### Operation applications relate a system's state values over time

- Let `T0: Timestamp` and `T1: Timestamp` be two timestamps where `T1` is after `T0` (ie, `T0` < `T1`)
- Let `S0` be the system state at `T0`, and `S1` be the system state at time `T1`
- 0 or more valid operations `ops: List[Validated[Operation]]` have been applied to the system  between `T0` and `T1`  
  - `S1` is the result of applying these valid operations to a system beginning in state `S0`
  - We could represent this relationship in pseudo-code as:

```
S1 = ops.fold(S0) { (prior_state, operation) => prior_state.apply(operation) }
```

#### Invariant rules on `NanobankState`

- The following invariants MUST be true for every system state over time
- That is, if a service's lifecycle is represented as a sequence of `NanobankState` instances, a valid
  system lifecycle MUST only include `NanobankState` instances which validate under the following rules 

1. The _accounts_ entity ID set strictly expands over time

- That is, `S1.accounts.map(_.id)` MUST be a superset of `S0.accounts.map(_.id)`
- Which might also be described by saying "accounts can't be deleted"

2. The _transactions_ value set strictly expands over time

- That is, `S1.transactions` MUST be a superset of `S0.transactions`
- Which might also be described by saying "transactions can't be deleted or changed"

3. `Transaction`s may only be associated with extant `Account`s

- All `s.transactions` elements MUST have `account_id` values equal to an `AccountID` value present in `accounts.map(_.id)`
- Given a valid system state `s: NanobankState`, the following MUST be true:

```
s.transactions.map(_.account_id).forAll(s.accounts.map(_.id).contains(_))
```

4. An `Account`'s balance MUST equal the sum of all transactions associated with the account

- Given a valid system state `s: NanobankState`, the following MUST be true:

```
s.accounts.fold(true) { (result, account)  =>
   result && s.transactions.filter(_.account_id == account.id).map(_.amount).sum == account.balance
}
```

- This implies that an `Account` with no transactions MUST have a zero balance
  - In real life an account can have an initial balance that is non-zero, but to write logically correct
    invariant rules that allow for non-zero initial balances adds complexity not needed to complete this project

#### Operation-state relationship rules

- Operation-state rules are rules that relate the system states _before_ and _after_ each operation is applied to a system
- An operation application on a system can be represented by a 5-tuple: `(sysctx: SystemInfo, input: Any, prior_state: NanobankState, new_state: NanostateBank, output: Any)`
  - `sysctx` is an object holding info about the system at the time of operation application, such as the current timestamp, etc. 
  - `input` is the input value to the operation; for web services, this is the full state of an HTTP request
  - `prior_state` is the `NanobankState` state of the system prior to the operation application
  - `new_state` is the `NanobankState` state of the system after the successful operation application
  - `output` is the response value for the operation application; for web services, this is the full state of the HTTP response
- A _valid_ operation application is a 4-tuple that conforms to the operation-state relationship rules
- There are two distinct kinds of _invalid_ operation applications
  - a "Bad Request" is an operation application that does not conform to the relationship rules below, and where all non-conformance can be determined from the `input` value alone
  - an "Unprocessable Entity" is an operation application that is _NOT_ a "Bad Request",  and does not conform to the relationship rules below, and where non-conformance can be determined from the tuple `(input, prior_state)`  

1. `GET /account/:accountId`(termed "get account info")
- This operation is a "read" operation, meaning the `prior_state` and `new_state` are identical
- The `output` value must be a projection of the `Account` state of the object with an ID matching `:accountId` URL param in the `intput` request
  - If no such `Account` object exists in `prior_state` then the output is a proscribed 404 response

2. `GET /transaction/history/:accountId`
- This operation is a "read" operation, meaning the `prior_state` and `new_state` are identical
- The `output` value must be a copy of the `Transaction` states of the transactions with an `account_id` value matching `:accountId` URL param in the `intput` request
   - If no such `Account` object exists in `prior_state` then the output is a proscribed 404 response

3. `POST /transaction`
- This is the only "modify" operation, meaning the `prior_state` and `new_state` of a valid operation application may differ;  these states are related by the rules given below
- The `input` includes the following named values:
  - _account_id_: an `AccountID` value referring to an element of `prior_state.accounts` with a matching `id` value
  - _amount_: a decimal value -- see the "Cannot overdraw account", and "No zero-valued transactions" rules below
  - _description: a free text value with no restrictions
- "Cannot overdraw account" rule: an operation application MUST NOT reduce the balance of an account below zero
  - A operation application MAY increase the balance of an account, even if the final account balance is below zero after the operation application
  - Logically, this rule states that the following expression must hold true:

```
given operation = (sysctx, input, prior_state, new_state, output)

let target_account = prior_state.find(_.id = input.account_id)

(
     ((input.amount < 0) && (target_account.balance + input_amount >= 0))
  || (input.amount > 0)
) 
```

- "No zero-valued transactions" rule: A zero-valued transaction seems wrong to me. I'm just going to assume they aren't allowed.

```
given operation = (sysctx, input, prior_state, new_state, output)

input.amount != 0
```

- Invariant relationship between prior and new system states
  - The account balance of the target account is updated with the new transaction amount
  - Given the invariant rule that an account balance must be equal to the sum of transactions, this rule can most
    succinctly be expressed as a relationship between the transactions collections between the prior and new states:

```
given operation = (sysctx, input, prior_state, new_state, output)

let new_tx = Transaction(
  id = fresh_uuid,
  posting_time = sysctx.time,
  account_id = input.account_id,
  amount = input.amount,
  description = input.description
)

let prior_target_account = prior_state.accounts.find(_.id == intput.account_id)
let new_target_account = Account(
  id = input.account_id,
  balance = new_state.transactions.filter(_.account_id == input.account_id).map(_.amount).sum + input.amount
)

new_state.accounts == prior_state.accounts - prior_target_account + new_target_account 
new_state.transactions == prior_state.transactions :+ new_tx
 
output == null
```

## Original Assignment and Additional Notes

You are tasked with building a bank account management application with the following API endpoints:
1. `GET /account/{account_id}`

   Description: Retrieve the balance and user details of a bank account.

   Method: GET

   URL Parameters:
   - account_id (string): The unique identifier of the bank account.

   Response:
   - `200 OK`: Returns the balance and user details of the specified bank account in JSON format.
   - `404 Not Found`: If the account does not exist.

>   Additional Notes:
>
>   _Response Body (JSON):_ **(PROPOSED)**
>
>   ```
>   {
>     "uuid": "string",
>     "balance": number,
>     "name": "string",
>     "owner": "string"
>   }
>   ```

2. `POST /transaction`

   Description:
   - Create a new transaction for a bank account.
   - If the account does not have enough funds, the transaction should fail.
   - The system should also consider all transactions that are in the PENDING state.

   Method: POST

   Request Body (JSON):

   `{ "account_id": "string", "amount": 100.0, "description": "string" }`

   Response:
   - `201 Created`: If the transaction is successful, it returns the transaction details in JSON format
   - `400 Bad Request`: If the request body is invalid
   - `404 Not Found`: If the account does not exist
   - `422 Unprocessable Entity`: If the transaction fails due to insufficient funds or other reasons
   
>  Additional Notes:
> 
>  - Information model:
>    - There is a 1:n relationship between Account => Transaction
>    - Transaction state includes 5 values:
>      - transaction_id (integer): service-assigned unique ID of the transaction; unique amongst transactions of the same account; transaction ID values are comparatively consistent with `transaction_received_dt` value ordering 
>      - transaction_declaration_dt (integer - epoch time): service-assigned datetime the transaction is initially received; not guaranteed to be unique across transactions
>      - amount (number)
>      - description (string, up to 255 chars)
>      - status (string {"PENDING" | "POSTED"})
>  - Invariant rules: 
>    - _Withdrawals_ are transactions with negative `"amount"` values
>    - _Deposits_ are transactions with positive `"amount"` values
>    - Transactions with 0 `"amount"` values are not valid
>      - Always yield `400 Bad Request` HTTP response 
>    - Deposit transactions should always succeed if account exists
>    - Withdrawal transactions should only succeed if sum of POSTED and PENDING transaction `"amount"` is > transaction `"amount"` value
>  - Lifecycle rules:
>    - Posted transactions are in PENDING state when the transaction operation is completed
>      - Need some way to promote PENDING transactions to POSTED
>      - Either separate endpoint or
>    - Account instances are not changeable by any service operations -- they are modified through OOB operations on the backing store,
>      visible to the web  service system through the backing store only

3. `GET /transaction/history/{account_id}`

   Description: Retrieve the history of transactions for a bank account.

   Method: GET

   URL Parameters:
   - account_id (string): The unique identifier of the bank account.

   Response:
   - `200 OK`: Returns the list of transaction history for the specified bank account in JSON format.
   - `404 Not Found`: If the account does not exist.

   Request Body (JSON):

   ```
   {
     "transaction_id": 1,
     "transaction_declaration_dt": 1,
     "amount": 100.0,
     "description": "string",
     "status": "string"
   }
   ```

Your implementation should meet the following requirements:

- Implement the API endpoints using any programming language and framework of your choice.
- Ensure that transactions are processed correctly, in order (relative to timestamp), considering both the available balance and pending
transactions.
- Make the application stateless, meaning that it should not store session data between requests. The deploy target for this application is
Kubernetes.
