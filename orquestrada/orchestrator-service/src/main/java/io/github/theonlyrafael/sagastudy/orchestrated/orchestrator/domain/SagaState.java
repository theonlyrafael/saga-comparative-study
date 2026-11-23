package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain;

public enum SagaState {
    RESERVANDO_SALDO, RESERVANDO_ATIVO, CONFIRMANDO_LIQUIDACAO, EFETIVANDO,
    COMPENSANDO, RECUPERACAO_PENDENTE, CONCLUIDA, REJEITADA
}
