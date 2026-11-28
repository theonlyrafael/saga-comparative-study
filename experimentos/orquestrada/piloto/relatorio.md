# Piloto de carga da Saga orquestrada

A carga de referência selecionada para esta modalidade é **2 compras/s**. Nas cinco rodadas de refinamento, a vazão mediana foi de 2,01 Sagas/s e a p99 mediana ficou em 7,13 s. As 20 rodadas da triagem e do refinamento produziram **3.235 ordens liquidadas**, cada uma com uma única liquidação e suas duas reservas confirmadas. Os débitos somaram 82.492,50. Ao fim de cada rodada, saldo e estoque estavam reconciliados, todas as Outboxes tinham publicado e as filas estavam vazias. [Protocolo funcional e critérios de medição](../../../docs/piloto-de-carga.md)

## Triagem de 27 de novembro de 2026

O k6 enviou compras de uma unidade a 25,50 por ordem. Cada patamar teve cinco rodadas com 12 s de aquecimento e 30 s de medição. Das 2.110 compras que chegaram na triagem, 1.500 entraram nas janelas de latência. Todas as 2.110 chegaram a `LIQUIDADA` e foram reconciliadas nos cinco bancos, inclusive as que terminaram depois que o k6 parou de enviar requisições. [Manifesto e configuração](2026-11-27/triagem/manifest.json)

| Carga oferecida (compras/s) | Ordens nas janelas | Vazão mediana (Sagas/s) | p50 (s) | p95 (s) | p99 (s) | Rodadas com ≥ 90% da taxa oferecida |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 150 | 1,00 | 5,90 | 6,55 | 6,57 | 5/5 |
| 3 | 450 | 2,87 | 7,42 | 8,58 | 8,83 | 4/5 |
| 6 | 900 | 1,70 | 50,55 | 53,71 | 54,12 | 0/5 |

Cada percentil na tabela é a mediana dos cinco percentis por rodada. A 6 compras/s, a mediana de ordens pendentes subiu de 61 no início para 191 no fim da janela. A mediana do tempo entre o fim do envio e a última conclusão chegou a 47,72 s. Essa fila explica como todas as compras puderam liquidar mesmo com uma vazão de apenas 1,70 Saga/s na janela. [Medições e SHA-256 das 15 rodadas](2026-11-27/triagem/summary.json)

A 3 compras/s, a p95 variou de 8,12 a 23,34 s. Isso motivou o refinamento a 2 compras/s, com janelas maiores. O Prometheus também registrou CPU e heap nas cinco JVMs. No orquestrador, a mediana das médias de heap foi de 53,4 MiB a 1 compra/s e 55,8 MiB a 6 compras/s. No mesmo intervalo de cargas, a fração de CPU do processo passou de 0,62% a 2,12%. As séries por serviço e instante estão nos arquivos de rodada.

## Refinamento a 2 compras/s

O refinamento partiu de bancos novos, com os mesmos JARs, imagens, recursos e configurações registrados na triagem. Foram cinco rodadas, cada uma com 12 s de aquecimento e 100 s de medição. Todas as **1.125 ordens** liquidaram. As 1.000 recebidas nas janelas entraram nos percentis. Nas cinco rodadas, o patamar atingiu o critério de sustentação.

| Vazão mediana (Sagas/s) | p50 (s) | p95 (s) | p99 (s) | IQR (s) | Pendentes no início e no fim da janela¹ | Drenagem¹ (s) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2,01 | 5,92 | 7,00 | 7,13 | 0,86 | 12 e 12 | 5,94 |

¹ Medianas entre as cinco rodadas. Em todas as 20 rodadas, o k6 registrou zero chegadas descartadas e o coordenador registrou zero reenvios por timeout. [Manifesto do refinamento](2026-11-27/refinamento/manifest.json) · [Medições e SHA-256 das cinco rodadas](2026-11-27/refinamento/summary.json)

## Parâmetros para a próxima coleta

| Compras/s | Uso indicado neste host | CV da vazão | CV da p95 | Repetições estimadas² |
| ---: | --- | ---: | ---: | ---: |
| 1 | Carga baixa | 2,36% | 7,91% | 5 |
| 2 | Referência de carga sustentada | 0,57% | 2,03% | 5 |
| 3 | Patamar superior, sensível à variação entre rodadas | 16,93% | 57,69% | 128 |
| 6 | Estresse, com acúmulo de ordens pendentes | 3,10% | 3,44% | 5 |

² A estimativa usa a variabilidade entre rodadas e o alvo de precisão definido no protocolo. A 3 compras/s, a primeira rodada teve p95 de 23,34 s e as outras quatro ficaram entre 8,12 e 9,17 s. A estimativa de 128 inclui essa dispersão. Vale recalibrar esse patamar no ambiente da comparação antes de definir o tamanho da campanha.

Para medir a cauda com pelo menos 200 compras por rodada, a janela de referência é de 100 s a 2 compras/s e de 200 s a 1 compra/s. A janela de 100 s foi exercitada neste piloto. A de 200 s a 1 compra/s é uma projeção a conferir na coleta. A 6 compras/s, manter a duração da sobrecarga fixa permite interpretar o tempo de drenagem. A calibração conclui a etapa orquestrada. A equivalência funcional e a comparação de desempenho entre modalidades dependem da implementação coreografada e da reprodução das condições no ambiente comum.

## Ambiente e reprodução

Os cinco serviços rodaram em Java 17, cada um com uma instância PostgreSQL 17.11. O mesmo host abrigou o RabbitMQ 4.4.0, o k6 1.3.0 e o Prometheus 3.5.0. O ambiente teve cota de CPU equivalente a quatro núcleos e limite de memória de 32 GiB. Cada rodada começou com saldo e estoque novos para o comprador e o ativo. O gerador, os hashes dos JARs, a configuração dos serviços e a ordem dos patamares estão nos manifestos.

Os arquivos comprimidos preservam a contagem de recibos do gerador, as linhas exportadas dos bancos e as amostras de recursos. Os manifestos usam UTC. As duas execuções ocorreram em 27 de novembro no horário de São Paulo. O cálculo dos percentis e a conferência dos efeitos financeiros podem ser repetidos sem executar as aplicações. [Comandos e definições das métricas](../../../docs/piloto-de-carga.md)
