# Piloto de carga da Saga orquestrada

O piloto calibra a taxa de chegada de compras, a janela de observação e a quantidade inicial de repetições da modalidade orquestrada. O desfecho esperado de cada compra é uma ordem `LIQUIDADA`, com uma Saga `CONCLUIDA`, um débito e uma transferência de ativo. A equivalência com a modalidade coreografada poderá ser medida quando as duas implementações estiverem disponíveis. Os cenários de falha têm uma execução própria. [Matriz C1–C6](cenarios-de-falha.md)

## Reproduzir

Use Linux, JDK 17, Git, Python 3.11 ou superior, Bash, curl e Docker Compose com suporte a `--wait`. Prepare `infra/.env` e execute os comandos abaixo na raiz. O executor cria o diretório de resultados e recusa um caminho já existente. [Configuração local](execucao-local.md#executar-o-bootstrap)

```bash
(cd orquestrada && ./mvnw clean verify)
bash infra/scripts/run-load-pilot.sh /tmp/piloto-orquestrado
python3 -B experimentos/piloto/report.py /tmp/piloto-orquestrado > /tmp/resumo-recalculado.json
cmp /tmp/piloto-orquestrado/summary.json /tmp/resumo-recalculado.json
```

Para repetir o refinamento de 2 compras/s com janelas de 100 s, use um segundo diretório. Cada chamada prepara novos bancos e um novo broker.

```bash
PILOT_PLAN=experimentos/piloto/refinamento.json \
  bash infra/scripts/run-load-pilot.sh /tmp/piloto-orquestrado-2rps
```

O executor sobe cinco JVMs, cinco instâncias independentes de PostgreSQL, um RabbitMQ e um Prometheus no mesmo host. As portas são atribuídas dinamicamente em loopback. O k6 usa a rede do host. Cada serviço acessa apenas o próprio banco com seu usuário de aplicação. Ao terminar, o executor encerra as JVMs e remove os contêineres e volumes do projeto temporário. O diretório de resultados permanece disponível.

## Desenho da rodada

As seções 3.2, 3.4, 3.7 e 3.8 do *Plano de Trabalho de Pesquisa 2026–2027* orientam a topologia, os patamares crescentes, o aquecimento e as repetições. Os valores escolhidos abaixo são parâmetros da calibração local da orquestrada.

- O k6 envia POSTs com taxa de chegada aberta. A resposta esperada é HTTP 202 com o recibo da ordem. Cada iteração usa uma chave de idempotência exclusiva.
- O primeiro bloco percorre 1, 3 e 6 compras/s em ordem crescente. Os outros quatro blocos repetem todos os patamares em ordem embaralhada com a semente 20261127. As rodadas são sequenciais. Para testar outra configuração, informe o caminho de uma cópia do plano em `PILOT_PLAN`. [Plano executável](../experimentos/piloto/plan.json)
- Cada rodada usa um comprador e um ativo exclusivos. O seed `pilot-v1` fornece 10.000 unidades, saldo para comprar todas e preço unitário de 25,50. Todas as compras da rodada disputam a mesma conta e a mesma posição da contraparte. As rodadas começam com recursos equivalentes e preservam os registros das anteriores.
- Cada rodada começa com 12 s de aquecimento. A triagem mede os 30 s seguintes. O refinamento mede 100 s para obter cerca de 200 compras na janela de 2 compras/s. Depois, o gerador para. O executor aguarda até 120 s para as Sagas terminarem, as Outboxes serem publicadas e as filas de serviço e de erro esvaziarem. A próxima rodada começa após a conferência dos bancos. [Plano do refinamento](../experimentos/piloto/refinamento.json)
- As JVMs usam dois processadores visíveis, heap inicial de 48 MiB e máximo de 256 MiB cada. O pool de banco comporta até cinco conexões por serviço. A Outbox consulta pendências a cada 1 s e o reconciliador, a cada 5 s, com prazo de 30 s por passo. O manifesto registra essas configurações, as versões das imagens, a revisão Git, os hashes dos JARs, a CPU e a memória do host. A amostragem de traces fica em 1,0, com exportação OTLP desligada em todas as rodadas.

## Como ler as métricas

| Medida | Definição operacional |
| --- | --- |
| Latência de ponta a ponta | Intervalo entre a criação da ordem registrada em `order_received_at` e o encerramento persistido em `saga_instances.updated_at`. A coorte reúne as ordens recebidas dentro da janela de medição, inclusive as que terminam durante a drenagem. Os relógios usam o mesmo host. |
| p50, p95, p99 e IQR | Percentis por rodada com interpolação linear no índice `(n−1) × p`. O IQR é a diferença entre p75 e p25. O resumo dos patamares usa as medianas das métricas por rodada. |
| Throughput | Número de Sagas que efetivamente terminam na janela de medição, dividido pela duração dessa janela em segundos. Inclui uma compra do aquecimento que conclua nessa janela. O limite inicial é inclusivo e o final é exclusivo. |
| Pendências e drenagem | Ordens já recebidas e ainda pendentes em cada limite da janela. A duração da drenagem vai do fim da janela à última conclusão, com mínimo de zero. |
| Erros e consistência | O executor compara tentativas, recibos e operações persistidas. Une os cinco bancos pelos identificadores de ordem, Saga, liquidação e reservas. Exige seis passos concluídos, uma liquidação por ordem, conservação do saldo e do estoque e nenhuma reserva ativa. |
| CPU e memória | O Prometheus coleta a cada 2 s a fração de CPU do processo e o heap JVM utilizado em bytes. O resumo guarda a média das amostras da janela por serviço. As séries originais estão nos arquivos de rodada. |

Uma rodada válida exige zero chegadas descartadas pelo k6, um recibo válido por tentativa, drenagem dentro do limite, amostras dos cinco serviços e consistência financeira de todas as ordens. A tolerância de uma iteração no total programado acomoda a fronteira temporal do gerador. Em caso de falha na validação, o executor preserva a rodada coletada e encerra a execução com erro.

## Regras de calibração

Um patamar é considerado sustentado neste ambiente quando ao menos quatro das cinco rodadas concluem, na janela, pelo menos 90% da taxa oferecida e todas passam nos critérios de validade. Um patamar acima desse limite de operação pode servir de ensaio de estresse. A incerteza de uma p99 calculada com poucas compras cresce rapidamente. Para a próxima coleta, dimensionar a janela para pelo menos 200 ordens no patamar estável mais baixo e conferir o número efetivamente observado.

A dispersão entre rodadas é medida pelo coeficiente de variação amostral de throughput e de p95. Para orientar o número de repetições, usa-se o maior dos dois coeficientes na aproximação `n = max(5, ceil((1,96 × CV / 0,10)²))`. O alvo é uma semilargura relativa de aproximadamente 10% para a média da métrica por rodada. A estimativa será revista com a variabilidade das duas modalidades na infraestrutura de comparação.

## Arquivos de evidência

`manifest.json` registra o ambiente e a ordem de execução. Cada `round-NN.json.gz` preserva o vínculo com o manifesto por SHA-256, o resumo do gerador, as linhas exportadas pelos cinco bancos e as amostras de Prometheus. O `summary.json` contém as métricas derivadas, a dispersão, a indicação dos patamares sustentados e o SHA-256 de cada arquivo bruto. A análise pode ser repetida sem subir os serviços. Os testes dos cálculos rodam com `python3 -B -m unittest discover -s experimentos/piloto`.

O relatório reúne as 20 rodadas, as medições de latência e a escolha dos patamares estáveis e de estresse. [Resultados da modalidade orquestrada](../experimentos/orquestrada/piloto/relatorio.md)
