import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auditor de catálogo: lê um CSV e aponta
 *  1) campos vazios, 2) preços inválidos, 3) estoques inválidos,
 *  4) códigos (id/SKU) repetidos, 5) títulos duplicados,
 *  6) cor e tamanho trocados de coluna (quando o arquivo tem as duas colunas).
 *
 * Com um segundo arquivo, também valida as sugestões de títulos feitas pelo Claude:
 * nenhuma sugestão pode trazer palavras ou números que não estavam nos dados originais.
 *
 * As colunas são encontradas pelo nome no cabeçalho, em qualquer ordem e com qualquer
 * quantidade de colunas extras (marca, descrição...). Nomes aceitos:
 *   código:    id, sku, codigo                          (obrigatório)
 *   título:    titulo, nome, title, name                (obrigatório)
 *   preço:     preco, valor, price                      (obrigatório)
 *   categoria: categoria                                (opcional)
 *   estoque:   estoque, quantidade, stock, quantity     (opcional)
 *   opcionais: cor, tamanho  (se existirem, produtos com o mesmo título mas cor ou
 *                             tamanho diferentes NÃO são considerados duplicados)
 *
 * Aceita arquivos exportados do Excel: separador "," ou ";", campos entre aspas,
 * UTF-8 ou Windows-1252 e preços como 119.90, 119,90 ou 1.299,90.
 *
 * Como rodar:
 *   java CatalogAuditor.java catalogo.csv
 *   java CatalogAuditor.java catalogo.csv sugestoes.csv
 */
public class CatalogAuditor {

    // Colunas do arquivo de sugestões.
    private static final int COLUNAS_SUGESTAO = 4; // id, titulo_original, titulo_sugerido, motivo
    private static final int TAMANHO_MAXIMO_TITULO = 60;

    // Palavras de ligação podem ser adicionadas, pois não acrescentam fatos sobre o produto.
    private static final Set<String> PALAVRAS_DE_LIGACAO =
            Set.of("a", "o", "as", "os", "e", "de", "da", "do", "das", "dos", "em", "com", "para");

    // Valores conhecidos para a checagem TROCADO. Ficam em minúsculas e sem acento,
    // porque o valor da planilha passa por semAcentos() + toLowerCase() antes de ser comparado.
    private static final Set<String> TAMANHOS_CONHECIDOS =
            Set.of("pp", "p", "m", "g", "gg", "exg", "exgg"); // números são tratados à parte
    private static final Set<String> CORES_CONHECIDAS =
            Set.of("preto", "preta", "branco", "branca", "azul", "verde", "vermelho", "vermelha",
                    "cinza", "bege", "marrom", "rosa", "prata", "amarelo");

    // Cada problema encontrado vira um objeto deste tipo (record = classe de dados imutável).
    record Problema(int linha, String tipo, String detalhe) {}

    /**
     * Posição de cada coluna no arquivo. Categoria, estoque, cor e tamanho são opcionais
     * (-1 quando não existem). "nomes" guarda os nomes do cabeçalho, na ordem do arquivo,
     * para usar nas mensagens.
     */
    record Layout(String[] nomes, int id, int titulo, int preco, int categoria, int estoque,
                  int cor, int tamanho) {
        int colunas() {
            return nomes.length;
        }

        /**
         * Verdadeiro para as colunas que não podem ficar vazias: código, título, preço e, se
         * existirem, categoria e estoque. Como uma coluna ausente vale -1 e nenhum índice de
         * coluna é negativo, o "== categoria" nunca acerta quando a categoria não existe.
         */
        boolean obrigatoria(int coluna) {
            return coluna == id || coluna == titulo || coluna == preco
                    || coluna == categoria || coluna == estoque;
        }
    }

