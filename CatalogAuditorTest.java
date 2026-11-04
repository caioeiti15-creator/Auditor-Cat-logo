import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Testes do CatalogAuditor, sem biblioteca externa: cada teste é um bloco que lança
 * AssertionError quando algo não é como esperado.
 *
 * Como rodar (na pasta do projeto):
 *   javac -encoding UTF-8 -d out CatalogAuditor.java CatalogAuditorTest.java
 *   java -Dstdout.encoding=UTF-8 -cp out CatalogAuditorTest
 */
public class CatalogAuditorTest {

    interface Teste {
        void rodar() throws Exception;
    }

    static final Map<String, Teste> TESTES = new LinkedHashMap<>();

    public static void main(String[] args) {
        // Item 1: aspas só abrem um campo quando ele ainda está vazio
        TESTES.put("1. aspas no meio do campo são texto comum", () -> {
            igual(List.of("Abajur \"Lua\" Cerâmica", "Casa"),
                    CatalogAuditor.dividirCampos("Abajur \"Lua\" Cerâmica;Casa", ';'));
            igual(List.of("a;b", "c"), CatalogAuditor.dividirCampos("\"a;b\";c", ';'));
            igual(List.of("x", "Disse \"oi\"", "y"),
                    CatalogAuditor.dividirCampos("x;\"Disse \"\"oi\"\"\";y", ';'));
        });

        // Item 2: VAZIO só confere SKU, título, preço, categoria e estoque
        TESTES.put("2. VAZIO ignora colunas extras", () -> {
            String saida = auditar("id;titulo;preco;categoria;estoque;marca\n"
                    + "1;Camiseta;10,50;Roupas;5;\n"       // marca vazia: não é problema
                    + "2;Bermuda;20,00;;5;Trilha\n");      // categoria vazia: continua sendo
            verdade(saida.contains("campo 'categoria' está vazio"), "categoria vazia deveria ser acusada");
            verdade(!saida.contains("campo 'marca'"), "marca não deveria ser conferida");
            verdade(saida.contains("Problemas encontrados: 1"), "esperado 1 problema:\n" + saida);
        });

        // Item 3: nomes ambíguos param o programa
        TESTES.put("3. nome e titulo juntos param com mensagem clara", () -> {
            String saida = auditar("id;nome;titulo;preco\n1;Camiseta;Camiseta;10,50\n");
            verdade(saida.contains("mais de uma coluna"), "faltou a mensagem de ambiguidade:\n" + saida);
            verdade(saida.contains("nome (coluna 2)") && saida.contains("titulo (coluna 3)"), "a mensagem deveria citar as colunas:\n" + saida);
            verdade(!saida.contains("Relatório de auditoria"), "não deveria gerar relatório:\n" + saida);
            String preco = auditar("id;titulo;preco;valor\n1;Camiseta;10,50;10,50\n");
            verdade(preco.contains("mais de uma coluna") && !preco.contains("Relatório de auditoria"),
                    "preco e valor juntos também deveriam parar:\n" + preco);
        });

        // Item 4: em linha quebrada, o título só vale se vier antes do preço
        TESTES.put("4. linha quebrada: título depois do preço não é usado", () -> {
            String saida = auditar("id;preco;titulo;categoria;estoque\n"
                    + "1;10,50;Camiseta;Roupas;5;sobrando\n"
                    + "2;10,50;Camiseta;Roupas;5\n");
            verdade(saida.contains("COLUNAS"), "a linha 2 deveria ter o erro COLUNAS:\n" + saida);
            verdade(!saida.contains("DUPLICADO"), "título deslocado não pode gerar DUPLICADO:\n" + saida);
        });
        TESTES.put("4b. linha quebrada: título antes do preço continua sendo usado", () -> {
            String saida = auditar("id;titulo;preco;categoria;estoque\n"
                    + "1;Camiseta;10,50;Roupas;5;sobrando\n"
                    + "2;Camiseta;10,50;Roupas;5\n");
            verdade(saida.contains("DUPLICADO"), "título antes do preço deveria contar:\n" + saida);
        });

        // Item 5: categoria e estoque opcionais, novos apelidos
        TESTES.put("5. sku;name;price;quantity", () -> {
            String saida = auditar("sku;name;price;quantity\nA1;Camiseta;10,50;3\n");
            verdade(saida.contains("Problemas encontrados: 0"), saida);
        });
        TESTES.put("5b. codigo;title;valor;stock", () -> {
            String saida = auditar("codigo;title;valor;stock\nA1;Camiseta;10,50;3\n");
            verdade(saida.contains("Problemas encontrados: 0"), saida);
        });
        TESTES.put("5c. sem categoria e sem estoque", () -> {
            String saida = auditar("id;titulo;preco\n1;Camiseta;10,50\n2;Bermuda;abc\n");
            verdade(saida.contains("Problemas encontrados: 1") && saida.contains("PRECO"), saida);
        });
        TESTES.put("5d. valor vazio é acusado com o nome real da coluna", () -> {
            String saida = auditar("id;titulo;valor\n1;Camiseta;\n");
            verdade(saida.contains("campo 'valor' está vazio"), saida);
        });

        // Item 6: nenhum BOM invisível no código, só a escrita
        TESTES.put("6. BOM escrito como \\uFEFF", () -> {
            String fonte = Files.readString(Path.of("CatalogAuditor.java"), StandardCharsets.UTF_8);
            verdade(fonte.indexOf('\uFEFF') < 0, "há um BOM invisível no código");
            int ocorrencias = 0;
            for (int i = fonte.indexOf("\\uFEFF"); i >= 0; i = fonte.indexOf("\\uFEFF", i + 1)) ocorrencias++;
            verdade(ocorrencias == 2, "esperado 2 usos de \\uFEFF, achei " + ocorrencias);
        });

        // Item 7: novas cores conhecidas
        TESTES.put("7. rosa, prata e amarelo são cores", () -> {
            verdade(CatalogAuditor.pareceCor("Rosa"), "rosa");
            verdade(CatalogAuditor.pareceCor("PRATA"), "prata");
            verdade(CatalogAuditor.pareceCor("  Amarelo "), "amarelo");
            String saida = auditar("id;titulo;preco;categoria;estoque;cor;tamanho\n1;Camisa;10,50;Roupas;5;M;Rosa\n");
            verdade(saida.contains("TROCADO"), "M/Rosa deveria ser TROCADO:\n" + saida);
        });

        // Código depois do preço: linhas quebradas pelo preço 119,90 não podem gerar CODIGO
        TESTES.put("8. id depois do preço: linhas quebradas não geram CODIGO", () -> {
            String saida = auditar("titulo,preco,id,categoria,estoque\n"
                    + "Panela Alfa,119,90,P1,Casa,5\n"
                    + "Panela Beta,129,90,P2,Casa,6\n");
            verdade(saida.contains("COLUNAS"), "as duas linhas deveriam ter o erro COLUNAS:\n" + saida);
            verdade(!saida.contains("CODIGO"), "código deslocado não pode gerar CODIGO:\n" + saida);
        });

        // Arquivo real com BOM (bytes EF BB BF no início), como o Excel grava
        TESTES.put("9. arquivo com BOM: cabeçalho reconhecido e 0 problemas", () -> {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] corpo = "id;titulo;preco\n1;Camiseta;10,50\n".getBytes(StandardCharsets.UTF_8);
            byte[] arquivoBytes = new byte[bom.length + corpo.length];
            System.arraycopy(bom, 0, arquivoBytes, 0, bom.length);
            System.arraycopy(corpo, 0, arquivoBytes, bom.length, corpo.length);
            Path arquivo = Files.createTempDirectory("auditor").resolve("bom.csv");
            Files.write(arquivo, arquivoBytes);
            String saida = capturar(new String[]{arquivo.toString()});
            verdade(!saida.contains("Cabeçalho inválido"), "cabeçalho não reconhecido:\n" + saida);
            verdade(saida.contains("Problemas encontrados: 0"), saida);
        });

