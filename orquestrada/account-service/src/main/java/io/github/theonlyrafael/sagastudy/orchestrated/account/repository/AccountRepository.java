package io.github.theonlyrafael.sagastudy.orchestrated.account.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/** Monetary primitives must run inside the reservation service transaction. */
@Transactional(propagation = Propagation.MANDATORY)
public interface AccountRepository extends Repository<Account, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.id = :accountId")
    Optional<Account> findForUpdate(@Param("accountId") String accountId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET available_balance = available_balance - :amount,
                   reserved_balance = reserved_balance + :amount
             WHERE account_id = :accountId
               AND :amount > 0
               AND available_balance >= :amount
            """, nativeQuery = true)
    int reserve(
            @Param("accountId") String accountId,
            @Param("amount") BigDecimal amount
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET reserved_balance = reserved_balance - :amount,
                   total_consumed = total_consumed + :amount
             WHERE account_id = :accountId
               AND :amount > 0
               AND reserved_balance >= :amount
            """, nativeQuery = true)
    int consume(
            @Param("accountId") String accountId,
            @Param("amount") BigDecimal amount
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET reserved_balance = reserved_balance - :amount,
                   available_balance = available_balance + :amount
             WHERE account_id = :accountId
               AND :amount > 0
               AND reserved_balance >= :amount
            """, nativeQuery = true)
    int release(
            @Param("accountId") String accountId,
            @Param("amount") BigDecimal amount
    );
}
