/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.analyzer;

import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.sql.planner.ParameterRewriter;
import com.facebook.presto.sql.tree.ArithmeticBinaryExpression;
import com.facebook.presto.sql.tree.ArithmeticUnaryExpression;
import com.facebook.presto.sql.tree.ArrayConstructor;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.AtTimeZone;
import com.facebook.presto.sql.tree.BetweenPredicate;
import com.facebook.presto.sql.tree.BindExpression;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.CoalesceExpression;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.CurrentTime;
import com.facebook.presto.sql.tree.DereferenceExpression;
import com.facebook.presto.sql.tree.ExistsPredicate;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.ExpressionTreeRewriter;
import com.facebook.presto.sql.tree.Extract;
import com.facebook.presto.sql.tree.FieldReference;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.IfExpression;
import com.facebook.presto.sql.tree.InListExpression;
import com.facebook.presto.sql.tree.InPredicate;
import com.facebook.presto.sql.tree.IsNotNullPredicate;
import com.facebook.presto.sql.tree.IsNullPredicate;
import com.facebook.presto.sql.tree.LambdaExpression;
import com.facebook.presto.sql.tree.LikePredicate;
import com.facebook.presto.sql.tree.Literal;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.NullIfExpression;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Row;
import com.facebook.presto.sql.tree.SearchedCaseExpression;
import com.facebook.presto.sql.tree.SimpleCaseExpression;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.SubqueryExpression;
import com.facebook.presto.sql.tree.SubscriptExpression;
import com.facebook.presto.sql.tree.TryExpression;
import com.facebook.presto.sql.tree.WhenClause;
import com.facebook.presto.sql.tree.Window;
import com.facebook.presto.sql.tree.WindowFrame;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.sql.NodeUtils.getSortItemsFromOrderBy;
import static com.facebook.presto.sql.analyzer.LambdaReferenceExtractor.hasReferencesToLambdaArgument;
import static com.facebook.presto.sql.analyzer.ScopeReferenceExtractor.getReferencesToScope;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MUST_BE_AGGREGATE_OR_GROUP_BY;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MUST_BE_AGGREGATION_FUNCTION;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NESTED_AGGREGATION;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NESTED_WINDOW;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.REFERENCE_TO_OUTPUT_ATTRIBUTE_WITHIN_ORDER_BY_AGGREGATION;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Checks whether an expression is constant with respect to the group
 */
class AggregationAnalyzer
{
    // fields and expressions in the group by clause
    private final List<Integer> fieldIndexes;
    private final List<Expression> expressions;

    private final Metadata metadata;
    private final Analysis analysis;

    private final Scope sourceScope;
    private final Optional<Scope> orderByScope;

    public static void verifySourceAggregations(
            List<Expression> groupByExpressions,
            Scope sourceScope,
            Expression expression,
            Metadata metadata,
            Analysis analysis)
    {
        AggregationAnalyzer analyzer = new AggregationAnalyzer(groupByExpressions, sourceScope, Optional.empty(), metadata, analysis);
        analyzer.analyze(expression);
    }

    public static void verifyOrderByAggregations(
            List<Expression> groupByExpressions,
            Scope sourceScope,
            Scope orderByScope,
            Expression expression,
            Metadata metadata,
            Analysis analysis)
    {
        AggregationAnalyzer analyzer = new AggregationAnalyzer(groupByExpressions, sourceScope, Optional.of(orderByScope), metadata, analysis);
        analyzer.analyze(expression);
    }

