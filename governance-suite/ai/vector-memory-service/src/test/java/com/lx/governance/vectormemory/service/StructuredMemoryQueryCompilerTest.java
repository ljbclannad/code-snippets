package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemorySearchFilter;
import com.lx.governance.vectormemory.dto.StructuredMemoryQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredMemoryQueryCompilerTest {

    private final StructuredMemoryQueryCompiler compiler = new StructuredMemoryQueryCompiler();

    @Test
    void shouldCompileDslExpressionToMemorySearchFilter() {
        StructuredMemoryQuery query = new StructuredMemoryQuery();
        StructuredMemoryQuery.FilterExpression expression = new StructuredMemoryQuery.FilterExpression();
        expression.setOperator("AND");

        StructuredMemoryQuery.FilterClause memoryTypes = new StructuredMemoryQuery.FilterClause();
        memoryTypes.setField("memoryTypes");
        memoryTypes.setOp("IN");
        memoryTypes.setValues(List.of("DIET", "PREFERENCE"));

        StructuredMemoryQuery.FilterClause minConfidence = new StructuredMemoryQuery.FilterClause();
        minConfidence.setField("minConfidence");
        minConfidence.setOp("GTE");
        minConfidence.setValues(List.of("0.8"));

        StructuredMemoryQuery.FilterClause verifiedOnly = new StructuredMemoryQuery.FilterClause();
        verifiedOnly.setField("verifiedOnly");
        verifiedOnly.setOp("EQ");
        verifiedOnly.setValues(List.of("true"));

        StructuredMemoryQuery.FilterClause recentDays = new StructuredMemoryQuery.FilterClause();
        recentDays.setField("recentDays");
        recentDays.setOp("LTE");
        recentDays.setValues(List.of("30"));

        expression.setClauses(List.of(memoryTypes, minConfidence, verifiedOnly, recentDays));
        query.setFilterExpression(expression);

        MemorySearchFilter filter = compiler.compile(query, MemorySearchFilter.builder().build());

        assertEquals(Set.of("DIET", "PREFERENCE"), filter.getMemoryTypes());
        assertEquals(0.8, filter.getMinConfidence());
        assertTrue(Boolean.TRUE.equals(filter.getVerifiedOnly()));
        assertEquals(30, filter.getRecentDays());
    }

    @Test
    void shouldCompileNestedOrExpressions() {
        StructuredMemoryQuery query = new StructuredMemoryQuery();

        StructuredMemoryQuery.FilterClause typeClause = new StructuredMemoryQuery.FilterClause();
        typeClause.setField("memoryType");
        typeClause.setOp("IN");
        typeClause.setValues(List.of("DIET"));

        StructuredMemoryQuery.FilterClause keyClause = new StructuredMemoryQuery.FilterClause();
        keyClause.setField("memoryKey");
        keyClause.setOp("CONTAINS");
        keyClause.setValues(List.of("晚饭"));

        StructuredMemoryQuery.FilterClause contentClause = new StructuredMemoryQuery.FilterClause();
        contentClause.setField("memoryContent");
        contentClause.setOp("CONTAINS");
        contentClause.setValues(List.of("控糖"));

        StructuredMemoryQuery.FilterExpression orExpression = new StructuredMemoryQuery.FilterExpression();
        orExpression.setOperator("OR");
        orExpression.setClauses(List.of(keyClause, contentClause));

        StructuredMemoryQuery.FilterExpression root = new StructuredMemoryQuery.FilterExpression();
        root.setOperator("AND");
        root.setClauses(List.of(typeClause));
        root.setExpressions(List.of(orExpression));
        query.setFilterExpression(root);

        MemorySearchFilter filter = compiler.compile(query, MemorySearchFilter.builder().build());

        assertTrue(filter.getExpression() != null);
        assertEquals("AND", filter.getExpression().getOperator());
        assertEquals(1, filter.getExpression().getExpressions().size());
        assertEquals("OR", filter.getExpression().getExpressions().get(0).getOperator());
    }
}
