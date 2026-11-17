package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts column lineage from a deliberately small, deterministic SELECT/JOIN SQL subset.
 * It never executes SQL and never guesses computed expressions or ambiguous unqualified columns.
 */
public final class SqlQueryLineageAnalyzer {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target");
    private static final Set<String> CLAUSES = Set.of(
            "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "EXCEPT",
            "INTERSECT", "FOR", "RETURNING", "QUALIFY", "WINDOW");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS");

    public LineageReport analyzeProject(Path projectRoot, ProjectKnowledgeModel model) throws IOException {
        if (projectRoot == null || model == null)
            throw new IllegalArgumentException("projectRoot and model are required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project root must be a directory");

        List<Path> files = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                    files.add(file);
                    if (files.size() > MAX_FILES) throw new IOException("SQL query scan exceeds " + MAX_FILES + " files");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + relative(root, file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> relative(root, path)));

        List<ColumnLineage> edges = new ArrayList<>();
        long totalBytes = 0;
        int queries = 0;
        for (Path file : files) {
            String locator = relative(root, file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + locator + " exceeds " + MAX_FILE_BYTES + " bytes");
                continue;
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("SQL query scan exceeds " + MAX_TOTAL_BYTES + " bytes");
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            for (Statement statement : splitStatements(sql)) {
                String first = firstKeyword(statement.sql());
                if (!"SELECT".equals(first)) {
                    if ("WITH".equals(first))
                        diagnostics.add("UNSUPPORTED_CTE: " + locator + ":" + statement.line()
                                + " CTE queries are not analyzed.");
                    continue;
                }
                queries++;
                QueryResult result = analyzeSelect(statement.sql(), locator, statement.line(), model);
                edges.addAll(result.edges());
                diagnostics.addAll(result.diagnostics());
            }
        }
        edges.sort(Comparator.comparing(ColumnLineage::querySource)
                .thenComparingInt(ColumnLineage::line)
                .thenComparing(ColumnLineage::outputColumn)
                .thenComparing(ColumnLineage::sourceTable)
                .thenComparing(ColumnLineage::sourceColumn));
        return new LineageReport(edges, diagnostics,
                List.of("Only simple SELECT projections and explicit FROM/JOIN table references are analyzed.",
                        "Computed expressions, functions, SELECT *, CTEs, subqueries, set operations and dynamic SQL are not expanded.",
                        "Unqualified columns must resolve to exactly one referenced table; ambiguous or missing names are diagnostics.",
                        "No SQL is executed and no runtime data is inspected."),
                files.size(), totalBytes, queries);
    }

    public QueryResult analyzeSelect(String sql, String sourceName, int startLine,
                                     ProjectKnowledgeModel model) {
        List<String> diagnostics = new ArrayList<>();
        List<ColumnLineage> edges = new ArrayList<>();
        List<Token> tokens = tokenize(sql);
        int select = keywordIndex(tokens, "SELECT", 0, tokens.size());
        if (select < 0) return new QueryResult(edges, List.of("NOT_A_SELECT: " + sourceName));
        int from = topLevelKeyword(tokens, "FROM", select + 1);
        if (from < 0) return new QueryResult(edges, List.of("MISSING_FROM: " + sourceName + ":" + startLine));

        int projectionStart = select + 1;
        if (projectionStart < from && isKeyword(tokens.get(projectionStart), "DISTINCT")) projectionStart++;
        List<List<Token>> projections = splitTopLevel(tokens.subList(projectionStart, from), ",");
        int fromEnd = fromEnd(tokens, from + 1);
        Map<String, TableRef> aliases = parseTableRefs(tokens, from + 1, fromEnd, model,
                sourceName, startLine, diagnostics);
        if (aliases.isEmpty()) {
            diagnostics.add("UNRESOLVED_FROM: " + sourceName + ":" + startLine
                    + " No physical source tables could be resolved.");
            return new QueryResult(edges, diagnostics);
        }

        for (List<Token> projection : projections) {
            if (projection.isEmpty()) continue;
            Projection parsed = parseProjection(projection);
            if (parsed == null) {
                diagnostics.add("UNSUPPORTED_PROJECTION: " + sourceName + ":" + startLine
                        + " Projection is not a simple column reference: " + render(projection));
                continue;
            }
            List<TableRef> candidates;
            if (parsed.qualifier() != null) {
                TableRef table = aliases.get(parsed.qualifier().toUpperCase(Locale.ROOT));
                if (table == null) {
                    diagnostics.add("UNKNOWN_TABLE_ALIAS: " + sourceName + ":" + startLine
                            + " Unknown alias " + parsed.qualifier() + " in " + render(projection));
                    continue;
                }
                candidates = List.of(table);
            } else {
                candidates = aliases.values().stream().distinct()
                        .filter(table -> hasColumn(table.table(), parsed.column())).toList();
                if (candidates.size() > 1) {
                    diagnostics.add("AMBIGUOUS_COLUMN: " + sourceName + ":" + startLine
                            + " Column " + parsed.column() + " exists in multiple FROM/JOIN tables.");
                    continue;
                }
                if (candidates.isEmpty()) {
                    diagnostics.add("SOURCE_COLUMN_NOT_FOUND: " + sourceName + ":" + startLine
                            + " Column " + parsed.column() + " was not found in the referenced tables.");
                    continue;
                }
            }
            TableRef table = candidates.getFirst();
            if (!hasColumn(table.table(), parsed.column())) {
                diagnostics.add("SOURCE_COLUMN_NOT_FOUND: " + sourceName + ":" + startLine
                        + " Column " + parsed.column() + " does not exist in " + table.qualifiedName() + ".");
                continue;
            }
            edges.add(new ColumnLineage(table.qualifiedName(), parsed.column(), parsed.outputName(),
                    table.alias(), sourceName, startLine, Status.OBSERVED));
        }
        return new QueryResult(edges, diagnostics);
    }

    private static Map<String, TableRef> parseTableRefs(List<Token> tokens, int start, int end,
                                                         ProjectKnowledgeModel model, String source,
                                                         int line, List<String> diagnostics) {
        Map<String, TableRef> aliases = new LinkedHashMap<>();
        int i = start;
        boolean expectTable = true;
        while (i < end) {
            Token token = tokens.get(i);
            if (isKeyword(token, "ON") || isKeyword(token, "USING")) {
                i = skipJoinCondition(tokens, i + 1, end);
                continue;
            }
            if (token.text().equals(",")) { expectTable = true; i++; continue; }
            if (JOIN_WORDS.contains(upper(token)) || isKeyword(token, "OUTER")) {
                i++;
                if (i < end && isKeyword(tokens.get(i), "OUTER")) i++;
                if (i < end && isKeyword(tokens.get(i), "JOIN")) i++;
                expectTable = true;
                continue;
            }
            if (!expectTable) { i++; continue; }
            if (!token.identifier()) { i++; continue; }

            StringBuilder name = new StringBuilder(token.text());
            i++;
            while (i + 1 < end && tokens.get(i).text().equals(".") && tokens.get(i + 1).identifier()) {
                name.append('.').append(tokens.get(i + 1).text());
                i += 2;
            }
            String tableName = name.toString();
            String alias = lastName(tableName);
            if (i < end && isKeyword(tokens.get(i), "AS")) {
                i++;
                if (i < end && tokens.get(i).identifier()) alias = tokens.get(i++).text();
            } else if (i < end && tokens.get(i).identifier() && !reserved(tokens.get(i))) {
                alias = tokens.get(i++).text();
            }

            List<ResolvedTable> resolved = resolveTables(model, tableName);
            if (resolved.size() == 1) {
                TableRef ref = new TableRef(alias, resolved.getFirst().qualifiedName(), resolved.getFirst().table());
                String key = alias.toUpperCase(Locale.ROOT);
                if (aliases.putIfAbsent(key, ref) != null)
                    diagnostics.add("DUPLICATE_TABLE_ALIAS: " + source + ":" + line + " alias " + alias + " is repeated.");
            } else if (resolved.isEmpty()) {
                diagnostics.add("SOURCE_TABLE_NOT_FOUND: " + source + ":" + line + " table " + tableName + " was not found.");
            } else {
                diagnostics.add("AMBIGUOUS_SOURCE_TABLE: " + source + ":" + line
                        + " table " + tableName + " matches multiple schemas or data stores.");
            }
            expectTable = false;
        }
        return aliases;
    }

    private static List<ResolvedTable> resolveTables(ProjectKnowledgeModel model, String name) {
        String schema = null;
        String table = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            schema = name.substring(0, dot);
            table = name.substring(dot + 1);
            int schemaDot = schema.lastIndexOf('.');
            if (schemaDot >= 0) schema = schema.substring(schemaDot + 1);
        }
        List<ResolvedTable> result = new ArrayList<>();
        for (ProjectKnowledgeModel.DataStore store : model.dataStores())
            for (ProjectKnowledgeModel.DbSchema dbSchema : store.schemas()) {
                if (schema != null && !same(schema, dbSchema.name())) continue;
                for (ProjectKnowledgeModel.DbTable dbTable : dbSchema.tables())
                    if (same(table, dbTable.name()))
                        result.add(new ResolvedTable(store.id() + "." + dbSchema.name() + "." + dbTable.name(), dbTable));
            }
        return result;
    }

    private static Projection parseProjection(List<Token> tokens) {
        List<Token> expr = new ArrayList<>(tokens);
        String output = null;
        int as = keywordIndex(expr, "AS", 0, expr.size());
        if (as >= 0) {
            if (as != expr.size() - 2 || !expr.get(as + 1).identifier()) return null;
            output = expr.get(as + 1).text();
            expr = new ArrayList<>(expr.subList(0, as));
        } else if (expr.size() == 2 && expr.get(0).identifier() && expr.get(1).identifier()
                && !reserved(expr.get(1))) {
            output = expr.get(1).text();
            expr = new ArrayList<>(expr.subList(0, 1));
        }
        String qualifier = null;
        String column;
        if (expr.size() == 1 && expr.getFirst().identifier()) column = expr.getFirst().text();
        else if (expr.size() == 3 && expr.get(0).identifier() && expr.get(1).text().equals(".")
                && expr.get(2).identifier()) {
            qualifier = expr.get(0).text();
            column = expr.get(2).text();
        } else return null;
        if ("*".equals(column)) return null;
        return new Projection(qualifier, column, output == null ? column : output);
    }

    private static int fromEnd(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.text().equals("(")) depth++;
            else if (token.text().equals(")")) depth--;
            else if (depth == 0 && CLAUSES.contains(upper(token))) return i;
        }
        return tokens.size();
    }

    private static int topLevelKeyword(List<Token> tokens, String keyword, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.text().equals("(")) depth++;
            else if (token.text().equals(")")) depth--;
            else if (depth == 0 && isKeyword(token, keyword)) return i;
        }
        return -1;
    }

    private static int keywordIndex(List<Token> tokens, String keyword, int start, int end) {
        for (int i = start; i < end; i++) if (isKeyword(tokens.get(i), keyword)) return i;
        return -1;
    }

    private static int skipJoinCondition(List<Token> tokens, int start, int end) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.text().equals("(")) depth++;
            else if (token.text().equals(")")) depth--;
            else if (depth == 0 && (JOIN_WORDS.contains(upper(token)) || token.text().equals(","))) return i;
        }
        return end;
    }

    private static List<List<Token>> splitTopLevel(List<Token> tokens, String delimiter) {
        List<List<Token>> result = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens) {
            if (token.text().equals("(")) depth++;
            else if (token.text().equals(")")) depth--;
            if (depth == 0 && token.text().equals(delimiter)) {
                result.add(List.copyOf(current));
                current.clear();
            } else current.add(token);
        }
        result.add(List.copyOf(current));
        return result;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2; while (i < sql.length() && sql.charAt(i) != '\n') i++; continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2; while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(sql.length(), i + 2); continue;
            }
            if (c == '\'' ) {
                int start = i++;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == '\'') {
                        if (i < sql.length() && sql.charAt(i) == '\'') i++;
                        else break;
                    }
                }
                result.add(new Token(sql.substring(start, i), TokenKind.STRING));
                continue;
            }
            if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int start = ++i;
                while (i < sql.length() && sql.charAt(i) != close) i++;
                String value = sql.substring(start, Math.min(i, sql.length()));
                if (i < sql.length()) i++;
                result.add(new Token(value, TokenKind.IDENTIFIER));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '#') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_' || sql.charAt(i) == '$' || sql.charAt(i) == '#')) i++;
                result.add(new Token(sql.substring(start, i), TokenKind.IDENTIFIER));
                continue;
            }
            result.add(new Token(String.valueOf(c), TokenKind.SYMBOL));
            i++;
        }
        return result;
    }

    private static List<Statement> splitStatements(String sql) {
        List<Statement> result = new ArrayList<>();
        int start = 0, line = 1, statementLine = 1;
        char quote = 0;
        boolean lineComment = false, blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (c == '\n') line++;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote != 0) {
                if (c == quote) {
                    if (next == quote && quote != ']') i++;
                    else quote = 0;
                }
                continue;
            }
            if (c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '\'' || c == '"' || c == '`' || c == '[') { quote = c == '[' ? ']' : c; continue; }
            if (c == ';') {
                String statement = sql.substring(start, i).trim();
                if (!statement.isEmpty()) result.add(new Statement(statement, statementLine));
                start = i + 1; statementLine = line;
            }
        }
        String statement = sql.substring(start).trim();
        if (!statement.isEmpty()) result.add(new Statement(statement, statementLine));
        return result;
    }

    private static String firstKeyword(String sql) {
        List<Token> tokens = tokenize(sql);
        return tokens.isEmpty() ? "" : upper(tokens.getFirst());
    }
    private static boolean hasColumn(ProjectKnowledgeModel.DbTable table, String column) {
        return table.columns().stream().anyMatch(candidate -> same(candidate.name(), column));
    }
    private static boolean isKeyword(Token token, String keyword) { return upper(token).equals(keyword); }
    private static String upper(Token token) { return token.text().toUpperCase(Locale.ROOT); }
    private static boolean reserved(Token token) {
        String value = upper(token);
        return CLAUSES.contains(value) || JOIN_WORDS.contains(value) || Set.of("AS", "ON", "USING", "OUTER").contains(value);
    }
    private static String lastName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
    private static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
    private static String render(List<Token> tokens) {
        return tokens.stream().map(Token::text).reduce((a, b) -> a + " " + b).orElse("");
    }
    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    public enum Status { OBSERVED }
    public enum TokenKind { IDENTIFIER, STRING, SYMBOL }
    private record Token(String text, TokenKind kind) {
        boolean identifier() { return kind == TokenKind.IDENTIFIER; }
    }
    private record Projection(String qualifier, String column, String outputName) {}
    private record ResolvedTable(String qualifiedName, ProjectKnowledgeModel.DbTable table) {}
    private record TableRef(String alias, String qualifiedName, ProjectKnowledgeModel.DbTable table) {}
    private record Statement(String sql, int line) {}
    public record ColumnLineage(String sourceTable, String sourceColumn, String outputColumn,
                                String tableAlias, String querySource, int line, Status status) {}
    public record QueryResult(List<ColumnLineage> edges, List<String> diagnostics) {
        public QueryResult { edges = List.copyOf(edges); diagnostics = List.copyOf(diagnostics); }
    }
    public record LineageReport(List<ColumnLineage> edges, List<String> diagnostics,
                                List<String> limitations, int filesScanned, long bytesRead, int queriesAnalyzed) {
        public LineageReport {
            edges = List.copyOf(edges); diagnostics = List.copyOf(diagnostics); limitations = List.copyOf(limitations);
        }
    }
}
