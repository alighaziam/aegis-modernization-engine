package io.aegis.parser.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static, non-executing parser for a documented DDL subset. Unknown syntax is reported, not guessed. */
public final class SqlDdlParser {
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.$#\\\" ]+)\\s*\\(");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+([\\w.$#\\\"]+)\\s+ON\\s+([\\w.$#\\\"]+)\\s*\\((.*?)\\)");
    private static final Pattern ALTER_TABLE = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+ADD\\s+(?:CONSTRAINT\\s+([\\w.$#\\\"]+)\\s+)?(.*)$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[\\\"]?([A-Za-z0-9_$#]+)[\\\"]?");
    private static final Pattern REFERENCES = Pattern.compile("(?is)REFERENCES\\s+([\\w.$#\\\"]+)\\s*\\((.*?)\\)");

    public ParseResult parse(String sql, String sourceName, String dialect) {
        if (sql == null) throw new IllegalArgumentException("SQL input is required");
        String vendor = detectVendor(dialect);
        List\u003cTable\u003e tables = new ArrayList\u003c\u003e();
        List\u003cIndex\u003e indexes = new ArrayList\u003c\u003e();
        List\u003cDiagnostic\u003e diagnostics = new ArrayList\u003c\u003e();
        List\u003cEvidenceDraft\u003e evidence = new ArrayList\u003c\u003e();
        List\u003cStatement\u003e statements = splitStatements(sql);
        for (Statement statement : statements) {
            String text = statement.text().trim();
            if (text.isEmpty()) continue;
            Matcher tableMatcher = CREATE_TABLE.matcher(text);
            Matcher indexMatcher = CREATE_INDEX.matcher(text);
            Matcher alterMatcher = ALTER_TABLE.matcher(text);
            if (tableMatcher.find()) {
                String tableName = cleanName(tableMatcher.group(1));
                int open = text.indexOf('(', tableMatcher.start());
                int close = matchingParen(text, open);
                if (close \u003c 0) { diagnostics.add(diag("DDL_UNBALANCED_PAREN", "Unclosed CREATE TABLE body", statement.line())); continue; }
                String body = text.substring(open + 1, close);
                Table table = parseTable(tableName, body, sourceName, statement.line(), diagnostics, evidence);
                tables.add(table);
                evidence.add(new EvidenceDraft("table:" + tableName, sourceName, statement.line(), "CREATE TABLE", "direct", "high"));
            } else if (indexMatcher.find()) {
                Index index = new Index(cleanName(indexMatcher.group(2)), cleanName(indexMatcher.group(3)),
                        splitIdentifiers(indexMatcher.group(4)), indexMatcher.group(1) != null,
                        locator(sourceName, statement.line()));
                indexes.add(index);
                evidence.add(new EvidenceDraft("index:" + index.name(), sourceName, statement.line(), "CREATE INDEX", "direct", "high"));
            } else if (alterMatcher.find()) {
                String tableName = cleanName(alterMatcher.group(1));
                String constraintName = cleanName(alterMatcher.group(2));
                String definition = alterMatcher.group(3).trim();
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE", "ALTER TABLE references table not parsed in this input: " + tableName, statement.line()));
                    continue;
                }
                Constraint constraint = parseConstraint(constraintName, definition, sourceName, statement.line(), diagnostics);
                if (constraint != null) {
                    List\u003cConstraint\u003e changed = new ArrayList\u003c\u003e(table.constraints()); changed.add(constraint);
                    replaceTable(tables, table, new Table(table.name(), table.columns(), changed, table.indexes(), table.locator()));
                    evidence.add(new EvidenceDraft("constraint:" + tableName + "." + constraint.name(), sourceName, statement.line(), "ALTER TABLE ADD", "direct", "high"));
                }
            } else {
                diagnostics.add(diag("UNSUPPORTED_DDL", "Statement is outside the supported DDL subset: " + abbreviate(text), statement.line()));
            }
        }
        for (Index index : indexes) {
            Table table = findTable(tables, index.tableName());
            if (table != null) {
                List\u003cIndex\u003e changed = new ArrayList\u003c\u003e(table.indexes()); changed.add(index);
                replaceTable(tables, table, new Table(table.name(), table.columns(), table.constraints(), changed, table.locator()));
            } else diagnostics.add(diag("INDEX_TABLE_NOT_FOUND", "Index references table not found in parsed DDL: " + index.tableName(), 1));
        }
        return new ParseResult(vendor, tables, indexes, diagnostics, evidence, statements.size());
    }

    private Table parseTable(String name, String body, String source, int line,
                             List\u003cDiagnostic\u003e diagnostics, List\u003cEvidenceDraft\u003e evidence) {
        List\u003cColumn\u003e columns = new ArrayList\u003c\u003e();
        List\u003cConstraint\u003e constraints = new ArrayList\u003c\u003e();
        for (String part : splitTopLevel(body)) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            String upper = item.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY KEY") || upper.startsWith("FOREIGN KEY") || upper.startsWith("UNIQUE ") || upper.startsWith("CHECK ")) {
                Constraint c = parseConstraint(null, item, source, line, diagnostics);
                if (c != null) constraints.add(c);
            } else {
                Column column = parseColumn(item, source, line, diagnostics);
                if (column != null) {
                    columns.add(column);
                    evidence.add(new EvidenceDraft("column:" + name + "." + column.name(), source, line, "CREATE TABLE column definition", "direct", "high"));
                    String u = item.toUpperCase(Locale.ROOT);
                    if (u.contains(" PRIMARY KEY")) constraints.add(new Constraint("PK_" + name, "PRIMARY_KEY", List.of(column.name()), null, List.of(), locator(source, line)));
                    Matcher ref = REFERENCES.matcher(item);
                    if (ref.find()) constraints.add(new Constraint("FK_" + name + "_" + column.name(), "FOREIGN_KEY", List.of(column.name()), cleanName(ref.group(1)), splitIdentifiers(ref.group(2)), locator(source, line)));
                }
            }
        }
        return new Table(name, columns, constraints, List.of(), locator(source, line));
    }

