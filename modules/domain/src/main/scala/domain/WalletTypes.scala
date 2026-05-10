package domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import scalapb.TypeMapper

object WalletTypes:
  // Transaction amounts must be strictly > 0
  type TransactionAmount = Long :| Positive

  // Balances can be 0, so they must be >= 0
  type Balance = Long :| GreaterEqual[0L]

  // TypeMappers for ScalaPB to automatically convert between raw Long and our Iron types
  // Note: For TypeMapper, the source type is Long and target type is TransactionAmount.
  given TypeMapper[Long, TransactionAmount] = TypeMapper[Long, TransactionAmount](l => l.refineUnsafe[Positive])(identity)
  given TypeMapper[Long, Balance] = TypeMapper[Long, Balance](l => l.refineUnsafe[GreaterEqual[0L]])(identity)