    public static void main(String[] args) throws IOException {
        // ETAPA 1 - Ler o arquivo, descobrindo a codificação, o separador e as colunas
        String caminho = args.length > 0 ? args[0] : "catalogo.csv";
        List<String> linhas = lerLinhas(Path.of(caminho));
        if (linhas.isEmpty()) {
            System.out.println("O arquivo está vazio.");
            return;
        }
        char separador = detectarSeparador(linhas.get(0));
        Layout layout;
        try {
            layout = detectarLayout(dividirCampos(linhas.get(0), separador));
        } catch (IllegalArgumentException ex) {
            System.out.println("Cabeçalho inválido: " + ex.getMessage());
            return;
        }

        List<Problema> problemas = new ArrayList<>();
        int totalProdutos = 0;

        // Mapa "título normalizado" -> números das linhas onde ele aparece.
        // LinkedHashMap mantém a ordem de inserção, então o relatório sai na ordem do arquivo.
        Map<String, List<Integer>> titulos = new LinkedHashMap<>();
        // Mesma ideia para o código (id/SKU), que não pode se repetir.
        Map<String, List<Integer>> codigos = new LinkedHashMap<>();

        // ETAPA 2 - Percorrer cada linha de dados (i = 1 pula o cabeçalho, que é a linha 0)
        for (int i = 1; i < linhas.size(); i++) {
            int numeroLinha = i + 1; // número "humano": a linha 1 do arquivo é o cabeçalho
            String linha = linhas.get(i);
            if (linha.isBlank()) continue; // ignora linhas totalmente em branco
            totalProdutos++;

            List<String> campos = dividirCampos(linha, separador);

            // O código e o título vêm antes dos campos que costumam sobrar, então são guardados
            // antes da checagem de colunas, para que uma linha quebrada ainda entre na contagem.
            String codigo = colunaConfiavel(campos, layout, layout.id()) ? campo(campos, layout.id()).trim() : "";
            if (!codigo.isEmpty()) {
                codigos.computeIfAbsent(codigo, k -> new ArrayList<>()).add(numeroLinha);
            }
            String chave = chaveDoTitulo(campos, layout);
            if (!chave.isEmpty()) {
                // computeIfAbsent: se a chave não existe, cria a lista; depois adiciona a linha.
                titulos.computeIfAbsent(chave, k -> new ArrayList<>()).add(numeroLinha);
            }

            // ETAPA 3 - Estrutura: se o número de colunas está errado, os demais índices não são
            // confiáveis. Registramos e seguimos para a próxima linha.
            if (campos.size() != layout.colunas()) {
                problemas.add(new Problema(numeroLinha, "COLUNAS",
                        "esperado " + layout.colunas() + " colunas, encontrado " + campos.size()));
                continue;
            }

            // ETAPA 4 - Campos vazios (inclui os que só têm espaços). Só as colunas obrigatórias
            // contam; colunas extras (marca, descrição...) podem ficar em branco.
            for (int c = 0; c < campos.size(); c++) {
                if (layout.obrigatoria(c) && campos.get(c).isBlank()) {
                    problemas.add(new Problema(numeroLinha, "VAZIO",
                            "campo '" + layout.nomes()[c] + "' está vazio"));
                }
            }

            // ETAPA 5 - Preço inválido (só se não estiver vazio, para não repetir o aviso)
            String preco = campos.get(layout.preco()).trim();
            if (!preco.isEmpty()) {
                String erro = validarPreco(preco);
                if (erro != null) {
                    problemas.add(new Problema(numeroLinha, "PRECO", erro + " (valor: '" + preco + "')"));
                }
            }

            // ETAPA 6 - Estoque inválido (só se o arquivo tem a coluna de estoque)
            String estoque = campo(campos, layout.estoque()).trim();
            if (!estoque.isEmpty()) {
                String erro = validarEstoque(estoque);
                if (erro != null) {
                    problemas.add(new Problema(numeroLinha, "ESTOQUE", erro + " (valor: '" + estoque + "')"));
                }
            }

            // ETAPA 6b - Cor e tamanho trocados (só se o arquivo tem as duas colunas)
            if (layout.cor() >= 0 && layout.tamanho() >= 0) {
                String cor = campos.get(layout.cor());
                String tamanho = campos.get(layout.tamanho());
                if (corETamanhoTrocados(cor, tamanho)) {
                    problemas.add(new Problema(numeroLinha, "TROCADO",
                            "cor '" + cor.trim() + "' parece um tamanho e tamanho '" + tamanho.trim()
                                    + "' parece uma cor"));
                }
            }
        }

        // ETAPA 7 - Códigos repetidos e títulos duplicados: qualquer valor que apareceu em mais de uma linha
        for (Map.Entry<String, List<Integer>> e : codigos.entrySet()) {
            if (e.getValue().size() > 1) {
                problemas.add(new Problema(e.getValue().get(0), "CODIGO",
                        "código '" + e.getKey() + "' repetido nas linhas " + e.getValue()));
            }
        }
        for (Map.Entry<String, List<Integer>> e : titulos.entrySet()) {
            if (e.getValue().size() > 1) {
                List<Integer> ls = e.getValue();
                // Reporta na primeira linha, citando todas as ocorrências.
                problemas.add(new Problema(ls.get(0), "DUPLICADO",
                        "título '" + e.getKey() + "' repetido nas linhas " + ls));
            }
        }

        // ETAPA 8 - Relatório
        imprimirRelatorio(problemas, totalProdutos);

        // ETAPA 9 - Sugestões do Claude (opcional)
        if (args.length > 1) {
            System.out.println();
            Map<String, String> aprovados = validarSugestoes(linhas, separador, layout, Path.of(args[1]));
            gerarCatalogoRevisado(linhas, separador, layout, aprovados, Path.of(caminho));
        }
    }

