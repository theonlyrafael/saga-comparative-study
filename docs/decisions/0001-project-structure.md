# 0001 — Organização do projeto

Adotado: monorepositório com as seis pastas da seção 11 do PDF. Maven multimódulo
por modalidade; `orquestrada/pom.xml` será parent/agregador, sem aplicação na raiz.
Cada serviço produz seu próprio JAR. Um Maven Wrapper por modalidade.

Group: `io.github.theonlyrafael.sagastudy`. Código e pacotes em inglês; documentação
e commits em português. Java 21. A preparação foi explicitamente ampliada para
incluir classes de inicialização, POMs, infraestrutura e configuração, sem domínio.
O PDF permanece byte a byte igual ao anexo. O guia é referência e não substitui a
autorização atual nem os limites funcionais da especificação.
