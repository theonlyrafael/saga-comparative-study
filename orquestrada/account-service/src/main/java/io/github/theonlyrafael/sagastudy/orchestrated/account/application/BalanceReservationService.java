package io.github.theonlyrafael.sagastudy.orchestrated.account.application;

import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservation;
import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationStatus;
import io.github.theonlyrafael.sagastudy.orchestrated.account.repository.AccountRepository;
import io.github.theonlyrafael.sagastudy.orchestrated.account.repository.BalanceReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.ACCOUNT_NOT_FOUND;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.INSUFFICIENT_BALANCE;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.INVALID_TRANSITION;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.OPERATION_CONFLICT;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.RESERVATION_NOT_FOUND;

@Service
public class BalanceReservationService {

    private final AccountRepository accounts;
    private final BalanceReservationRepository reservations;

    public BalanceReservationService(
            AccountRepository accounts,
            BalanceReservationRepository reservations
    ) {
        this.accounts = accounts;
        this.reservations = reservations;
    }

    @Transactional
    public UUID reserve(ReserveBalanceCommand command) {
        Objects.requireNonNull(command, "command is required");

        lockAccount(command.accountId());

        var existing = reservations.findByOperationId(command.operationId());

        if (existing.isPresent()) {
            BalanceReservation reservation = existing.get();

            if (!reservation.matches(
                    command.orderId(),
                    command.sagaId(),
                    command.accountId(),
                    command.amount()
            )) {
                throw new BalanceReservationException(
                        OPERATION_CONFLICT,
                        "operationId was already used with different reservation data"
                );
            }

            // Replays return the original identifier, including after completion.
            return reservation.getId();
        }

        if (reservations.existsByOrderIdOrSagaId(
                command.orderId(),
                command.sagaId()
        )) {
            throw new BalanceReservationException(
                    OPERATION_CONFLICT,
                    "The order or saga already has a balance reservation"
            );
        }

        int changedRows = accounts.reserve(
                command.accountId(),
                command.amount()
        );

        if (changedRows != 1) {
            throw new BalanceReservationException(
                    INSUFFICIENT_BALANCE,
                    "The account does not have enough available balance"
            );
        }

        BalanceReservation reservation = BalanceReservation.reserve(
                command.orderId(),
                command.sagaId(),
                command.operationId(),
                command.accountId(),
                command.amount()
        );

        return reservations.saveAndFlush(reservation).getId();
    }

    @Transactional
    public void confirm(UUID reservationId) {
        BalanceReservation reservation = findAfterLockingAccount(reservationId);

        if (reservation.getStatus() == BalanceReservationStatus.CONFIRMED) {
            return;
        }

        requireReserved(reservation);

        int changedRows = accounts.consume(
                reservation.getAccountId(),
                reservation.getAmount()
        );

        requireBalanceUpdate(changedRows);

        reservation.confirm();
        reservations.saveAndFlush(reservation);
    }

    @Transactional
    public void release(UUID reservationId) {
        BalanceReservation reservation = findAfterLockingAccount(reservationId);

        if (reservation.getStatus() == BalanceReservationStatus.RELEASED) {
            return;
        }

        requireReserved(reservation);

        int changedRows = accounts.release(
                reservation.getAccountId(),
                reservation.getAmount()
        );

        requireBalanceUpdate(changedRows);

        reservation.release();
        reservations.saveAndFlush(reservation);
    }

    private BalanceReservation findAfterLockingAccount(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId is required");

        // Read only the immutable account identifier before acquiring its lock.
        String accountId = reservations.findAccountId(reservationId)
                .orElseThrow(() -> new BalanceReservationException(
                        RESERVATION_NOT_FOUND,
                        "Balance reservation was not found"
                ));

        lockAccount(accountId);

        // Load the reservation state only after obtaining the account lock.
        return reservations.findById(reservationId)
                .orElseThrow(() -> new BalanceReservationException(
                        RESERVATION_NOT_FOUND,
                        "Balance reservation was not found"
                ));
    }

    private void lockAccount(String accountId) {
        accounts.findForUpdate(accountId)
                .orElseThrow(() -> new BalanceReservationException(
                        ACCOUNT_NOT_FOUND,
                        "Account was not found"
                ));
    }

    private void requireReserved(BalanceReservation reservation) {
        if (reservation.getStatus() != BalanceReservationStatus.RESERVED) {
            throw new BalanceReservationException(
                    INVALID_TRANSITION,
                    "The reservation already reached an incompatible terminal state"
            );
        }
    }

    private void requireBalanceUpdate(int changedRows) {
        if (changedRows != 1) {
            // This is an invariant violation, not a normal insufficient-balance refusal.
            throw new IllegalStateException(
                    "Reserved balance is inconsistent with the stored reservation"
            );
        }
    }
}
