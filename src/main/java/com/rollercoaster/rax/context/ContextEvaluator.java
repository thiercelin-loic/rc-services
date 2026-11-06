package com.rollercoaster.rax.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Component
public class ContextEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextEvaluator.class);

    private static final String ERR_CIRCULAR_DEPENDENCY =
            "ERR_CIRCULAR_DEP: Circular dependency detected during CALCULATE Context Transition on table '%s' without a unique index column.";

    public record CalculateModifier(
            ModifierType type,
            String targetTableOrColumn,
            String secondaryColumn,
            String direction,
            List<String> preservedColumns) {

        public CalculateModifier {
            preservedColumns = preservedColumns != null ? List.copyOf(preservedColumns) : List.of();
        }

        public enum ModifierType {
            ALL,
            REMOVEFILTERS,
            ALLEXCEPT,
            ALLSELECTED,
            USERELATIONSHIP,
            CROSSFILTER,
            KEEPFILTERS
        }
    }

    public record ExplicitFilterPredicate(
            String targetColumn,
            Set<Object> allowedValues,
            String sqlCondition,
            boolean keepFilters) {

        public ExplicitFilterPredicate {
            allowedValues = allowedValues != null ? Set.copyOf(allowedValues) : Set.of();
        }
    }

    public FilterContext synthesizeCalculateContext(
            FilterContext parentFilterContext,
            RowContext activeRowContext,
            List<CalculateModifier> modifiers,
            List<ExplicitFilterPredicate> explicitFilters) {

        LOGGER.trace("Synthesizing CALCULATE context (Modifiers: {}, ExplicitFilters: {})",
                modifiers != null ? modifiers.size() : 0,
                explicitFilters != null ? explicitFilters.size() : 0);

        FilterContext synthesized = cloneOrInitializeContext(parentFilterContext);
        applyContextTransition(synthesized, activeRowContext);
        applyCalculateModifiers(synthesized, modifiers);
        applyExplicitFilters(synthesized, explicitFilters);

        return synthesized;
    }

    public <T> T evaluateInSynthesizedContext(
            FilterContext parentFilterContext,
            RowContext activeRowContext,
            List<CalculateModifier> modifiers,
            List<ExplicitFilterPredicate> explicitFilters,
            Function<FilterContext, T> evaluator) {
        FilterContext synthesized = synthesizeCalculateContext(
                parentFilterContext, activeRowContext, modifiers, explicitFilters);
        return evaluator.apply(synthesized);
    }

    private FilterContext cloneOrInitializeContext(FilterContext parentFilterContext) {
        return parentFilterContext != null ? parentFilterContext.cloneContext() : new FilterContext();
    }

    private void applyContextTransition(FilterContext targetContext, RowContext activeRowContext) {
        if (activeRowContext == null) {
            return;
        }

        if (!activeRowContext.hasPrimaryKeyIndex() && activeRowContext.getActiveCalculatedColumnStack().size() > 1) {
            String errorMessage = String.format(ERR_CIRCULAR_DEPENDENCY, activeRowContext.getTableName());
            LOGGER.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        Map<String, Set<Object>> rowPredicates = activeRowContext.toContextTransitionPredicates();
        for (Map.Entry<String, Set<Object>> entry : rowPredicates.entrySet()) {
            targetContext.replaceFilter(entry.getKey(), entry.getValue());
        }
    }

    private void applyCalculateModifiers(FilterContext targetContext, List<CalculateModifier> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return;
        }
        for (CalculateModifier modifier : modifiers) {
            applySingleModifier(targetContext, modifier);
        }
    }

    private void applySingleModifier(FilterContext targetContext, CalculateModifier modifier) {
        switch (modifier.type()) {
            case ALL, REMOVEFILTERS -> targetContext.applyAllModifier(modifier.targetTableOrColumn());
            case ALLEXCEPT -> targetContext.applyAllExceptModifier(
                    modifier.targetTableOrColumn(),
                    modifier.preservedColumns());
            case ALLSELECTED -> targetContext.applyAllSelectedModifier(modifier.targetTableOrColumn());
            case USERELATIONSHIP -> targetContext.activateRelationship(
                    modifier.targetTableOrColumn(),
                    modifier.secondaryColumn());
            case CROSSFILTER -> targetContext.overrideCrossFilter(
                    modifier.targetTableOrColumn(),
                    modifier.secondaryColumn(),
                    modifier.direction());
            case KEEPFILTERS -> targetContext.markKeepFilters(modifier.targetTableOrColumn());
        }
    }

    private void applyExplicitFilters(FilterContext targetContext, List<ExplicitFilterPredicate> explicitFilters) {
        if (explicitFilters == null || explicitFilters.isEmpty()) {
            return;
        }
        for (ExplicitFilterPredicate filter : explicitFilters) {
            applySingleExplicitFilter(targetContext, filter);
        }
    }

    private void applySingleExplicitFilter(FilterContext targetContext, ExplicitFilterPredicate filter) {
        if (filter.keepFilters()) {
            targetContext.markKeepFilters(filter.targetColumn());
        }

        if (filter.allowedValues() != null && !filter.allowedValues().isEmpty()) {
            if (filter.keepFilters()) {
                targetContext.intersectFilter(filter.targetColumn(), filter.allowedValues());
            } else {
                targetContext.replaceFilter(filter.targetColumn(), filter.allowedValues());
            }
        }

        if (filter.sqlCondition() != null && !filter.sqlCondition().isBlank()) {
            if (!filter.keepFilters() && filter.targetColumn() != null && !filter.targetColumn().isBlank()) {
                targetContext.applyAllModifier(filter.targetColumn());
            }
            targetContext.addSqlPredicate(filter.sqlCondition());
        }
    }
}
