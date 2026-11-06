package com.rollercoaster.rax.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class LexicalSymbolTable {

    private final LexicalSymbolTable parent;
    private final Map<String, Object> evaluatedVariables = new LinkedHashMap<>();
    private final Map<String, String> sqlExpressions = new LinkedHashMap<>();

    public LexicalSymbolTable() {
        this(null);
    }

    public LexicalSymbolTable(LexicalSymbolTable parent) {
        this.parent = parent;
    }

    public void bindVariable(String name, Object evaluatedValue, String compiledSqlExpression) {
        String key = normalize(name);
        evaluatedVariables.put(key, evaluatedValue);
        if (compiledSqlExpression != null) {
            sqlExpressions.put(key, compiledSqlExpression);
        }
    }

    public Optional<Object> resolveValue(String name) {
        String key = normalize(name);
        if (evaluatedVariables.containsKey(key)) {
            return Optional.ofNullable(evaluatedVariables.get(key));
        }
        return parent != null ? parent.resolveValue(name) : Optional.empty();
    }

    public Optional<String> resolveSqlExpression(String name) {
        String key = normalize(name);
        if (sqlExpressions.containsKey(key)) {
            return Optional.of(sqlExpressions.get(key));
        }
        return parent != null ? parent.resolveSqlExpression(name) : Optional.empty();
    }

    public boolean contains(String name) {
        String key = normalize(name);
        return evaluatedVariables.containsKey(key)
                || sqlExpressions.containsKey(key)
                || (parent != null && parent.contains(name));
    }

    public Map<String, String> getAllSqlExpressions() {
        return Collections.unmodifiableMap(sqlExpressions);
    }

    private String normalize(String name) {
        return name.trim().toLowerCase();
    }
}
