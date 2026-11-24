package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain;

public enum SagaStep {
    RESERVE_BALANCE("ReservarSaldo"),
    RESERVE_ASSET("ReservarAtivo"),
    RELEASE_ASSET("DesfazerReservaAtivo"),
    RELEASE_BALANCE("DesfazerReservaSaldo"),
    SETTLE("ConfirmarLiquidacao"),
    CONFIRM_BALANCE("ConfirmarReservaSaldo"),
    CONFIRM_ASSET("ConfirmarReservaAtivo"),
    FINALIZE_ORDER("FinalizarOrdem");

    private final String commandType;

    SagaStep(String commandType) {
        this.commandType = commandType;
    }

    public String commandType() {
        return commandType;
    }
}
