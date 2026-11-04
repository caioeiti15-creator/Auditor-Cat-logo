# Auditor de Catálogo

Ferramenta em Java que encontra erros em catálogos de e-commerce e valida sugestões de títulos feitas com IA antes de aplicá-las.

O programa não publica nada e não altera o arquivo original. Ele gera um relatório e uma cópia revisada para uma pessoa conferir.

## O que ele verifica

| Tipo | Exemplo |
| --- | --- |
| `VAZIO` | Produto sem título, preço, SKU, categoria ou estoque |
| `PRECO` | Preço negativo, zero ou escrito como `R$ 69,90` |
| `ESTOQUE` | Estoque negativo ou escrito como `dez` |
| `CODIGO` | SKU usado em mais de um produto |
| `DUPLICADO` | Mesmo título, cor e tamanho em mais de uma linha |
| `TROCADO` | `M` na coluna de cor e `Preta` na coluna de tamanho |
| `COLUNAS` | Linha com número errado de colunas |

O programa aceita CSV separado por `,` ou `;`, campos entre aspas, UTF-8 ou Windows-1252 e preços como `119.90`, `119,90` ou `1.299,90`. As colunas são encontradas pelo nome no cabeçalho, em qualquer ordem.

## Como funciona

1. O auditor lê o catálogo e lista os problemas.
2. O Claude sugere títulos seguindo as regras de `PROMPT_SUGESTOES.md`.
3. O validador recusa palavras ou números que não estavam nos dados e títulos que perderam informações.
4. O programa gera `catalogo_revisado.csv` somente com as alterações aprovadas.
5. O lojista revisa o resultado antes de publicar.

Exemplo real da etapa de sugestões, usando dados fictícios:

| Antes | Depois |
| --- | --- |
| `tênis corrida pro` | `Tênis Corrida Pro` |
| `  Fone Bluetooth   X1  ` | `Fone Bluetooth X1` |
| `Tapete Sala 2x3m` | `Tapete de Sala 2x3m` |

## Como rodar

O projeto foi testado com o Java 21.

```bash
# Auditar um catálogo
java -Dstdout.encoding=UTF-8 CatalogAuditor.java catalogo.csv

# Auditar e validar sugestões
java -Dstdout.encoding=UTF-8 CatalogAuditor.java catalogo.csv sugestoes.csv
```

## Testes

```bash
javac -encoding UTF-8 -d out CatalogAuditor.java CatalogAuditorTest.java
java -Dstdout.encoding=UTF-8 -cp out CatalogAuditorTest
```

São 14 testes automatizados. Um deles usa `loja_teste_produtos.csv`, um catálogo fictício de 54 produtos com erros plantados e descritos em `gabarito_loja_teste.csv`. O auditor encontra os 15 erros do gabarito sem alarmes falsos.

## Limitações

- Ainda não foi testado com exportações reais de plataformas de e-commerce.
- Não lê arquivos `.xlsx` nem campos com quebra de linha.
- O validador confere regras, mas não avalia se um título ficou bom.
- A checagem `TROCADO` depende de listas de cores e tamanhos conhecidos.

## Uso de IA

O projeto foi desenvolvido com auxílio do Claude e do Claude Code. As sugestões de títulos são geradas pelo Claude, e todas passam pelo validador e por revisão humana.