    /**
     * Procura cada coluna pelo nome no cabeçalho. Lança IllegalArgumentException se faltar uma
     * coluna obrigatória (código, título, preço) ou se mais de uma coluna puder ser o mesmo campo.
     */
    static Layout detectarLayout(List<String> cabecalho) {
        List<String> ambiguidades = new ArrayList<>();
        int id = indiceDaColuna(cabecalho, ambiguidades, "código", "id", "sku", "codigo");
        int titulo = indiceDaColuna(cabecalho, ambiguidades, "título", "titulo", "nome", "title", "name");
        int preco = indiceDaColuna(cabecalho, ambiguidades, "preço", "preco", "valor", "price");
        int categoria = indiceDaColuna(cabecalho, ambiguidades, "categoria", "categoria");
        int estoque = indiceDaColuna(cabecalho, ambiguidades, "estoque", "estoque", "quantidade", "stock", "quantity");
        int cor = indiceDaColuna(cabecalho, ambiguidades, "cor", "cor");
        int tamanho = indiceDaColuna(cabecalho, ambiguidades, "tamanho", "tamanho");

        // Com mais de uma candidata não dá para adivinhar qual é a certa, então paramos.
        if (!ambiguidades.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", ambiguidades)
                    + ". Deixe apenas uma dessas colunas no arquivo.");
        }

        // Categoria, estoque, cor e tamanho são opcionais; só código, título e preço são exigidos.
        List<String> faltando = new ArrayList<>();
        if (id < 0) faltando.add("id/sku");
        if (titulo < 0) faltando.add("titulo/nome");
        if (preco < 0) faltando.add("preco");
        if (!faltando.isEmpty()) {
            throw new IllegalArgumentException("faltam as colunas " + faltando + " (encontradas: " + cabecalho + ")");
        }