    private Column parseColumn(String text, String source, int line, List\u003cDiagnostic\u003e diagnostics) {
        Matcher m = Pattern.compile("(?is)^([\"]?[A-Za-z0-9_$#]+[\"]?)\\s+(.+)$").matcher(text.trim());
        if (!m.find()) { diagnostics.add(diag("UNPARSED_COLUMN", "Could not parse column definition: " + abbreviate(text), line)); return null; }
        String name = cleanName(m.group(1));
        String rest = m.group(2).trim();
        Matcher type = Pattern.compile("(?i)^([A-Za-z][A-Za-z0-9_]*(?:\\s+(?:PRECISION|VARYING|WITH|WITHOUT|TIME|ZONE))*)(?:\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?").matcher(rest);
        if (!type.find()) { diagnostics.add(diag("UNSUPPORTED_COLUMN_TYPE", "Could not identify data type for " + name, line)); return null; }
        String dataType = type.group(1).trim().replaceAll("\\s+", " ");
        Integer first = integer(type.group(2)); Integer second = integer(type.group(3));
        boolean nullable = !rest.toUpperCase(Locale.ROOT).contains("NOT NULL");
        String defaultValue = null;
        Matcher def = Pattern.compile("(?is)\\bDEFAULT\\s+(.+?)(?=\\s+(?:NOT\\s+NULL|NULL|PRIMARY\\s+KEY|REFERENCES|UNIQUE)\\b|$)").matcher(rest);
        if (def.find()) defaultValue = def.group(1).trim();
        return new Column(name, dataType, first, second, nullable, defaultValue, locator(source, line));
    }

    private Constraint parseConstraint(String suppliedName, String definition, String source, int line,
                                       List\u003cDiagnostic\u003e diagnostics) {
        String text = definition.trim(); String name = suppliedName;
        Matcher named = Pattern.compile("(?is)^CONSTRAINT\\s+([\\w.$#\"]+)\\s+(.*)$").matcher(text);
        if (named.find()) { if (name == null) name = cleanName(named.group(1)); text = named.group(2).trim(); }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY")) return new Constraint(name == null ? "PK_" + line : name, "PRIMARY_KEY", insideList(text), null, List.of(), locator(source, line));
        if (upper.startsWith("UNIQUE")) return new Constraint(name == null ? "UQ_" + line : name, "UNIQUE", insideList(text), null, List.of(), locator(source, line));
        if (upper.startsWith("FOREIGN KEY")) {
            Matcher ref = REFERENCES.matcher(text);
            if (!ref.find()) { diagnostics.add(diag("UNPARSED_FOREIGN_KEY", "Foreign key has no supported REFERENCES clause", line)); return null; }
            return new Constraint(name == null ? "FK_" + line : name, "FOREIGN_KEY", insideList(text), cleanName(ref.group(1)), splitIdentifiers(ref.group(2)), locator(source, line));
        }
        if (upper.startsWith("CHECK")) return new Constraint(name == null ? "CK_" + line : name, "CHECK", List.of(), null, List.of(), locator(source, line));
        diagnostics.add(diag("UNSUPPORTED_CONSTRAINT", "Unsupported constraint: " + abbreviate(text), line));
        return null;
    }