        // Regressão: o arquivo da loja continua com 15 problemas
        TESTES.put("regressão. loja_teste_produtos.csv tem 15 problemas", () -> {
            String saida = capturar(new String[]{"loja_teste_produtos.csv"});
            verdade(saida.contains("Problemas encontrados: 15"), saida);
        });

        int falhas = 0;
        for (Map.Entry<String, Teste> e : TESTES.entrySet()) {
            try {
                e.getValue().rodar();
                System.out.println("PASSOU  " + e.getKey());
            } catch (Throwable t) {
                falhas++;
                System.out.println("FALHOU  " + e.getKey());
                System.out.println("        " + String.valueOf(t.getMessage()).replace("\n", "\n        "));
            }
        }
        System.out.println();
        System.out.println((TESTES.size() - falhas) + " de " + TESTES.size() + " testes passaram.");
        System.exit(falhas == 0 ? 0 : 1);
    }

    /** Grava o texto num CSV temporário, roda o auditor nele e devolve tudo que ele imprimiu. */
    static String auditar(String csv) throws Exception {
        Path pasta = Files.createTempDirectory("auditor");
        Path arquivo = pasta.resolve("teste.csv");
        Files.writeString(arquivo, csv, StandardCharsets.UTF_8);
        return capturar(new String[]{arquivo.toString()});
    }

    /** Roda CatalogAuditor.main trocando a saída padrão por um buffer, para podermos ler o que foi impresso. */
    static String capturar(String[] args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CatalogAuditor.main(args);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static void verdade(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError(mensagem);
    }

    static void igual(Object esperado, Object obtido) {
        if (!esperado.equals(obtido)) {
            throw new AssertionError("esperado " + esperado + " mas veio " + obtido);
        }
    }
}
