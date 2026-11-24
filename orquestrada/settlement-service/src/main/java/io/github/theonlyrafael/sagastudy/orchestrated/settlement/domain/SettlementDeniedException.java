package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

/** Only a durable denial of this exact operation authorizes release of its reservations. */
public class SettlementDeniedException extends RuntimeException {
    public SettlementDeniedException() {
        super("Settlement denied before commitment");
    }
}