    private AggregationAnalyzer(List<Expression> groupByExpressions, Scope sourceScope, Optional<Scope> orderByScope, Metadata metadata, Analysis analysis)
    {
        requireNonNull(groupByExpressions, "groupByExpressions is null");
        requireNonNull(sourceScope, "sourceScope is null");
        requireNonNull(orderByScope, "orderByScope is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(analysis, "analysis is null");

        this.sourceScope = sourceScope;
        this.orderByScope = orderByScope;
        this.metadata = metadata;
        this.analysis = analysis;
        this.expressions = groupByExpressions.stream()
                .map(e -> ExpressionTreeRewriter.rewriteWith(new ParameterRewriter(analysis.getParameters()), e))
                .collect(toImmutableList());
        ImmutableList.Builder<Integer> fieldIndexes = ImmutableList.builder();

        fieldIndexes.addAll(groupByExpressions.stream()
                .filter(FieldReference.class::isInstance)
                .map(FieldReference.class::cast)
                .map(FieldReference::getFieldIndex)
                .iterator());

        // For a query like "SELECT * FROM T GROUP BY a", groupByExpressions will contain "a",
        // and the '*' will be expanded to Field references. Therefore we translate all simple name expressions
        // in the group by clause to fields they reference so that the expansion from '*' can be matched against them
        for (Expression expression : Iterables.filter(expressions, analysis.getColumnReferences()::contains)) {
            QualifiedName name;
            if (expression instanceof Identifier) {
                name = QualifiedName.of(((Identifier) expression).getName());
            }
            else {
                name = DereferenceExpression.getQualifiedName((DereferenceExpression) expression);
            }

            List<Field> fields = sourceScope.getRelationType().resolveFields(name);
            checkState(fields.size() <= 1, "Found more than one field for name '%s': %s", name, fields);

            if (fields.size() == 1) {
                Field field = Iterables.getOnlyElement(fields);
                fieldIndexes.add(sourceScope.getRelationType().indexOf(field));
            }
        }
        this.fieldIndexes = fieldIndexes.build();
    }

    private void analyze(Expression expression)
    {
        Visitor visitor = new Visitor();
        if (!visitor.process(expression, null)) {
            throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, expression, "'%s' must be an aggregate expression or appear in GROUP BY clause", expression);
        }
    }

