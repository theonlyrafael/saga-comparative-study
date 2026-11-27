# Cenários de falha da Saga orquestrada

A matriz verifica o que acontece com uma compra quando um serviço cai, uma resposta atrasa ou um efeito precisa ser desfeito. C1 a C6 seguem a ordem dos seis tipos da seção 3.5 do *Plano de Trabalho de Pesquisa 2026–2027*. Os desfechos seguem as seções 5, 9 e 12 da especificação. Os mecanismos e prazos abaixo foram escolhidos para reproduzir as falhas no ambiente funcional. A calibração de carga e a definição das janelas da comparação ficam para o piloto. [Especificação da Saga orquestrada](specifications/orchestrated-saga-specification.pdf)

## Reprodução

Use Git, Bash 4.1+, curl, jq, Python 3, JDK 17 e Docker com Compose. Prepare as credenciais locais em `infra/.env` e construa os cinco JARs. Cada chamada cria um projeto Compose com volumes vazios, portas em loopback e cinco JVMs. O PostgreSQL local hospeda cinco bancos, cada qual com seu próprio usuário. Ao terminar, o script remove os volumes desse projeto. [Configuração do ambiente](execucao-local.md#executar-o-bootstrap)

```bash
(cd orquestrada && ./mvnw clean verify)
bash infra/scripts/check-failure-scenarios.sh > matriz.json
jq -r '.cases[] | [.id, (.orders | map(.orderState) | join(", "))] | @tsv' matriz.json
```

Para repetir um caso, use, por exemplo, `bash infra/scripts/check-failure-scenarios.sh C3`. A execução isolada parte do mesmo seed. Na execução completa, as compras compartilham o seed e a verificação de cada caso inclui o saldo e o estoque acumulados. O progresso sai no terminal. O relatório JSON é emitido depois que todas as verificações passam. Se uma delas falhar, o comando termina com erro e informa o diretório dos logs.

A execução registrada usou Maven Wrapper 3.10.0, Docker 29.9.2 e Compose 2.40.3. Para repetir a configuração de memória e processadores das JVMs, defina `export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xms48m -Xmx256m'` antes de iniciar o ensaio. A coleta comparativa usará condições de infraestrutura e carga equivalentes nas duas modalidades.

## Entradas e injeções

O perfil `demo-v1` inicia `CLI001` com 1.000,00 e `CLI002` com 10,00. A contraparte dispõe de uma unidade de `ATV001` a 10,00 e dez de `ATV002` a 25,50. Cada POST compra uma unidade com uma chave de idempotência própria. C1, C2, C4, C5 e C6 usam `CLI001` e `ATV002`.

| Caso | Injeção e ponto de observação | Duração e reversão | Desfecho exigido |
| --- | --- | --- | --- |
| C1 · Queda da custódia | Reter a liquidação até as duas reservas existirem. Encerrar a custódia com `SIGKILL` e permitir a liquidação. Conferir a decisão `CONFIRMED`, o saldo efetivado, o ativo reservado e a ordem `RECEBIDA`. | Manter a custódia fora do ar até observar esses quatro fatos e reiniciar a JVM sobre o mesmo banco. | Uma liquidação e uma transferência. A ordem chega a `LIQUIDADA` após a confirmação da custódia. |
| C2 · Latência na reserva de saldo | Antes do POST, outra transação bloqueia a linha de `CLI001` com `FOR UPDATE`. Verificar o consumidor bloqueado no PostgreSQL e o primeiro timeout de `ReservarSaldo` no coordenador. | Manter o bloqueio até observar pelo menos um reenvio com a ordem ainda `RECEBIDA`. Liberá-lo com rollback. | A operação reenviada consome 25,50 e transfere uma unidade uma única vez. |
| C3 · Negativa de negócio | Enviar duas compras de `ATV001` em paralelo e aguardar as duas reservas de saldo antes de habilitar a custódia. Em seguida, `CLI002` tenta comprar `ATV002` por 25,50. | As recusas decorrem dos recursos do seed e valem para uma ordem cada. Após a disputa, a Saga libera a reserva de saldo da compra sem estoque. | Uma das concorrentes liquida e a outra termina com `ATIVO_INSUFICIENTE`. A terceira termina com `SALDO_INSUFICIENTE` sem criar reservas. |
| C4 · Duplicação de comando | Após a liquidação, publicar novamente `ConfirmarReservaAtivo` no RabbitMQ. Repetir com novo `messageId` e o mesmo `operationId`. | Duas entregas adicionais. Aguardar a republicação da resposta durável após cada uma. | A Inbox associa dois identificadores de mensagem a uma única operação. A posição do comprador aumenta uma única vez. |
| C5 · Ausência de publicação | Subir ordens com o publicador desligado, aceitar o POST e encerrar a JVM antes da publicação. Conferir a ordem durável, o evento pendente e a ausência de Saga no coordenador. | Reiniciar ordens com o publicador habilitado e aguardar a confirmação do mesmo `messageId`. | A Outbox inicia a Saga. A compra liquida uma vez e repetir o POST devolve o recibo original. |
| C6 · Falha parcial durante a compensação | Após as duas reservas, desligar conta e negar a liquidação apenas dessa ordem. Confirmar a decisão durável `DENIED`, a liberação do ativo, o saldo ainda reservado e a ordem `RECEBIDA`. | Manter conta desligada até vencer o prazo de `DesfazerReservaSaldo`. Reiniciar o serviço para concluir a liberação. | A Saga libera primeiro o ativo e depois o saldo. Só então a ordem fica `REJEITADA` com `LIQUIDACAO_NEGADA`. |

O ensaio usa prazo de 3 s por passo, varredura a cada 1 s e `SAGA_RETRY_MAX_ATTEMPTS=5`, com atraso inicial de 1 s e máximo de 1 min. A espera por cada observação em banco tem limite de 90 s. A reversão de cada falha depende do estado observado. Os segundos registrados em `injection` descrevem a execução local, incluindo o tempo de detecção pelo teste.

Para exercitar também a perda de uma resposta já publicada, execute `bash infra/scripts/check-recovery.sh`. Esse ensaio descarta a confirmação de custódia no broker e exige que o coordenador recupere a resposta da operação original após reiniciar. [Ensaio de recuperação](execucao-local.md#verificar-a-compensação-e-o-reinício)

## Critérios de aprovação

- Para cada ordem, o GET, a Saga e as reservas referem-se aos mesmos identificadores. Cada resposta de etapa deve aparecer na sequência esperada. As duas confirmações após a liquidação podem chegar em qualquer ordem.
- Uma ordem `LIQUIDADA` exige saldo e ativo efetivados, quantidade e valor compatíveis, além de uma liquidação que referencie as duas reservas originais e corresponda ao identificador do GET.
- Uma ordem `REJEITADA` exige o motivo correto, ausência de liquidação e liberação das reservas que chegaram a existir. O teste distingue a recusa sem saldo da recusa após a reserva de saldo e da compensação de ambas as reservas.
- Após cada caso, os valores disponíveis, reservados e consumidos devem corresponder exatamente às compras liquidadas. O total de ativos do comprador e da contraparte é conservado. Todas as Outboxes devem terminar publicadas e as filas de serviço e de erro devem ficar vazias.

O relatório contém a revisão Git do executor, as versões em uso, o estado inicial, a evidência de cada injeção, os identificadores das ordens, as respostas aceitas e os saldos finais. Em `ledger.cashCents`, cada vetor traz disponível, reservado e consumido, em centavos. Em `ledger.positions`, traz unidades disponíveis da contraparte, unidades do comprador e total reservado. [Registro da execução completa](../experimentos/orquestrada/falhas/2026-11-26.json)
