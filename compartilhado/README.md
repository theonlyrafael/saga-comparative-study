# Compartilhado

Espaço para contratos versionados e regras puras quando necessários. Não contém
aplicação, entidades JPA, repositórios, banco compartilhado ou lógica de coordenação.

## Mensageria v1

O contrato em [envelope.schema.json](mensageria/v1/envelope.schema.json) segue JSON
Schema 2020-12. Valide o envelope completo, habilitando os formatos UUID e
date-time, antes de produzir efeitos. O tipo é o nome do PDF em português.
schemaVersion vale 1. messageId identifica o envelope e pode mudar no reenvio.
operationId identifica a operação lógica e permanece estável durante retries.
orderId e sagaId correlacionam a compra. occurredAt usa data/hora com fuso.

O payload inclui o snapshot da ordem. Preço e total usam strings decimais positivas
com duas casas para preservar centavos no transporte JSON. Na futura integração,
converter o BigDecimal capturado com toPlainString(). O domínio continua responsável
por conferir total = preço × quantidade. Os campos adicionais de recurso e motivo
dependem do tipo de mensagem. Os códigos de recusa incluem SALDO_INSUFICIENTE e
ATIVO_INSUFICIENTE. LiquidacaoFalhou requer ausência comprovada de decisão durável.

A routing key é o valor de type. Os oito comandos do orquestrador passam por
saga.v1.commands. OrdemRecebida e as respostas dos serviços passam por
saga.v1.responses rumo à fila saga.v1.orchestrator. A topologia declara filas
duráveis por serviço e DLQs por destino. Rejeição sem requeue encaminha a mensagem
para a DLQ do destinatário. Uma chave desconhecida retorna como não roteável ao
publicador que usa mandatory. O ACK e a publicação com confirms entram na
implementação dos participantes.

Para executar os testes do contrato, use Python 3.11+ com o pacote jsonschema 4
e rode na raiz:

    python3 -m unittest discover -s compartilhado/mensageria/tests
