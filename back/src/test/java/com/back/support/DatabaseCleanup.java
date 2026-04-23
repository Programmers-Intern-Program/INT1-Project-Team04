package com.back.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public class DatabaseCleanup {

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final List<String> tableNames;

    public DatabaseCleanup(EntityManager entityManager, JdbcTemplate jdbcTemplate) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.tableNames = entityManager.getMetamodel().getEntities()
                .stream()
                .map(entityType -> entityType.getJavaType().getAnnotation(Table.class))
                .filter(table -> table != null && !table.name().isBlank())
                .map(Table::name)
                .distinct()
                .sorted()
                .toList();
    }

    public void execute() {
        if (tableNames.isEmpty()) {
            return;
        }

        entityManager.clear();
        jdbcTemplate.execute("TRUNCATE TABLE " + joinedTableNames() + " RESTART IDENTITY CASCADE");
    }

    private String joinedTableNames() {
        return tableNames.stream()
                .map(tableName -> "\"" + tableName + "\"")
                .collect(Collectors.joining(", "));
    }
}