    /**
     * visitor returns true if all expressions are constant with respect to the group.
     */
    private class Visitor
            extends AstVisitor<Boolean, Void>
    {
        @Override
        protected Boolean visitExpression(Expression node, Void context)
        {
            throw new UnsupportedOperationException("aggregation analysis not yet implemented for: " + node.getClass().getName());
        }

        @Override
        protected Boolean visitAtTimeZone(AtTimeZone node, Void context)
        {
            return process(node.getValue(), context);
        }

        @Override
        protected Boolean visitSubqueryExpression(SubqueryExpression node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitExists(ExistsPredicate node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitSubscriptExpression(SubscriptExpression node, Void context)
        {
            return process(node.getBase(), context) &&
                    process(node.getIndex(), context);
        }

        @Override
        protected Boolean visitArrayConstructor(ArrayConstructor node, Void context)
        {
            return node.getValues().stream().allMatch(expression -> process(expression, context));
        }

        @Override
        protected Boolean visitCast(Cast node, Void context)
        {
            return process(node.getExpression(), context);
        }

        @Override
        protected Boolean visitCoalesceExpression(CoalesceExpression node, Void context)
        {
            return node.getOperands().stream().allMatch(expression -> process(expression, context));
        }

        @Override
        protected Boolean visitNullIfExpression(NullIfExpression node, Void context)
        {
            return process(node.getFirst(), context) && process(node.getSecond(), context);
        }

        @Override
        protected Boolean visitExtract(Extract node, Void context)
        {
            return process(node.getExpression(), context);
        }

        @Override
        protected Boolean visitBetweenPredicate(BetweenPredicate node, Void context)
        {
            return process(node.getMin(), context) &&
                    process(node.getValue(), context) &&
                    process(node.getMax(), context);
        }

        @Override
        protected Boolean visitCurrentTime(CurrentTime node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitArithmeticBinary(ArithmeticBinaryExpression node, Void context)
        {
            return process(node.getLeft(), context) && process(node.getRight(), context);
        }

        @Override
        protected Boolean visitComparisonExpression(ComparisonExpression node, Void context)
        {
            return process(node.getLeft(), context) && process(node.getRight(), context);
        }

        @Override
        protected Boolean visitLiteral(Literal node, Void context)
        {
            return true;
        }

        @Override
        protected Boolean visitIsNotNullPredicate(IsNotNullPredicate node, Void context)
        {
            return process(node.getValue(), context);
        }

        @Override
        protected Boolean visitIsNullPredicate(IsNullPredicate node, Void context)
        {
            return process(node.getValue(), context);
        }

        @Override
        protected Boolean visitLikePredicate(LikePredicate node, Void context)
        {
            return process(node.getValue(), context) && process(node.getPattern(), context);
        }

        @Override
        protected Boolean visitInListExpression(InListExpression node, Void context)
        {
            return node.getValues().stream().allMatch(expression -> process(expression, context));
        }

        @Override
        protected Boolean visitInPredicate(InPredicate node, Void context)
        {
            return process(node.getValue(), context) && process(node.getValueList(), context);
        }

        @Override
        protected Boolean visitFunctionCall(FunctionCall node, Void context)
        {
            if (metadata.isAggregationFunction(node.getName())) {
                if (!node.getWindow().isPresent()) {
                    AggregateExtractor aggregateExtractor = new AggregateExtractor(metadata.getFunctionRegistry());
                    WindowFunctionExtractor windowExtractor = new WindowFunctionExtractor();

                    for (Expression argument : node.getArguments()) {
                        aggregateExtractor.process(argument, null);
                        windowExtractor.process(argument, null);
                    }

                    if (!aggregateExtractor.getAggregates().isEmpty()) {
                        throw new SemanticException(NESTED_AGGREGATION,
                                node,
                                "Cannot nest aggregations inside aggregation '%s': %s",
                                node.getName(),
                                aggregateExtractor.getAggregates());
                    }

                    if (!windowExtractor.getWindowFunctions().isEmpty()) {
                        throw new SemanticException(NESTED_WINDOW,
                                node,
                                "Cannot nest window functions inside aggregation '%s': %s",
                                node.getName(),
                                windowExtractor.getWindowFunctions());
                    }

                    if (node.getFilter().isPresent() && node.isDistinct()) {
                        throw new SemanticException(NOT_SUPPORTED,
                                node,
                                "Filtered aggregations not supported with DISTINCT: '%s'",
                                node);
                    }

                    // ensure that no output fields are referenced from ORDER BY clause
                    if (orderByScope.isPresent()) {
                        node.getArguments().stream().forEach(AggregationAnalyzer.this::verifyNoOrderByReferencesToOutputColumns);
                    }

                    return true;
                }
            }
            else if (node.getFilter().isPresent()) {
                throw new SemanticException(MUST_BE_AGGREGATION_FUNCTION,
                        node,
                        "Filter is only valid for aggregation functions",
                        node);
            }

            if (node.getWindow().isPresent() && !process(node.getWindow().get(), context)) {
                return false;
            }

            return node.getArguments().stream().allMatch(expression -> process(expression, context));
        }

        @Override
        protected Boolean visitLambdaExpression(LambdaExpression node, Void context)
        {
            return process(node.getBody(), context);
        }

        @Override
        protected Boolean visitBindExpression(BindExpression node, Void context)
        {
            return process(node.getValue(), context) && process(node.getFunction(), context);
        }

        @Override
        public Boolean visitWindow(Window node, Void context)
        {
            for (Expression expression : node.getPartitionBy()) {
                if (!process(expression, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY,
                            expression,
                            "PARTITION BY expression '%s' must be an aggregate expression or appear in GROUP BY clause",
                            expression);
                }
            }

            for (SortItem sortItem : getSortItemsFromOrderBy(node.getOrderBy())) {
                Expression expression = sortItem.getSortKey();
                if (!process(expression, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY,
                            expression,
                            "ORDER BY expression '%s' must be an aggregate expression or appear in GROUP BY clause",
                            expression);
                }
            }

            if (node.getFrame().isPresent()) {
                process(node.getFrame().get(), context);
            }

            return true;
        }

        @Override
        public Boolean visitWindowFrame(WindowFrame node, Void context)
        {
            Optional<Expression> start = node.getStart().getValue();
            if (start.isPresent()) {
                if (!process(start.get(), context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, start.get(), "Window frame start must be an aggregate expression or appear in GROUP BY clause");
                }
            }
            if (node.getEnd().isPresent() && node.getEnd().get().getValue().isPresent()) {
                Expression endValue = node.getEnd().get().getValue().get();
                if (!process(endValue, context)) {
                    throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, endValue, "Window frame end must be an aggregate expression or appear in GROUP BY clause");
                }
            }

            return true;
        }

        @Override
        protected Boolean visitIdentifier(Identifier node, Void context)
        {
            if (analysis.getLambdaArgumentReferences().containsKey(node)) {
                return true;
            }
            return isField(node, QualifiedName.of(node.getName()));
        }

        @Override
        protected Boolean visitDereferenceExpression(DereferenceExpression node, Void context)
        {
            if (analysis.getColumnReferences().contains(node)) {
                return isField(node, DereferenceExpression.getQualifiedName(node));
            }

            // Allow SELECT col1.f1 FROM table1 GROUP BY col1
            return process(node.getBase(), context);
        }

        private boolean isField(Expression node, QualifiedName qualifiedName)
        {
            Scope scope = orderByScope.orElse(sourceScope);

            ResolvedField resolvedField = scope.resolveField(node, qualifiedName);
            if (orderByScope.isPresent() && resolvedField.getScope().equals(orderByScope.get())) {
                return true;
            }

            return resolvedField.getScope().equals(sourceScope) && fieldIndexes.contains(resolvedField.getRelationFieldIndex());
        }

        @Override
        protected Boolean visitFieldReference(FieldReference node, Void context)
        {
            if (orderByScope.isPresent()) {
                return true;
            }

            boolean inGroup = fieldIndexes.contains(node.getFieldIndex());
            if (!inGroup) {
                Field field = sourceScope.getRelationType().getFieldByIndex(node.getFieldIndex());

                String column;
                if (!field.getName().isPresent()) {
                    column = Integer.toString(node.getFieldIndex() + 1);
                }
                else if (field.getRelationAlias().isPresent()) {
                    column = String.format("'%s.%s'", field.getRelationAlias().get(), field.getName().get());
                }
                else {
                    column = "'" + field.getName().get() + "'";
                }

                throw new SemanticException(MUST_BE_AGGREGATE_OR_GROUP_BY, node, "Column %s not in GROUP BY clause", column);
            }
            return inGroup;
        }

        @Override
        protected Boolean visitArithmeticUnary(ArithmeticUnaryExpression node, Void context)
        {
            return process(node.getValue(), context);
        }

        @Override
        protected Boolean visitNotExpression(NotExpression node, Void context)
        {
            return process(node.getValue(), context);
        }

        @Override
        protected Boolean visitLogicalBinaryExpression(LogicalBinaryExpression node, Void context)
        {
            return process(node.getLeft(), context) && process(node.getRight(), context);
        }

        @Override
        protected Boolean visitIfExpression(IfExpression node, Void context)
        {
            ImmutableList.Builder<Expression> expressions = ImmutableList.<Expression>builder()
                    .add(node.getCondition())
                    .add(node.getTrueValue());

            if (node.getFalseValue().isPresent()) {
                expressions.add(node.getFalseValue().get());
            }

            return expressions.build().stream().allMatch(expression -> process(expression, context));
        }

        @Override
        protected Boolean visitSimpleCaseExpression(SimpleCaseExpression node, Void context)
        {
            if (!process(node.getOperand(), context)) {
                return false;
            }

            for (WhenClause whenClause : node.getWhenClauses()) {
                if (!process(whenClause.getOperand(), context) || !process(whenClause.getResult(), context)) {
                    return false;
                }
            }

            if (node.getDefaultValue().isPresent() && !process(node.getDefaultValue().get(), context)) {
                return false;
            }

            return true;
        }

        @Override
        protected Boolean visitSearchedCaseExpression(SearchedCaseExpression node, Void context)
        {
            for (WhenClause whenClause : node.getWhenClauses()) {
                if (!process(whenClause.getOperand(), context) || !process(whenClause.getResult(), context)) {
                    return false;
                }
            }

            return !node.getDefaultValue().isPresent() || process(node.getDefaultValue().get(), context);
        }

        @Override
        protected Boolean visitTryExpression(TryExpression node, Void context)
        {
            return process(node.getInnerExpression(), context);
        }

        @Override
        public Boolean visitRow(Row node, final Void context)
        {
            return node.getItems().stream()
                    .allMatch(item -> process(item, context));
        }

        @Override
        public Boolean visitParameter(Parameter node, Void context)
        {
            if (analysis.isDescribe()) {
                return true;
            }
            List<Expression> parameters = analysis.getParameters();
            checkArgument(node.getPosition() < parameters.size(), "Invalid parameter number %s, max values is %s", node.getPosition(), parameters.size() - 1);
            return process(parameters.get(node.getPosition()), context);
        }

        @Override
        public Boolean process(Node node, @Nullable Void context)
        {
            if (expressions.stream().anyMatch(node::equals)
                    && (!orderByScope.isPresent() || !hasOrderByReferencesToOutputColumns(node))
                    && !hasReferencesToLambdaArgument(node, analysis)) {
                return true;
            }

            return super.process(node, context);
        }
    }

    private boolean hasOrderByReferencesToOutputColumns(Node node)
    {
        return !getReferencesToScope(node, analysis, orderByScope.get()).isEmpty();
    }

    private void verifyNoOrderByReferencesToOutputColumns(Node node)
    {
        getReferencesToScope(node, analysis, orderByScope.get()).stream()
                .findFirst()
                .ifPresent(expression -> {
                    throw new SemanticException(REFERENCE_TO_OUTPUT_ATTRIBUTE_WITHIN_ORDER_BY_AGGREGATION, expression, "Invalid reference to output projection attribute from ORDER BY aggregation");
                });
    }
}
