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
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.cost.CachingCostProvider;
import com.facebook.presto.cost.CachingStatsProvider;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.CostProvider;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.cost.StatsProvider;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.NewTableLayout;
import com.facebook.presto.metadata.TableMetadata;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.LimitNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.statistics.TableStatisticsMetadata;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Field;
import com.facebook.presto.sql.analyzer.RelationId;
import com.facebook.presto.sql.analyzer.RelationType;
import com.facebook.presto.sql.analyzer.Scope;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.StatisticsAggregationPlanner.TableStatisticAggregation;
import com.facebook.presto.sql.planner.optimizations.PlanNodeSearcher;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.planner.plan.DeleteNode;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticsWriterNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode.DeleteHandle;
import com.facebook.presto.sql.planner.sanity.PlanChecker;
import com.facebook.presto.sql.tree.Analyze;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.CreateTableAsSelect;
import com.facebook.presto.sql.tree.Delete;
import com.facebook.presto.sql.tree.Explain;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.Insert;
import com.facebook.presto.sql.tree.LambdaArgumentDeclaration;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.RefreshMaterializedView;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.SystemSessionProperties.isPrintStatsForNonJoinQuery;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.metadata.MetadataUtil.toSchemaTableName;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.plan.AggregationNode.singleGroupingSet;
import static com.facebook.presto.spi.plan.LimitNode.Step.FINAL;
import static com.facebook.presto.spi.statistics.TableStatisticType.ROW_COUNT;
import static com.facebook.presto.sql.analyzer.ExpressionTreeUtils.createSymbolReference;
import static com.facebook.presto.sql.analyzer.ExpressionTreeUtils.getSourceLocation;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.CreateName;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.InsertReference;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.RefreshMaterializedViewReference;
import static com.facebook.presto.sql.planner.plan.TableWriterNode.WriterTarget;
import static com.facebook.presto.sql.relational.Expressions.constant;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Streams.zip;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class LogicalPlanner
{
    public enum Stage
    {
        CREATED, OPTIMIZED, OPTIMIZED_AND_VALIDATED
    }

    private final boolean explain;
    private final PlanNodeIdAllocator idAllocator;
    private final Session session;
    private final List<PlanOptimizer> planOptimizers;
    private final PlanChecker planChecker;
    private final PlanVariableAllocator variableAllocator = new PlanVariableAllocator();
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final StatisticsAggregationPlanner statisticsAggregationPlanner;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final WarningCollector warningCollector;

    public LogicalPlanner(
            boolean explain,
            Session session,
            List<PlanOptimizer> planOptimizers,
            PlanNodeIdAllocator idAllocator,
            Metadata metadata,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            WarningCollector warningCollector,
            PlanChecker planChecker)
    {
        this(explain, session, planOptimizers, planChecker, idAllocator, metadata, sqlParser, statsCalculator, costCalculator, warningCollector);
    }

    public LogicalPlanner(
            boolean explain,
            Session session,
            List<PlanOptimizer> planOptimizers,
            PlanChecker planChecker,
            PlanNodeIdAllocator idAllocator,
            Metadata metadata,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            WarningCollector warningCollector)
    {
        this.explain = explain;
        this.session = requireNonNull(session, "session is null");
        this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
        this.planChecker = requireNonNull(planChecker, "planChecker is null");
        this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.statisticsAggregationPlanner = new StatisticsAggregationPlanner(variableAllocator, metadata);
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
    }

    public Plan plan(Analysis analysis)
    {
        return plan(analysis, Stage.OPTIMIZED_AND_VALIDATED);
    }

    public Plan plan(Analysis analysis, Stage stage)
    {
        PlanNode root = planStatement(analysis, analysis.getStatement());

        planChecker.validateIntermediatePlan(root, session, metadata, sqlParser, variableAllocator.getTypes(), warningCollector);
        boolean enableVerboseRuntimeStats = SystemSessionProperties.isVerboseRuntimeStatsEnabled(session);
        if (stage.ordinal() >= Stage.OPTIMIZED.ordinal()) {
            for (PlanOptimizer optimizer : planOptimizers) {
                long start = System.nanoTime();
                root = optimizer.optimize(root, session, variableAllocator.getTypes(), variableAllocator, idAllocator, warningCollector);
                requireNonNull(root, format("%s returned a null plan", optimizer.getClass().getName()));
                if (enableVerboseRuntimeStats) {
                    session.getRuntimeStats().addMetricValue(String.format("optimizer%sTimeNanos", optimizer.getClass().getSimpleName()), System.nanoTime() - start);
                }
            }
        }

        if (stage.ordinal() >= Stage.OPTIMIZED_AND_VALIDATED.ordinal()) {
            // make sure we produce a valid plan after optimizations run. This is mainly to catch programming errors
            planChecker.validateFinalPlan(root, session, metadata, sqlParser, variableAllocator.getTypes(), warningCollector);
        }

        TypeProvider types = variableAllocator.getTypes();
        return new Plan(root, types, computeStats(root, types));
    }

    private StatsAndCosts computeStats(PlanNode root, TypeProvider types)
    {
        if (explain || isPrintStatsForNonJoinQuery(session) ||
                PlanNodeSearcher.searchFrom(root).where(node ->
                        (node instanceof JoinNode) || (node instanceof SemiJoinNode)).matches()) {
            StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, session, types);
            CostProvider costProvider = new CachingCostProvider(costCalculator, statsProvider, Optional.empty(), session);
            return StatsAndCosts.create(root, statsProvider, costProvider);
        }
        return StatsAndCosts.empty();
    }

    public PlanNode planStatement(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect && analysis.isCreateTableAsSelectNoOp()) {
            checkState(analysis.getCreateTableDestination().isPresent(), "Table destination is missing");
            VariableReferenceExpression variable = variableAllocator.newVariable(getSourceLocation(statement), "rows", BIGINT);
            PlanNode source = new ValuesNode(
                    getSourceLocation(statement),
                    idAllocator.getNextId(),
                    ImmutableList.of(variable),
                    ImmutableList.of(ImmutableList.of(constant(0L, BIGINT))));
            return new OutputNode(source.getSourceLocation(), idAllocator.getNextId(), source, ImmutableList.of("rows"), ImmutableList.of(variable));
        }
        return createOutputPlan(planStatementWithoutOutput(analysis, statement), analysis);
    }

    private RelationPlan planStatementWithoutOutput(Analysis analysis, Statement statement)
    {
        if (statement instanceof CreateTableAsSelect) {
            if (analysis.isCreateTableAsSelectNoOp()) {
                throw new PrestoException(NOT_SUPPORTED, "CREATE TABLE IF NOT EXISTS is not supported in this context " + statement.getClass().getSimpleName());
            }
            return createTableCreationPlan(analysis, ((CreateTableAsSelect) statement).getQuery());
        }
        else if (statement instanceof Analyze) {
            return createAnalyzePlan(analysis, (Analyze) statement);
        }
        else if (statement instanceof Insert) {
            checkState(analysis.getInsert().isPresent(), "Insert handle is missing");
            return createInsertPlan(analysis, (Insert) statement);
        }
        else if (statement instanceof Delete) {
            return createDeletePlan(analysis, (Delete) statement);
        }
        else if (statement instanceof Query) {
            return createRelationPlan(analysis, (Query) statement);
        }
        else if (statement instanceof Explain && ((Explain) statement).isAnalyze()) {
            return createExplainAnalyzePlan(analysis, (Explain) statement);
        }
        else if (statement instanceof RefreshMaterializedView) {
            checkState(analysis.getRefreshMaterializedViewAnalysis().isPresent(), "RefreshMaterializedView analysis is missing");
            return createRefreshMaterializedViewPlan(analysis, (RefreshMaterializedView) statement);
        }
        else {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported statement type " + statement.getClass().getSimpleName());
        }
    }

    private RelationPlan createExplainAnalyzePlan(Analysis analysis, Explain statement)
    {
        RelationPlan underlyingPlan = planStatementWithoutOutput(analysis, statement.getStatement());
        PlanNode root = underlyingPlan.getRoot();
        Scope scope = analysis.getScope(statement);
        VariableReferenceExpression outputVariable = variableAllocator.newVariable(scope.getRelationType().getFieldByIndex(0));
        root = new ExplainAnalyzeNode(getSourceLocation(statement), idAllocator.getNextId(), root, outputVariable, statement.isVerbose());
        return new RelationPlan(root, scope, ImmutableList.of(outputVariable));
    }

    private RelationPlan createAnalyzePlan(Analysis analysis, Analyze analyzeStatement)
    {
        TableHandle targetTable = analysis.getAnalyzeTarget().get();

        // Plan table scan
        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, targetTable);
        ImmutableList.Builder<VariableReferenceExpression> tableScanOutputsBuilder = ImmutableList.builder();
        ImmutableMap.Builder<VariableReferenceExpression, ColumnHandle> variableToColumnHandle = ImmutableMap.builder();
        ImmutableMap.Builder<String, VariableReferenceExpression> columnNameToVariable = ImmutableMap.builder();
        TableMetadata tableMetadata = metadata.getTableMetadata(session, targetTable);
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            VariableReferenceExpression variable = variableAllocator.newVariable(getSourceLocation(analyzeStatement), column.getName(), column.getType());
            tableScanOutputsBuilder.add(variable);
            variableToColumnHandle.put(variable, columnHandles.get(column.getName()));
            columnNameToVariable.put(column.getName(), variable);
        }

        List<VariableReferenceExpression> tableScanOutputs = tableScanOutputsBuilder.build();
        TableStatisticsMetadata tableStatisticsMetadata = metadata.getStatisticsCollectionMetadata(
                session,
                targetTable.getConnectorId().getCatalogName(),
                tableMetadata.getMetadata());

        TableStatisticAggregation tableStatisticAggregation = statisticsAggregationPlanner.createStatisticsAggregation(tableStatisticsMetadata, columnNameToVariable.build(), true);
        StatisticAggregations statisticAggregations = tableStatisticAggregation.getAggregations();

        PlanNode planNode = new StatisticsWriterNode(
                getSourceLocation(analyzeStatement),
                idAllocator.getNextId(),
                new AggregationNode(
                        getSourceLocation(analyzeStatement),
                        idAllocator.getNextId(),
                        new TableScanNode(getSourceLocation(analyzeStatement), idAllocator.getNextId(), targetTable, tableScanOutputs, variableToColumnHandle.build(), TupleDomain.all(), TupleDomain.all()),
                        statisticAggregations.getAggregations(),
                        singleGroupingSet(statisticAggregations.getGroupingVariables()),
                        ImmutableList.of(),
                        AggregationNode.Step.SINGLE,
                        Optional.empty(),
                        Optional.empty()),
                targetTable,
                variableAllocator.newVariable(getSourceLocation(analyzeStatement), "rows", BIGINT),
                tableStatisticsMetadata.getTableStatistics().contains(ROW_COUNT),
                tableStatisticAggregation.getDescriptor());
        return new RelationPlan(planNode, analysis.getScope(analyzeStatement), planNode.getOutputVariables());
    }

    private RelationPlan createTableCreationPlan(Analysis analysis, Query query)
    {
        QualifiedObjectName destination = analysis.getCreateTableDestination().get();

        RelationPlan plan = createRelationPlan(analysis, query);

        ConnectorTableMetadata tableMetadata = createTableMetadata(
                destination,
                getOutputTableColumns(plan, analysis.getColumnAliases()),
                analysis.getCreateTableProperties(),
                analysis.getParameters(),
                analysis.getCreateTableComment());
        Optional<NewTableLayout> newTableLayout = metadata.getNewTableLayout(session, destination.getCatalogName(), tableMetadata);
        Optional<NewTableLayout> preferredShuffleLayout = metadata.getPreferredShuffleLayoutForNewTable(session, destination.getCatalogName(), tableMetadata);

        List<String> columnNames = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, destination.getCatalogName(), tableMetadata);

        return createTableWriterPlan(
                analysis,
                plan,
                new CreateName(new ConnectorId(destination.getCatalogName()), tableMetadata, newTableLayout),
                columnNames,
                tableMetadata.getColumns(),
                newTableLayout,
                preferredShuffleLayout,
                statisticsMetadata);
    }

    private RelationPlan createRefreshMaterializedViewPlan(Analysis analysis, RefreshMaterializedView refreshMaterializedViewStatement)
    {
        Analysis.RefreshMaterializedViewAnalysis viewAnalysis = analysis.getRefreshMaterializedViewAnalysis().get();

        TableHandle tableHandle = viewAnalysis.getTarget();
        List<ColumnHandle> columnHandles = viewAnalysis.getColumns();
        WriterTarget target = new RefreshMaterializedViewReference(tableHandle, metadata.getTableMetadata(session, tableHandle).getTable());

        return buildInternalInsertPlan(tableHandle, columnHandles, viewAnalysis.getQuery(), analysis, target);
    }

    private RelationPlan createInsertPlan(Analysis analysis, Insert insertStatement)
    {
        Analysis.Insert insertAnalysis = analysis.getInsert().get();

        TableHandle tableHandle = insertAnalysis.getTarget();
        List<ColumnHandle> columnHandles = insertAnalysis.getColumns();
        WriterTarget target = new InsertReference(tableHandle, metadata.getTableMetadata(session, tableHandle).getTable());

        return buildInternalInsertPlan(tableHandle, columnHandles, insertStatement.getQuery(), analysis, target);
    }

    private RelationPlan buildInternalInsertPlan(
            TableHandle tableHandle,
            List<ColumnHandle> columnHandles,
            Query query,
            Analysis analysis,
            WriterTarget target)
    {
        TableMetadata tableMetadata = metadata.getTableMetadata(session, tableHandle);

        List<ColumnMetadata> visibleTableColumns = tableMetadata.getColumns().stream()
                .filter(column -> !column.isHidden())
                .collect(toImmutableList());
        List<String> visibleTableColumnNames = visibleTableColumns.stream()
                .map(ColumnMetadata::getName)
                .collect(toImmutableList());

        RelationPlan plan = createRelationPlan(analysis, query);

        Map<String, ColumnHandle> columns = metadata.getColumnHandles(session, tableHandle);
        Assignments.Builder assignments = Assignments.builder();
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            if (column.isHidden()) {
                continue;
            }
            VariableReferenceExpression output = variableAllocator.newVariable(getSourceLocation(query), column.getName(), column.getType());
            int index = columnHandles.indexOf(columns.get(column.getName()));
            if (index < 0) {
                Expression cast = new Cast(new NullLiteral(), column.getType().getTypeSignature().toString());
                assignments.put(output, castToRowExpression(cast));
            }
            else {
                VariableReferenceExpression input = plan.getVariable(index);
                Type tableType = column.getType();
                Type queryType = input.getType();

                if (queryType.equals(tableType) || metadata.getFunctionAndTypeManager().isTypeOnlyCoercion(queryType, tableType)) {
                    assignments.put(output, castToRowExpression(createSymbolReference(input)));
                }
                else {
                    Expression cast = new Cast(createSymbolReference(input), tableType.getTypeSignature().toString());
                    assignments.put(output, castToRowExpression(cast));
                }
            }
        }
        ProjectNode projectNode = new ProjectNode(idAllocator.getNextId(), plan.getRoot(), assignments.build());

        List<Field> fields = visibleTableColumns.stream()
                .map(column -> Field.newUnqualified(query.getLocation(), column.getName(), column.getType()))
                .collect(toImmutableList());
        Scope scope = Scope.builder().withRelationType(RelationId.anonymous(), new RelationType(fields)).build();

        plan = new RelationPlan(projectNode, scope, projectNode.getOutputVariables());

        Optional<NewTableLayout> newTableLayout = metadata.getInsertLayout(session, tableHandle);
        Optional<NewTableLayout> preferredShuffleLayout = metadata.getPreferredShuffleLayoutForInsert(session, tableHandle);

        String catalogName = tableHandle.getConnectorId().getCatalogName();
        TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, catalogName, tableMetadata.getMetadata());

        return createTableWriterPlan(
                analysis,
                plan,
                target,
                visibleTableColumnNames,
                visibleTableColumns,
                newTableLayout,
                preferredShuffleLayout,
                statisticsMetadata);
    }

    private RelationPlan createTableWriterPlan(
            Analysis analysis,
            RelationPlan plan,
            WriterTarget target,
            List<String> columnNames,
            List<ColumnMetadata> columnMetadataList,
            Optional<NewTableLayout> writeTableLayout,
            Optional<NewTableLayout> preferredShuffleLayout,
            TableStatisticsMetadata statisticsMetadata)
    {
        verify(!(writeTableLayout.isPresent() && preferredShuffleLayout.isPresent()), "writeTableLayout and preferredShuffleLayout cannot both exist");

        PlanNode source = plan.getRoot();

        if (!analysis.isCreateTableAsSelectWithData()) {
            source = new LimitNode(source.getSourceLocation(), idAllocator.getNextId(), source, 0L, FINAL);
        }

        List<VariableReferenceExpression> variables = plan.getFieldMappings();
        Optional<PartitioningScheme> tablePartitioningScheme = getPartitioningSchemeForTableWrite(writeTableLayout, columnNames, variables);
        Optional<PartitioningScheme> preferredShufflePartitioningScheme = getPartitioningSchemeForTableWrite(preferredShuffleLayout, columnNames, variables);

        verify(columnNames.size() == variables.size(), "columnNames.size() != variables.size(): %s and %s", columnNames, variables);
        Map<String, VariableReferenceExpression> columnToVariableMap = zip(columnNames.stream(), plan.getFieldMappings().stream(), SimpleImmutableEntry::new)
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));

        Set<VariableReferenceExpression> notNullColumnVariables = columnMetadataList.stream()
                .filter(column -> !column.isNullable())
                .map(ColumnMetadata::getName)
                .map(columnToVariableMap::get)
                .collect(toImmutableSet());

        if (!statisticsMetadata.isEmpty()) {
            TableStatisticAggregation result = statisticsAggregationPlanner.createStatisticsAggregation(statisticsMetadata, columnToVariableMap, true);

            StatisticAggregations.Parts aggregations = result.getAggregations().splitIntoPartialAndFinal(variableAllocator, metadata.getFunctionAndTypeManager());

            TableFinishNode commitNode = new TableFinishNode(
                    source.getSourceLocation(),
                    idAllocator.getNextId(),
                    new TableWriterNode(
                            source.getSourceLocation(),
                            idAllocator.getNextId(),
                            source,
                            Optional.of(target),
                            variableAllocator.newVariable("rows", BIGINT),
                            variableAllocator.newVariable("fragments", VARBINARY),
                            variableAllocator.newVariable("commitcontext", VARBINARY),
                            plan.getFieldMappings(),
                            columnNames,
                            notNullColumnVariables,
                            tablePartitioningScheme,
                            preferredShufflePartitioningScheme,
                            // partial aggregation is run within the TableWriteOperator to calculate the statistics for
                            // the data consumed by the TableWriteOperator
                            Optional.of(aggregations.getPartialAggregation())),
                    Optional.of(target),
                    variableAllocator.newVariable("rows", BIGINT),
                    // final aggregation is run within the TableFinishOperator to summarize collected statistics
                    // by the partial aggregation from all of the writer nodes
                    Optional.of(aggregations.getFinalAggregation()),
                    Optional.of(result.getDescriptor()));

            return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputVariables());
        }

        TableFinishNode commitNode = new TableFinishNode(
                source.getSourceLocation(),
                idAllocator.getNextId(),
                new TableWriterNode(
                        source.getSourceLocation(),
                        idAllocator.getNextId(),
                        source,
                        Optional.of(target),
                        variableAllocator.newVariable("rows", BIGINT),
                        variableAllocator.newVariable("fragments", VARBINARY),
                        variableAllocator.newVariable("commitcontext", VARBINARY),
                        plan.getFieldMappings(),
                        columnNames,
                        notNullColumnVariables,
                        tablePartitioningScheme,
                        preferredShufflePartitioningScheme,
                        Optional.empty()),
                Optional.of(target),
                variableAllocator.newVariable("rows", BIGINT),
                Optional.empty(),
                Optional.empty());
        return new RelationPlan(commitNode, analysis.getRootScope(), commitNode.getOutputVariables());
    }

    private RelationPlan createDeletePlan(Analysis analysis, Delete node)
    {
        DeleteNode deleteNode = new QueryPlanner(analysis, variableAllocator, idAllocator, buildLambdaDeclarationToVariableMap(analysis, variableAllocator), metadata, session)
                .plan(node);

        TableHandle handle = analysis.getTableHandle(node.getTable());
        DeleteHandle deleteHandle = new DeleteHandle(handle, metadata.getTableMetadata(session, handle).getTable());
        TableFinishNode commitNode = new TableFinishNode(
                deleteNode.getSourceLocation(),
                idAllocator.getNextId(),
                deleteNode,
                Optional.of(deleteHandle),
                variableAllocator.newVariable("rows", BIGINT),
                Optional.empty(),
                Optional.empty());

        return new RelationPlan(commitNode, analysis.getScope(node), commitNode.getOutputVariables());
    }

    private PlanNode createOutputPlan(RelationPlan plan, Analysis analysis)
    {
        ImmutableList.Builder<VariableReferenceExpression> outputs = ImmutableList.builder();
        ImmutableList.Builder<String> names = ImmutableList.builder();

        int columnNumber = 0;
        RelationType outputDescriptor = analysis.getOutputDescriptor();
        for (Field field : outputDescriptor.getVisibleFields()) {
            String name = field.getName().orElse("_col" + columnNumber);
            names.add(name);

            int fieldIndex = outputDescriptor.indexOf(field);
            VariableReferenceExpression variable = plan.getVariable(fieldIndex);
            outputs.add(variable);

            columnNumber++;
        }

        return new OutputNode(plan.getRoot().getSourceLocation(), idAllocator.getNextId(), plan.getRoot(), names.build(), outputs.build());
    }

    private RelationPlan createRelationPlan(Analysis analysis, Query query)
    {
        return new RelationPlanner(analysis, variableAllocator, idAllocator, buildLambdaDeclarationToVariableMap(analysis, variableAllocator), metadata, session)
                .process(query, null);
    }

    private ConnectorTableMetadata createTableMetadata(QualifiedObjectName table, List<ColumnMetadata> columns, Map<String, Expression> propertyExpressions, Map<NodeRef<Parameter>, Expression> parameters, Optional<String> comment)
    {
        ConnectorId connectorId = metadata.getCatalogHandle(session, table.getCatalogName())
                .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog does not exist: " + table.getCatalogName()));

        Map<String, Object> properties = metadata.getTablePropertyManager().getProperties(
                connectorId,
                table.getCatalogName(),
                propertyExpressions,
                session,
                metadata,
                parameters);

        return new ConnectorTableMetadata(toSchemaTableName(table), columns, properties, comment);
    }

    private static List<ColumnMetadata> getOutputTableColumns(RelationPlan plan, Optional<List<Identifier>> columnAliases)
    {
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        int aliasPosition = 0;
        for (Field field : plan.getDescriptor().getVisibleFields()) {
            String columnName = columnAliases.isPresent() ? columnAliases.get().get(aliasPosition).getValue() : field.getName().get();
            columns.add(new ColumnMetadata(columnName, field.getType()));
            aliasPosition++;
        }
        return columns.build();
    }

    private static Map<NodeRef<LambdaArgumentDeclaration>, VariableReferenceExpression> buildLambdaDeclarationToVariableMap(Analysis analysis, PlanVariableAllocator variableAllocator)
    {
        Map<NodeRef<LambdaArgumentDeclaration>, VariableReferenceExpression> resultMap = new LinkedHashMap<>();
        for (Entry<NodeRef<Expression>, Type> entry : analysis.getTypes().entrySet()) {
            if (!(entry.getKey().getNode() instanceof LambdaArgumentDeclaration)) {
                continue;
            }
            NodeRef<LambdaArgumentDeclaration> lambdaArgumentDeclaration = NodeRef.of((LambdaArgumentDeclaration) entry.getKey().getNode());
            if (resultMap.containsKey(lambdaArgumentDeclaration)) {
                continue;
            }
            resultMap.put(lambdaArgumentDeclaration, variableAllocator.newVariable(lambdaArgumentDeclaration.getNode(), entry.getValue()));
        }
        return resultMap;
    }

    private static Optional<PartitioningScheme> getPartitioningSchemeForTableWrite(Optional<NewTableLayout> tableLayout, List<String> columnNames, List<VariableReferenceExpression> variables)
    {
        // todo this should be checked in analysis
        tableLayout.ifPresent(layout -> {
            if (!ImmutableSet.copyOf(columnNames).containsAll(layout.getPartitionColumns())) {
                throw new PrestoException(NOT_SUPPORTED, "INSERT must write all distribution columns: " + layout.getPartitionColumns());
            }
        });

        Optional<PartitioningScheme> partitioningScheme = Optional.empty();
        if (tableLayout.isPresent()) {
            List<VariableReferenceExpression> partitionFunctionArguments = new ArrayList<>();
            tableLayout.get().getPartitionColumns().stream()
                    .mapToInt(columnNames::indexOf)
                    .mapToObj(variables::get)
                    .forEach(partitionFunctionArguments::add);

            List<VariableReferenceExpression> outputLayout = new ArrayList<>(variables);

            partitioningScheme = Optional.of(new PartitioningScheme(
                    Partitioning.create(tableLayout.get().getPartitioning(), partitionFunctionArguments),
                    outputLayout));
        }
        return partitioningScheme;
    }
}