    private static List\u003cStatement\u003e splitStatements(String sql) {
        List\u003cStatement\u003e out = new ArrayList\u003c\u003e(); StringBuilder current = new StringBuilder();
        boolean single = false, quoted = false, lineComment = false, blockComment = false; int line = 1, startLine = 1;
        for (int i = 0; i \u003c sql.length(); i++) {
            char c = sql.charAt(i), next = i + 1 \u003c sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') { lineComment = false; line++; } continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } else if (c == '\n') line++; continue; }
            if (!single && !quoted && c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (!single && !quoted && c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '\'' && !quoted) single = !single;
            else if (c == '"' && !single) quoted = !quoted;
            if (c == ';' && !single && !quoted) { out.add(new Statement(current.toString(), startLine)); current.setLength(0); startLine = line; }
            else { current.append(c); if (c == '\n') line++; }
        }
        if (!current.toString().isBlank()) out.add(new Statement(current.toString(), startLine));
        return out;
    }

    private static List\u003cString\u003e splitTopLevel(String body) {
        List\u003cString\u003e parts = new ArrayList\u003c\u003e(); int depth = 0; boolean single = false, quoted = false; int start = 0;
        for (int i = 0; i \u003c body.length(); i++) { char c = body.charAt(i);
            if (c == '\'' && !quoted) single = !single; else if (c == '"' && !single) quoted = !quoted;
            if (!single && !quoted) { if (c == '(') depth++; else if (c == ')') depth--; else if (c == ',' && depth == 0) { parts.add(body.substring(start, i)); start = i + 1; } }
        }
        parts.add(body.substring(start)); return parts;
    }

    private static int matchingParen(String text, int open) {
        if (open \u003c 0) return -1; int depth = 0; boolean single = false, quoted = false;
        for (int i = open; i \u003c text.length(); i++) { char c = text.charAt(i);
            if (c == '\'' && !quoted) single = !single; else if (c == '"' && !single) quoted = !quoted;
            if (!single && !quoted) { if (c == '(') depth++; else if (c == ')' && --depth == 0) return i; }
        }
        return -1;
    }

    private static List\u003cString\u003e splitIdentifiers(String text) {
        List\u003cString\u003e out = new ArrayList\u003c\u003e(); for (String item : text.split(",")) out.add(cleanName(item)); return List.copyOf(out);
    }
    private static List\u003cString\u003e insideList(String text) { int a = text.indexOf('('), b = text.lastIndexOf(')'); return a \u003c 0 || b \u003c a ? List.of() : splitIdentifiers(text.substring(a + 1, b)); }
    private static String cleanName(String value) { return value == null ? null : value.trim().replace("\"", "").replaceAll("\\s+", ""); }
    private static String locator(String source, int line) { return source + ":" + line; }
    private static Integer integer(String s) { try { return s == null ? null : Integer.valueOf(s); } catch (NumberFormatException e) { return null; } }
    private static String detectVendor(String dialect) {
        if (dialect == null || dialect.isBlank()) return "UNKNOWN";
        String normalized = dialect.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.equals("oracle") || normalized.startsWith("oracle-")) return "ORACLE";
        if (normalized.equals("postgres") || normalized.equals("postgresql") || normalized.startsWith("postgresql-")) return "POSTGRESQL";
        if (normalized.equals("mysql") || normalized.startsWith("mysql-")) return "MYSQL";
        if (normalized.equals("mariadb") || normalized.startsWith("mariadb-")) return "MARIADB";
        if (normalized.equals("sqlserver") || normalized.equals("sql-server") || normalized.equals("mssql")
                || normalized.startsWith("sqlserver-")) return "SQLSERVER";
        if (normalized.equals("db2") || normalized.startsWith("db2-")) return "DB2";
        return "UNKNOWN";
    }
    private static String abbreviate(String s) { return s.length() \u003e 180 ? s.substring(0, 180) + "..." : s; }
    private static Diagnostic diag(String code, String message, int line) { return new Diagnostic(code, message, line, "REVIEW_REQUIRED"); }
    private static Table findTable(List\u003cTable\u003e tables, String name) { for (Table t : tables) if (t.name().equalsIgnoreCase(name)) return t; return null; }
    private static void replaceTable(List\u003cTable\u003e tables, Table old, Table replacement) { tables.set(tables.indexOf(old), replacement); }

    public record ParseResult(String vendor, List\u003cTable\u003e tables, List\u003cIndex\u003e indexes,
                              List\u003cDiagnostic\u003e diagnostics, List\u003cEvidenceDraft\u003e evidence,
                              int statementCount) {}
    public record Table(String name, List\u003cColumn\u003e columns, List\u003cConstraint\u003e constraints, List\u003cIndex\u003e indexes, String locator) {}
    public record Column(String name, String dataType, Integer length, Integer scale, boolean nullable, String defaultExpression, String locator) {}
    public record Constraint(String name, String type, List\u003cString\u003e columns, String referencedTable,
                             List\u003cString\u003e referencedColumns, String locator) {}
    public record Index(String name, String tableName, List\u003cString\u003e columns, boolean unique, String locator) {}
    public record Diagnostic(String code, String message, int line, String action) {}
    public record EvidenceDraft(String subjectRef, String source, int line, String extraction, String kind, String confidence) {}
    private record Statement(String text, int line) {}
                                                 }
