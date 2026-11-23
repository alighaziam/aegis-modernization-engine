package io.aegis.parser.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static, non-executing parser for a documented DDL subset. Unknown syntax is reported, not guessed. */
public final class SqlDdlParser {
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.$#\\\" ]+)\\s*\\(");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+([\\w.$#\\\"]+)\\s+ON\\s+([\\w.$#\\\"]+)\\s*\\((.*?)\\)");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^\\s*DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?([\\w.$#\\\"]+)\\s*$");
    private static final Pattern DROP_INDEX = Pattern.compile("(?is)^\\s*DROP\\s+INDEX\\s+(IF\\s+EXISTS\\s+)?([\\w.$#\\\"]+)\\s*$");
    private static final Pattern ALTER_TABLE = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+ADD\\s+(?:CONSTRAINT\\s+([\\w.$#\\\"]+)\\s+)?(.*)$");
    private static final Pattern ALTER_DROP_CONSTRAINT = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+DROP\\s+CONSTRAINT\\s+(IF\\s+EXISTS\\s+)?([\\w.$#\\\"]+)\\s*$");
    private static final Pattern ALTER_DROP_COLUMN = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+DROP\\s+COLUMN\\s+(IF\\s+EXISTS\\s+)?([\\w.$#\\\"]+)\\s*$");
    private static final Pattern ALTER_RENAME_COLUMN = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+RENAME\\s+COLUMN\\s+([\\w.$#\\\"]+)\\s+TO\\s+([\\w.$#\\\"]+)\\s*$");
    private static final Pattern ALTER_RENAME_TABLE = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+RENAME\\s+TO\\s+([\\w.$#\\\"]+)\\s*$");
    private static final Pattern ALTER_COLUMN = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+ALTER\\s+(?:COLUMN\\s+)?([\\w.$#\\\"]+)\\s+(TYPE\\s+.+|SET\\s+DATA\\s+TYPE\\s+.+|SET\\s+NOT\\s+NULL|DROP\\s+NOT\\s+NULL|SET\\s+DEFAULT\\s+.+|DROP\\s+DEFAULT)\\s*$");
    private static final Pattern ALTER_MODIFY_COLUMN = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([\\w.$#\\\"]+)\\s+MODIFY\\s+(?:\\((.*)\\)|(.*))\\s*$");
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
            Matcher dropTableMatcher = DROP_TABLE.matcher(text);
            Matcher dropIndexMatcher = DROP_INDEX.matcher(text);
            Matcher alterMatcher = ALTER_TABLE.matcher(text);
            Matcher dropConstraintMatcher = ALTER_DROP_CONSTRAINT.matcher(text);
            Matcher dropColumnMatcher = ALTER_DROP_COLUMN.matcher(text);
            Matcher renameColumnMatcher = ALTER_RENAME_COLUMN.matcher(text);
            Matcher renameTableMatcher = ALTER_RENAME_TABLE.matcher(text);
            Matcher alterColumnMatcher = ALTER_COLUMN.matcher(text);
            Matcher modifyColumnMatcher = ALTER_MODIFY_COLUMN.matcher(text);
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
            } else if (dropTableMatcher.find()) {
                boolean ifExists = dropTableMatcher.group(1) != null;
                String tableName = cleanName(dropTableMatcher.group(2));
                Table table = findTable(tables, tableName);
                if (table == null) {
                    if (!ifExists) diagnostics.add(diag("DROP_TABLE_NOT_FOUND",
                            "DROP TABLE references a table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                List<String> dependentTables = tables.stream()
                        .filter(candidate -> candidate != table)
                        .filter(candidate -> candidate.constraints().stream()
                                .anyMatch(constraint -> "FOREIGN_KEY".equalsIgnoreCase(constraint.type())
                                        && sameTableReference(constraint.referencedTable(), tableName)))
                        .map(Table::name)
                        .distinct()
                        .toList();
                if (!dependentTables.isEmpty()) {
                    diagnostics.add(diag("DROP_TABLE_DEPENDENCY",
                            "DROP TABLE is blocked by parsed foreign-key references from: "
                                    + String.join(", ", dependentTables),
                            statement.line()));
                    continue;
                }
                tables.remove(table);
                indexes.removeIf(index -> sameTableReference(index.tableName(), tableName));
                evidence.add(new EvidenceDraft("table:" + tableName, sourceName, statement.line(),
                        "DROP TABLE", "direct", "high"));
            } else if (dropIndexMatcher.find()) {
                boolean ifExists = dropIndexMatcher.group(1) != null;
                String indexName = cleanName(dropIndexMatcher.group(2));
                boolean removed = indexes.removeIf(index -> index.name().equalsIgnoreCase(indexName));
                for (Table table : new ArrayList<>(tables)) {
                    List<Index> changedIndexes = new ArrayList<>(table.indexes());
                    boolean removedFromTable = changedIndexes.removeIf(index -> index.name().equalsIgnoreCase(indexName));
                    if (removedFromTable) {
                        removed = true;
                        replaceTable(tables, table, new Table(table.name(), table.columns(), table.constraints(),
                                changedIndexes, table.locator()));
                    }
                }
                if (!removed) {
                    if (!ifExists) diagnostics.add(diag("DROP_INDEX_NOT_FOUND",
                            "DROP INDEX references an index not parsed in this input: " + indexName,
                            statement.line()));
                    continue;
                }
                evidence.add(new EvidenceDraft("index:" + indexName, sourceName, statement.line(),
                        "DROP INDEX", "direct", "high"));
            } else if (dropConstraintMatcher.find()) {
                String tableName = cleanName(dropConstraintMatcher.group(1));
                boolean ifExists = dropConstraintMatcher.group(2) != null;
                String constraintName = cleanName(dropConstraintMatcher.group(3));
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE",
                            "ALTER TABLE references table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                List<Constraint> changed = new ArrayList<>(table.constraints());
                boolean removed = changed.removeIf(existing -> existing.name().equalsIgnoreCase(constraintName));
                if (!removed) {
                    if (!ifExists) diagnostics.add(diag("DROP_CONSTRAINT_NOT_FOUND",
                            "ALTER TABLE attempts to drop an unknown constraint: " + tableName + "." + constraintName,
                            statement.line()));
                    continue;
                }
                replaceTable(tables, table, new Table(table.name(), table.columns(), changed,
                        table.indexes(), table.locator()));
                evidence.add(new EvidenceDraft("constraint:" + tableName + "." + constraintName,
                        sourceName, statement.line(), "ALTER TABLE DROP CONSTRAINT", "direct", "high"));
            } else if (dropColumnMatcher.find()) {
                String tableName = cleanName(dropColumnMatcher.group(1));
                boolean ifExists = dropColumnMatcher.group(2) != null;
                String columnName = cleanName(dropColumnMatcher.group(3));
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE",
                            "ALTER TABLE references table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                Column existing = table.columns().stream()
                        .filter(column -> column.name().equalsIgnoreCase(columnName))
                        .findFirst().orElse(null);
                if (existing == null) {
                    if (!ifExists) diagnostics.add(diag("DROP_COLUMN_NOT_FOUND",
                            "ALTER TABLE attempts to drop an unknown column: " + tableName + "." + columnName,
                            statement.line()));
                    continue;
                }
                boolean usedByConstraint = table.constraints().stream()
                        .anyMatch(constraint -> constraint.columns().stream()
                                .anyMatch(name -> name.equalsIgnoreCase(columnName)));
                boolean usedByIndex = indexes.stream()
                        .anyMatch(index -> index.tableName().equalsIgnoreCase(tableName)
                                && index.columns().stream()
                                .anyMatch(name -> name.equalsIgnoreCase(columnName)));
                if (usedByConstraint || usedByIndex) {
                    diagnostics.add(diag("DROP_COLUMN_DEPENDENCY",
                            "ALTER TABLE cannot safely drop a column referenced by a parsed constraint or index: "
                                    + tableName + "." + columnName,
                            statement.line()));
                    continue;
                }
                List<Column> changed = new ArrayList<>(table.columns());
                changed.removeIf(column -> column.name().equalsIgnoreCase(columnName));
                replaceTable(tables, table, new Table(table.name(), changed, table.constraints(),
                        table.indexes(), table.locator()));
                evidence.add(new EvidenceDraft("column:" + tableName + "." + columnName,
                        sourceName, statement.line(), "ALTER TABLE DROP COLUMN", "direct", "high"));
            } else if (renameTableMatcher.find()) {
                String oldName = cleanName(renameTableMatcher.group(1));
                String newName = cleanName(renameTableMatcher.group(2));
                Table existing = findTable(tables, oldName);
                if (existing == null) {
                    diagnostics.add(diag("RENAME_TABLE_NOT_FOUND",
                            "ALTER TABLE attempts to rename an unknown table: " + oldName,
                            statement.line()));
                    continue;
                }
                if (findTable(tables, newName) != null) {
                    diagnostics.add(diag("DUPLICATE_TABLE",
                            "ALTER TABLE rename target already exists: " + newName,
                            statement.line()));
                    continue;
                }

                for (int i = 0; i < indexes.size(); i++) {
                    Index index = indexes.get(i);
                    if (index.tableName().equalsIgnoreCase(oldName)) {
                        indexes.set(i, new Index(index.name(), newName, index.columns(),
                                index.unique(), index.locator()));
                    }
                }
                for (int i = 0; i < tables.size(); i++) {
                    Table current = tables.get(i);
                    String tableName = current.name().equalsIgnoreCase(oldName) ? newName : current.name();
                    List<Constraint> changedConstraints = current.constraints().stream()
                            .map(constraint -> oldName.equalsIgnoreCase(constraint.referencedTable())
                                    ? new Constraint(constraint.name(), constraint.type(), constraint.columns(),
                                            newName, constraint.referencedColumns(), constraint.locator())
                                    : constraint)
                            .toList();
                    List<Index> changedIndexes = current.indexes().stream()
                            .map(index -> index.tableName().equalsIgnoreCase(oldName)
                                    ? new Index(index.name(), newName, index.columns(), index.unique(), index.locator())
                                    : index)
                            .toList();
                    if (!tableName.equals(current.name())
                            || !changedConstraints.equals(current.constraints())
                            || !changedIndexes.equals(current.indexes())) {
                        tables.set(i, new Table(tableName, current.columns(), changedConstraints,
                                changedIndexes, current.locator()));
                    }
                }
                evidence.add(new EvidenceDraft("table:" + newName, sourceName, statement.line(),
                        "ALTER TABLE RENAME TO", "direct", "high"));
            } else if (alterColumnMatcher.find()) {
                String tableName = cleanName(alterColumnMatcher.group(1));
                String columnName = cleanName(alterColumnMatcher.group(2));
                String action = alterColumnMatcher.group(3).trim();
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE",
                            "ALTER TABLE references table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                Column existing = table.columns().stream()
                        .filter(column -> column.name().equalsIgnoreCase(columnName))
                        .findFirst().orElse(null);
                if (existing == null) {
                    diagnostics.add(diag("ALTER_COLUMN_NOT_FOUND",
                            "ALTER TABLE attempts to modify an unknown column: " + tableName + "." + columnName,
                            statement.line()));
                    continue;
                }

                String upperAction = action.toUpperCase(Locale.ROOT);
                Column changedColumn = existing;
                String extraction;
                if (upperAction.startsWith("TYPE ") || upperAction.startsWith("SET DATA TYPE ")) {
                    String typeDefinition = action.replaceFirst("(?is)^(?:TYPE|SET\\s+DATA\\s+TYPE)\\s+", "").trim();
                    Matcher usingMatcher = Pattern.compile("(?is)\\s+USING\\s+").matcher(typeDefinition);
                    if (usingMatcher.find()) {
                        diagnostics.add(diag("ALTER_COLUMN_USING_REVIEW",
                                "USING expressions are not evaluated by static DDL analysis; review the data conversion for "
                                        + tableName + "." + columnName,
                                statement.line()));
                        typeDefinition = typeDefinition.substring(0, usingMatcher.start()).trim();
                    }
                    Column parsedType = parseColumn(columnName + " " + typeDefinition,
                            sourceName, statement.line(), diagnostics);
                    if (parsedType == null) continue;
                    changedColumn = new Column(existing.name(), parsedType.dataType(), parsedType.length(),
                            parsedType.scale(), existing.nullable(), existing.defaultExpression(),
                            locator(sourceName, statement.line()));
                    extraction = "ALTER TABLE ALTER COLUMN TYPE";
                } else if (upperAction.equals("SET NOT NULL")) {
                    changedColumn = new Column(existing.name(), existing.dataType(), existing.length(),
                            existing.scale(), false, existing.defaultExpression(), locator(sourceName, statement.line()));
                    extraction = "ALTER TABLE ALTER COLUMN SET NOT NULL";
                } else if (upperAction.equals("DROP NOT NULL")) {
                    changedColumn = new Column(existing.name(), existing.dataType(), existing.length(),
                            existing.scale(), true, existing.defaultExpression(), locator(sourceName, statement.line()));
                    extraction = "ALTER TABLE ALTER COLUMN DROP NOT NULL";
                } else if (upperAction.startsWith("SET DEFAULT ")) {
                    String defaultExpression = action.substring("SET DEFAULT ".length()).trim();
                    if (defaultExpression.isEmpty()) {
                        diagnostics.add(diag("ALTER_COLUMN_DEFAULT_MISSING",
                                "SET DEFAULT requires an expression for " + tableName + "." + columnName,
                                statement.line()));
                        continue;
                    }
                    changedColumn = new Column(existing.name(), existing.dataType(), existing.length(),
                            existing.scale(), existing.nullable(), defaultExpression, locator(sourceName, statement.line()));
                    extraction = "ALTER TABLE ALTER COLUMN SET DEFAULT";
                } else {
                    changedColumn = new Column(existing.name(), existing.dataType(), existing.length(),
                            existing.scale(), existing.nullable(), null, locator(sourceName, statement.line()));
                    extraction = "ALTER TABLE ALTER COLUMN DROP DEFAULT";
                }
                List<Column> changedColumns = new ArrayList<>(table.columns());
                changedColumns.set(changedColumns.indexOf(existing), changedColumn);
                replaceTable(tables, table, new Table(table.name(), changedColumns, table.constraints(),
                        table.indexes(), table.locator()));
                evidence.add(new EvidenceDraft("column:" + tableName + "." + columnName,
                        sourceName, statement.line(), extraction, "direct", "high"));
            } else if (modifyColumnMatcher.find()) {
                String tableName = cleanName(modifyColumnMatcher.group(1));
                String definitions = modifyColumnMatcher.group(2) != null
                        ? modifyColumnMatcher.group(2) : modifyColumnMatcher.group(3);
                if (definitions != null && definitions.regionMatches(true, 0, "COLUMN ", 0, 7)) {
                    definitions = definitions.substring(7).trim();
                }
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE",
                            "ALTER TABLE references table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                if (definitions == null || definitions.isBlank()) {
                    diagnostics.add(diag("ALTER_MODIFY_COLUMN_EMPTY",
                            "ALTER TABLE MODIFY requires at least one column definition.", statement.line()));
                    continue;
                }
                List<Column> changedColumns = new ArrayList<>(table.columns());
                boolean changedAny = false;
                for (String definition : splitTopLevel(definitions)) {
                    Matcher columnDefinition = Pattern.compile("(?is)^([\\\"]?[A-Za-z0-9_$#]+[\\\"]?)\\s+(.+)$")
                            .matcher(definition.trim());
                    if (!columnDefinition.find()) {
                        diagnostics.add(diag("UNPARSED_COLUMN_MODIFY",
                                "Could not parse ALTER TABLE MODIFY column: " + abbreviate(definition),
                                statement.line()));
                        continue;
                    }
                    String columnName = cleanName(columnDefinition.group(1));
                    Column existing = changedColumns.stream()
                            .filter(column -> column.name().equalsIgnoreCase(columnName))
                            .findFirst().orElse(null);
                    if (existing == null) {
                        diagnostics.add(diag("ALTER_COLUMN_NOT_FOUND",
                                "ALTER TABLE MODIFY references an unknown column: " + tableName + "." + columnName,
                                statement.line()));
                        continue;
                    }
                    String columnOptions = columnDefinition.group(2).trim();
                    Column parsed = parseColumn(columnName + " " + columnOptions,
                            sourceName, statement.line(), diagnostics);
                    if (parsed == null) continue;
                    boolean nullable = existing.nullable();
                    String upperOptions = columnOptions.toUpperCase(Locale.ROOT);
                    if (upperOptions.matches("(?s).*\\bNOT\\s+NULL\\b.*")) nullable = false;
                    else if (upperOptions.matches("(?s).*\\bNULL\\b.*")) nullable = true;
                    String defaultExpression = parsed.defaultExpression() == null
                            ? existing.defaultExpression() : parsed.defaultExpression();
                    Column changed = new Column(existing.name(), parsed.dataType(), parsed.length(),
                            parsed.scale(), nullable, defaultExpression, locator(sourceName, statement.line()));
                    changedColumns.set(changedColumns.indexOf(existing), changed);
                    changedAny = true;
                    evidence.add(new EvidenceDraft("column:" + tableName + "." + columnName,
                            sourceName, statement.line(), "ALTER TABLE MODIFY COLUMN", "direct", "high"));
                }
                if (changedAny) {
                    replaceTable(tables, table, new Table(table.name(), changedColumns, table.constraints(),
                            table.indexes(), table.locator()));
                }
            } else if (alterMatcher.find()) {
                String tableName = cleanName(alterMatcher.group(1));
                String constraintName = cleanName(alterMatcher.group(2));
                String definition = alterMatcher.group(3).trim();
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE", "ALTER TABLE references table not parsed in this input: " + tableName, statement.line()));
                    continue;
                }
                String upperDefinition = definition.toUpperCase(Locale.ROOT);
                boolean addsConstraint = upperDefinition.startsWith("CONSTRAINT ")
                        || upperDefinition.startsWith("PRIMARY KEY")
                        || upperDefinition.startsWith("FOREIGN KEY")
                        || upperDefinition.startsWith("UNIQUE ")
                        || upperDefinition.startsWith("CHECK ");
                if (addsConstraint) {
                    Constraint constraint = parseConstraint(constraintName, definition, sourceName, statement.line(), diagnostics);
                    if (constraint != null) {
                        List<Constraint> changed = new ArrayList<>(table.constraints());
                        changed.add(constraint);
                        replaceTable(tables, table, new Table(table.name(), table.columns(), changed,
                                table.indexes(), table.locator()));
                        evidence.add(new EvidenceDraft("constraint:" + tableName + "." + constraint.name(),
                                sourceName, statement.line(), "ALTER TABLE ADD", "direct", "high"));
                    }
                } else {
                    String columnDefinitions = definition;
                    if (columnDefinitions.regionMatches(true, 0, "COLUMN ", 0, 7)) {
                        columnDefinitions = columnDefinitions.substring(7).trim();
                    }
                    if (columnDefinitions.startsWith("(") && columnDefinitions.endsWith(")")) {
                        columnDefinitions = columnDefinitions.substring(1, columnDefinitions.length() - 1);
                    }
                    List<Column> changed = new ArrayList<>(table.columns());
                    for (String columnDefinition : splitTopLevel(columnDefinitions)) {
                        Column column = parseColumn(columnDefinition, sourceName, statement.line(), diagnostics);
                        if (column == null) continue;
                        if (changed.stream().anyMatch(existing -> existing.name().equalsIgnoreCase(column.name()))) {
                            diagnostics.add(diag("DUPLICATE_COLUMN",
                                    "ALTER TABLE attempts to add an existing column: " + tableName + "." + column.name(),
                                    statement.line()));
                            continue;
                        }
                        changed.add(column);
                        evidence.add(new EvidenceDraft("column:" + tableName + "." + column.name(),
                                sourceName, statement.line(), "ALTER TABLE ADD COLUMN", "direct", "high"));
                    }
                    if (!changed.equals(table.columns())) {
                        replaceTable(tables, table, new Table(table.name(), changed, table.constraints(),
                                table.indexes(), table.locator()));
                    }
                }
            } else if (renameColumnMatcher.find()) {
                String tableName = cleanName(renameColumnMatcher.group(1));
                String oldName = cleanName(renameColumnMatcher.group(2));
                String newName = cleanName(renameColumnMatcher.group(3));
                Table table = findTable(tables, tableName);
                if (table == null) {
                    diagnostics.add(diag("ALTER_TABLE_BEFORE_CREATE",
                            "ALTER TABLE references table not parsed in this input: " + tableName,
                            statement.line()));
                    continue;
                }
                Column existing = table.columns().stream()
                        .filter(column -> column.name().equalsIgnoreCase(oldName))
                        .findFirst().orElse(null);
                if (existing == null) {
                    diagnostics.add(diag("RENAME_COLUMN_NOT_FOUND",
                            "ALTER TABLE attempts to rename an unknown column: " + tableName + "." + oldName,
                            statement.line()));
                    continue;
                }
                if (table.columns().stream().anyMatch(column -> column.name().equalsIgnoreCase(newName))) {
                    diagnostics.add(diag("DUPLICATE_COLUMN",
                            "ALTER TABLE rename target already exists: " + tableName + "." + newName,
                            statement.line()));
                    continue;
                }
                List<Column> changedColumns = new ArrayList<>(table.columns());
                changedColumns.remove(existing);
                changedColumns.add(new Column(newName, existing.dataType(), existing.length(), existing.scale(),
                        existing.nullable(), existing.defaultExpression(), existing.locator()));
                List<Constraint> changedConstraints = table.constraints().stream()
                        .map(constraint -> new Constraint(constraint.name(), constraint.type(),
                                constraint.columns().stream().map(column -> column.equalsIgnoreCase(oldName) ? newName : column).toList(),
                                constraint.referencedTable(), constraint.referencedColumns(), constraint.locator()))
                        .toList();
                for (int i = 0; i < indexes.size(); i++) {
                    Index index = indexes.get(i);
                    if (index.tableName().equalsIgnoreCase(tableName)
                            && index.columns().stream().anyMatch(column -> column.equalsIgnoreCase(oldName))) {
                        indexes.set(i, new Index(index.name(), index.tableName(),
                                index.columns().stream().map(column -> column.equalsIgnoreCase(oldName) ? newName : column).toList(),
                                index.unique(), index.locator()));
                    }
                }
                List<Index> changedIndexes = indexes.stream()
                        .filter(index -> index.tableName().equalsIgnoreCase(tableName)).toList();
                replaceTable(tables, table, new Table(table.name(), changedColumns, changedConstraints,
                        changedIndexes, table.locator()));
                evidence.add(new EvidenceDraft("column:" + tableName + "." + newName,
                        sourceName, statement.line(), "ALTER TABLE RENAME COLUMN", "direct", "high"));
            } else if (!isNonDdlStatement(text)) {
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

    private static boolean isNonDdlStatement(String statement) {
        String text = statement.stripLeading();
        while (true) {
            if (text.startsWith("--")) {
                int newline = text.indexOf('\n');
                text = newline < 0 ? "" : text.substring(newline + 1).stripLeading();
            } else if (text.startsWith("/*")) {
                int close = text.indexOf("*/", 2);
                if (close < 0) return false;
                text = text.substring(close + 2).stripLeading();
            } else break;
        }
        int end = 0;
        while (end < text.length() && Character.isLetter(text.charAt(end))) end++;
        if (end == 0) return false;
        String keyword = text.substring(0, end).toUpperCase(Locale.ROOT);
        return Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE",
                "CALL", "BEGIN", "COMMIT", "ROLLBACK", "EXPLAIN").contains(keyword);
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
            Matcher localColumns = Pattern.compile("(?is)^FOREIGN\\s+KEY\\s*\\((.*?)\\)").matcher(text);
            if (!localColumns.find()) {
                diagnostics.add(diag("UNPARSED_FOREIGN_KEY", "Foreign key has no supported local column list", line));
                return null;
            }
            return new Constraint(name == null ? "FK_" + line : name, "FOREIGN_KEY",
                    splitIdentifiers(localColumns.group(1)), cleanName(ref.group(1)),
                    splitIdentifiers(ref.group(2)), locator(source, line));
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
    private static boolean sameTableReference(String reference, String target) {
        if (reference == null || target == null) return false;
        String left = reference.replace("\\"", "").toLowerCase(Locale.ROOT);
        String right = target.replace("\\"", "").toLowerCase(Locale.ROOT);
        return left.equals(right) || left.endsWith("." + right) || right.endsWith("." + left);
    }

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