        String[] nomes = cabecalho.stream().map(String::trim).toArray(String[]::new);
        return new Layout(nomes, id, titulo, preco, categoria, estoque, cor, tamanho);
    }

    /**
     * Posição da coluna cujo nome (sem acento e sem maiúsculas) bate com algum apelido; -1 se não
     * houver nenhuma. O primeiro argumento depois da lista é o nome do campo, usado nas mensagens;
     * os demais são os apelidos aceitos. Se mais de uma coluna bater, a ambiguidade é anotada em
     * "ambiguidades" e o método devolve -1.
     */
    static int indiceDaColuna(List<String> cabecalho, List<String> ambiguidades, String campo, String... apelidos) {
        List<Integer> achadas = new ArrayList<>();
        for (int i = 0; i < cabecalho.size(); i++) {
            String nome = semAcentos(cabecalho.get(i)).trim().toLowerCase();
            for (String apelido : apelidos) {
                if (nome.equals(apelido)) {
                    achadas.add(i);
                    break; // uma coluna conta uma vez só, mesmo que bata com dois apelidos
                }
            }
        }
        if (achadas.size() > 1) {
            List<String> descricao = new ArrayList<>();
            for (int i : achadas) descricao.add(cabecalho.get(i).trim() + " (coluna " + (i + 1) + ")");
            ambiguidades.add("mais de uma coluna pode representar o campo '" + campo + "': "
                    + String.join(", ", descricao));
            return -1;
        }
        return achadas.isEmpty() ? -1 : achadas.get(0);
    }

    /** Devolve o campo na posição indicada, ou "" se a linha for curta demais ou a coluna não existir. */
    static String campo(List<String> campos, int indice) {
        return indice >= 0 && indice < campos.size() ? campos.get(indice) : "";
    }

    /**
     * Chave usada para achar duplicados: o título e, quando o arquivo tem essas colunas, a cor e o
     * tamanho. Assim "Camiseta Básica" preta P e "Camiseta Básica" branca G são produtos diferentes.
     * Devolve "" quando o produto não tem título ou quando não dá para confiar nele (veja
     * colunaConfiavel).
     */
    static String chaveDoTitulo(List<String> campos, Layout layout) {
        if (!colunaConfiavel(campos, layout, layout.titulo())) return "";
        String chave = normalizar(campo(campos, layout.titulo()));
        if (chave.isEmpty()) return "";
        if (layout.cor() >= 0) {
            if (!colunaConfiavel(campos, layout, layout.cor())) return "";
            chave += " / " + normalizar(campo(campos, layout.cor()));
        }
        if (layout.tamanho() >= 0) {
            if (!colunaConfiavel(campos, layout, layout.tamanho())) return "";
            chave += " / " + normalizar(campo(campos, layout.tamanho()));
        }
        return chave;
    }

    /**
     * Uma linha com o número certo de colunas é confiável por inteiro. Numa linha com colunas a
     * mais ou a menos (por exemplo, um preço "119,90" que vira duas colunas), tudo que vem DEPOIS
     * do problema fica deslocado. O preço é o que costuma quebrar, então só confiamos nas colunas
     * que vêm antes dele.
     */
    static boolean colunaConfiavel(List<String> campos, Layout layout, int coluna) {
        return campos.size() == layout.colunas() || coluna < layout.preco();
    }

    /**
     * Compara cada sugestão com o catálogo e imprime as que não podem ser aprovadas.
     * Devolve um mapa id -> novo título, somente com as alterações aprovadas.
     */
    static Map<String, String> validarSugestoes(List<String> linhasCatalogo, char separador, Layout layout,
                                                Path arquivo) throws IOException {
        // Guarda os produtos por id, para encontrar cada um rapidamente.
        Map<String, List<String>> produtos = new LinkedHashMap<>();
        Set<String> idsRepetidosNoCatalogo = new HashSet<>();
        for (int i = 1; i < linhasCatalogo.size(); i++) {
            if (linhasCatalogo.get(i).isBlank()) continue;
            List<String> campos = dividirCampos(linhasCatalogo.get(i), separador);
            String id = campo(campos, layout.id()).trim();
            if (produtos.put(id, campos) != null) idsRepetidosNoCatalogo.add(id);
        }

        List<String> linhas = lerLinhas(arquivo);
        char separadorSugestoes = linhas.isEmpty() ? ';' : detectarSeparador(linhas.get(0));
        List<Problema> problemas = new ArrayList<>();
        Set<String> idsVistos = new HashSet<>();
        Map<String, String> aprovados = new LinkedHashMap<>();
        int mantidos = 0;
        int pendentes = 0;

        for (int i = 1; i < linhas.size(); i++) {
            int numeroLinha = i + 1;
            if (linhas.get(i).isBlank()) continue;

            List<String> campos = dividirCampos(linhas.get(i), separadorSugestoes);
            if (campos.size() != COLUNAS_SUGESTAO) {
                problemas.add(new Problema(numeroLinha, "COLUNAS",
                        "esperado " + COLUNAS_SUGESTAO + " colunas, encontrado " + campos.size()));
                continue;
            }

            String id = campos.get(0).trim();
            String original = campos.get(1).trim();
            String sugerido = campos.get(2).trim();
            String motivo = campos.get(3).trim();

            List<String> produto = produtos.get(id);
            if (produto == null) {
                problemas.add(new Problema(numeroLinha, "ID", "id '" + id + "' não existe no catálogo"));
                continue;
            }
            if (!idsVistos.add(id)) {
                problemas.add(new Problema(numeroLinha, "ID", "id '" + id + "' aparece mais de uma vez"));
                aprovados.remove(id); // com duas respostas, nenhuma das duas é aplicada
                continue;
            }
            if (idsRepetidosNoCatalogo.contains(id)) {
                problemas.add(new Problema(numeroLinha, "ID", "id '" + id + "' aparece mais de uma vez no catálogo"));
                continue;
            }

            String tituloCatalogo = campo(produto, layout.titulo());
            if (!normalizar(original).equals(normalizar(tituloCatalogo))) {
                problemas.add(new Problema(numeroLinha, "ORIGINAL",
                        "título diferente do catálogo: '" + tituloCatalogo.trim() + "'"));
                continue;
            }

            // Sem título sugerido: é um caso para o lojista resolver, e o motivo é obrigatório.
            if (sugerido.isEmpty()) {
                if (motivo.isEmpty()) {
                    problemas.add(new Problema(numeroLinha, "MOTIVO", "sugestão vazia sem motivo"));
                } else {
                    pendentes++;
                }
                continue;
            }

            int errosAntes = problemas.size();
            if (tituloCatalogo.isBlank()) {
                problemas.add(new Problema(numeroLinha, "INVENTADO",
                        "o produto não tem título, então '" + sugerido + "' não vem dos dados"));
                continue;
            }
            if (sugerido.length() > TAMANHO_MAXIMO_TITULO) {
                problemas.add(new Problema(numeroLinha, "TAMANHO", "título com " + sugerido.length()
                        + " caracteres (máximo: " + TAMANHO_MAXIMO_TITULO + ")"));
            }

            // Só o título e a categoria podem servir de fonte para as palavras da sugestão.
            String categoria = produto.size() == layout.colunas() ? campo(produto, layout.categoria()) : "";
            Set<String> permitidas = palavras(tituloCatalogo + " " + categoria);
            for (String palavra : palavras(sugerido)) {
                if (permitidas.contains(palavra) || PALAVRAS_DE_LIGACAO.contains(palavra)) continue;
                boolean temDigito = palavra.matches(".*\\d.*");
                problemas.add(new Problema(numeroLinha, temDigito ? "NUMERO" : "PALAVRA",
                        "'" + palavra + "' não está nos dados originais do produto " + id));
            }

            // O inverso da checagem acima: nenhuma palavra do título pode desaparecer.
            Set<String> palavrasSugeridas = palavras(sugerido);
            for (String palavra : palavras(tituloCatalogo)) {
                if (palavrasSugeridas.contains(palavra) || PALAVRAS_DE_LIGACAO.contains(palavra)) continue;
                problemas.add(new Problema(numeroLinha, "PERDIDA",
                        "'" + palavra + "' saiu do título do produto " + id));
            }

            if (problemas.size() == errosAntes) {
                if (sugerido.equals(tituloCatalogo)) {
                    mantidos++;
                } else if (produto.size() != layout.colunas()) {
                    // A linha do catálogo está quebrada, então o título não pode ser trocado com segurança.
                    problemas.add(new Problema(numeroLinha, "COLUNAS",
                            "corrija as colunas do produto " + id + " no catálogo antes de alterar o título"));
                } else {
                    aprovados.put(id, sugerido);
                }
            }
        }

        // Todo produto precisa ter recebido uma resposta.
        for (String id : produtos.keySet()) {
            if (!idsVistos.contains(id)) {
                problemas.add(new Problema(1, "FALTANDO", "produto " + id + " ficou sem sugestão"));
            }
        }

        System.out.println("=== Validação das sugestões ===");
        System.out.println("Títulos alterados e aprovados: " + aprovados.size());
        System.out.println("Títulos mantidos: " + mantidos);
        System.out.println("Pendentes para o lojista: " + pendentes);
        System.out.println("Problemas nas sugestões: " + problemas.size());
        problemas.sort((a, b) -> Integer.compare(a.linha(), b.linha()));
        for (Problema p : problemas) {
            System.out.printf("Linha %2d | %-9s | %s%n", p.linha(), p.tipo(), p.detalhe());
        }
        return aprovados;
    }

    /**
     * Grava uma cópia do catálogo trocando apenas os títulos aprovados.
     * O arquivo original nunca é alterado, e as demais linhas são copiadas como estão.
     */
    static void gerarCatalogoRevisado(List<String> linhas, char separador, Layout layout,
                                      Map<String, String> aprovados, Path catalogo) throws IOException {
        Path destino = catalogo.resolveSibling("catalogo_revisado.csv");
        if (destino.toAbsolutePath().normalize().equals(catalogo.toAbsolutePath().normalize())) {
            System.out.println();
            System.out.println("Catálogo revisado não gerado: o arquivo de entrada já se chama " + destino.getFileName());
            return;
        }

        List<String> saida = new ArrayList<>();
        saida.add(linhas.get(0));
        int aplicados = 0;
        for (int i = 1; i < linhas.size(); i++) {
            List<String> campos = dividirCampos(linhas.get(i), separador);
            String novoTitulo = aprovados.get(campo(campos, layout.id()).trim());

            // Com o número errado de colunas, reescrever a linha poderia esconder o erro.
            if (novoTitulo == null || campos.size() != layout.colunas()) {
                saida.add(linhas.get(i));
                continue;
            }

            campos.set(layout.titulo(), novoTitulo);
            List<String> protegidos = new ArrayList<>();
            for (String campo : campos) protegidos.add(protegerCampo(campo, separador));
            saida.add(String.join(String.valueOf(separador), protegidos));
            aplicados++;
        }

        // O BOM no início faz o Excel reconhecer os acentos de um arquivo UTF-8.
        Files.writeString(destino, "\uFEFF" + String.join("\n", saida) + "\n", StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Catálogo revisado: " + destino + " (" + aplicados + " título(s) alterado(s))");
    }

    /** Coloca aspas em um campo quando ele contém o separador ou uma aspa. */
    static String protegerCampo(String campo, char separador) {
        if (campo.indexOf(separador) < 0 && campo.indexOf('"') < 0) return campo;
        return '"' + campo.replace("\"", "\"\"") + '"';
    }

    /** Tira os acentos de um texto: "Preço" vira "Preco". */
    static String semAcentos(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Separa um texto em palavras comparáveis: minúsculas e sem acentos. */
    static Set<String> palavras(String texto) {
        Set<String> resultado = new HashSet<>();
        for (String palavra : semAcentos(texto).toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!palavra.isEmpty()) resultado.add(palavra);
        }
        return resultado;
    }

    /** Tenta ler como UTF-8; se os bytes não forem UTF-8 válido, usa Windows-1252 (padrão do Excel). */
    static List<String> lerLinhas(Path caminho) throws IOException {
        byte[] bytes = Files.readAllBytes(caminho);
        String texto;
        try {
            texto = decodificarEstrito(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            texto = new String(bytes, Charset.forName("windows-1252"));
        }
        // O Excel pode gravar um caractere invisível (BOM) no início do arquivo.
        if (texto.startsWith("\uFEFF")) texto = texto.substring(1);
        return texto.lines().toList(); // lines() aceita quebras de linha do Windows e do Linux
    }

    /** Decodifica lançando exceção para bytes inválidos, em vez de trocá-los por um caractere "?". */
    static String decodificarEstrito(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /** O separador é o símbolo que mais aparece no cabeçalho, fora de aspas. */
    static char detectarSeparador(String cabecalho) {
        int virgulas = 0;
        int pontosEVirgulas = 0;
        boolean dentroDeAspas = false;
        for (char c : cabecalho.toCharArray()) {
            if (c == '"') dentroDeAspas = !dentroDeAspas;
            else if (!dentroDeAspas && c == ',') virgulas++;
            else if (!dentroDeAspas && c == ';') pontosEVirgulas++;
        }
        return pontosEVirgulas > virgulas ? ';' : ',';
    }

    /**
     * Divide uma linha percorrendo um caractere por vez. Um split() comum não serve aqui,
     * porque cortaria "Camiseta, preta" em dois campos. Aspas duplas ("") dentro de um
     * campo entre aspas representam uma aspa literal.
     */
    static List<String> dividirCampos(String linha, char separador) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroDeAspas = false;

        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            // Aspas só abrem ou fecham um campo se estivermos dentro de um (fechar, ou "" literal)
            // ou se o campo ainda estiver vazio (abrir). No meio do texto, como em Abajur "Lua",
            // a aspa é só um caractere comum e é mantida.
            if (c == '"' && (dentroDeAspas || atual.length() == 0)) {
                boolean aspaDupla = dentroDeAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"';
                if (aspaDupla) {
                    atual.append('"');
                    i++; // pula a segunda aspa
                } else {
                    dentroDeAspas = !dentroDeAspas;
                }
            } else if (c == separador && !dentroDeAspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString()); // o último campo não tem separador depois dele
        return campos;
    }

    /**
     * Verdadeiro quando a coluna Cor traz um tamanho conhecido E a coluna Tamanho traz uma cor
     * conhecida. As duas condições juntas indicam que os valores foram trocados de coluna.
     * Exigir as duas evita falsos alarmes: com uma só, um produto legítimo poderia ser acusado
     * (ex.: a cor "38" ou o tamanho "Azul" sozinhos podem ser apenas um cadastro estranho).
     */
    static boolean corETamanhoTrocados(String cor, String tamanho) {
        return pareceTamanho(cor) && pareceCor(tamanho);
    }

    /** Tamanho conhecido (PP, P, M, G, GG, EXG, EXGG) ou qualquer número inteiro, como 38 ou 42. */
    static boolean pareceTamanho(String valor) {
        String v = semAcentos(valor).trim().toLowerCase();
        return TAMANHOS_CONHECIDOS.contains(v) || v.matches("\\d+");
    }

    /** Cor da lista de cores conhecidas. */
    static boolean pareceCor(String valor) {
        return CORES_CONHECIDAS.contains(semAcentos(valor).trim().toLowerCase());
    }

    /** Devolve a mensagem de erro, ou null se o preço for válido. */
    static String validarPreco(String texto) {
        String numero = texto;
        // Formato brasileiro: 119,90 ou 1.299,90 -> 119.90 ou 1299.90
        if (numero.matches("-?(\\d+|\\d{1,3}(\\.\\d{3})+),\\d{1,2}")) {
            numero = numero.replace(".", "").replace(',', '.');
        }
        // Aceita só dígitos e ponto decimal. Isso recusa "abc", "R$199.90" e "1E+3".
        if (!numero.matches("-?\\d+(\\.\\d+)?")) return "não é um número";

        BigDecimal valor = new BigDecimal(numero);
        if (valor.signum() < 0) return "preço negativo";
        if (valor.signum() == 0) return "preço zero";
        return null;
    }

    /** Estoque precisa ser um número inteiro maior ou igual a zero. */
    static String validarEstoque(String texto) {
        if (texto.matches("-\\d+")) return "estoque negativo";
        if (!texto.matches("\\d+")) return "não é um número inteiro";
        return null;
    }

    /** Deixa títulos comparáveis: sem espaços extras e sem diferença de maiúsculas. */
    static String normalizar(String titulo) {
        return titulo.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    static void imprimirRelatorio(List<Problema> problemas, int totalProdutos) {
        problemas.sort((a, b) -> Integer.compare(a.linha(), b.linha()));

        System.out.println("=== Relatório de auditoria ===");
        System.out.println("Produtos lidos: " + totalProdutos);
        System.out.println("Problemas encontrados: " + problemas.size());
        System.out.println();
        for (Problema p : problemas) {
            System.out.printf("Linha %2d | %-9s | %s%n", p.linha(), p.tipo(), p.detalhe());
        }

        // Resumo: quantos problemas de cada tipo.
        Map<String, Integer> contagem = new LinkedHashMap<>();
        for (Problema p : problemas) contagem.merge(p.tipo(), 1, Integer::sum);
        System.out.println();
        System.out.println("Resumo por tipo: " + contagem);
    }
}
