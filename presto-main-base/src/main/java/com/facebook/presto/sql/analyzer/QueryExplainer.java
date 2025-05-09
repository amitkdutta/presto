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

import com.facebook.presto.Session;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.execution.DataDefinitionTask;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.Optimizer;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.LogicalPlanner;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanFragmenter;
import com.facebook.presto.sql.planner.PlanOptimizers;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.planner.planPrinter.IOPlanPrinter;
import com.facebook.presto.sql.planner.planPrinter.PlanPrinter;
import com.facebook.presto.sql.planner.sanity.PlanChecker;
import com.facebook.presto.sql.tree.ExplainType.Type;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableMap;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.SystemSessionProperties.isVerboseOptimizerInfoEnabled;
import static com.facebook.presto.common.RuntimeMetricName.ANALYZE_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.FRAGMENT_PLAN_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.LOGICAL_PLANNER_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.OPTIMIZER_TIME_NANOS;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.Optimizer.PlanStage.OPTIMIZED_AND_VALIDATED;
import static com.facebook.presto.sql.analyzer.utils.ParameterUtils.parameterExtractor;
import static com.facebook.presto.sql.planner.planPrinter.IOPlanPrinter.textIOPlan;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.graphvizDistributedPlan;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.graphvizLogicalPlan;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.jsonDistributedPlan;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.jsonLogicalPlan;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class QueryExplainer
{
    private final List<PlanOptimizer> planOptimizers;
    private final PlanFragmenter planFragmenter;
    private final Metadata metadata;
    private final AccessControl accessControl;
    private final SqlParser sqlParser;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask;
    private final PlanChecker planChecker;

    @Inject
    public QueryExplainer(
            PlanOptimizers planOptimizers,
            PlanFragmenter planFragmenter,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask,
            PlanChecker planChecker)
    {
        this(
                planOptimizers.getPlanningTimeOptimizers(),
                planFragmenter,
                metadata,
                accessControl,
                sqlParser,
                statsCalculator,
                costCalculator,
                dataDefinitionTask,
                planChecker);
    }

    public QueryExplainer(
            List<PlanOptimizer> planOptimizers,
            PlanFragmenter planFragmenter,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            Map<Class<? extends Statement>, DataDefinitionTask<?>> dataDefinitionTask,
            PlanChecker planChecker)
    {
        this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
        this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.dataDefinitionTask = ImmutableMap.copyOf(requireNonNull(dataDefinitionTask, "dataDefinitionTask is null"));
        this.planChecker = requireNonNull(planChecker, "planChecker is null");
    }

    public Analysis analyze(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        Analyzer analyzer = new Analyzer(session, metadata, sqlParser, accessControl, Optional.of(this), parameters, parameterExtractor(statement, parameters), warningCollector, query);
        return analyzer.analyze(statement);
    }

    public String getPlan(Session session, Statement statement, Type planType, List<Expression> parameters, boolean verbose, WarningCollector warningCollector, String query)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            return explainTask(statement, task, parameters);
        }

        switch (planType) {
            case LOGICAL:
                Plan plan = getLogicalPlan(session, statement, parameters, warningCollector, query);
                return PlanPrinter.textLogicalPlan(plan.getRoot(), plan.getTypes(), plan.getStatsAndCosts(), metadata.getFunctionAndTypeManager(), session, 0, verbose, isVerboseOptimizerInfoEnabled(session));
            case DISTRIBUTED:
                SubPlan subPlan = getDistributedPlan(session, statement, parameters, warningCollector, query);
                return PlanPrinter.textDistributedPlan(subPlan, metadata.getFunctionAndTypeManager(), session, verbose);
            case IO:
                return IOPlanPrinter.textIOPlan(getLogicalPlan(session, statement, parameters, warningCollector, query).getRoot(), metadata, session);
        }
        throw new IllegalArgumentException("Unhandled plan type: " + planType);
    }

    private static <T extends Statement> String explainTask(Statement statement, DataDefinitionTask<T> task, List<Expression> parameters)
    {
        return task.explain((T) statement, parameters);
    }

    public String getGraphvizPlan(Session session, Statement statement, Type planType, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            // todo format as graphviz
            return explainTask(statement, task, parameters);
        }

        switch (planType) {
            case LOGICAL:
                Plan plan = getLogicalPlan(session, statement, parameters, warningCollector, query);
                return graphvizLogicalPlan(plan.getRoot(), plan.getTypes(), plan.getStatsAndCosts(), metadata.getFunctionAndTypeManager(), session);
            case DISTRIBUTED:
                SubPlan subPlan = getDistributedPlan(session, statement, parameters, warningCollector, query);
                return graphvizDistributedPlan(subPlan, metadata.getFunctionAndTypeManager(), session);
        }
        throw new IllegalArgumentException("Unhandled plan type: " + planType);
    }

    public String getJsonPlan(Session session, Statement statement, Type planType, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        DataDefinitionTask<?> task = dataDefinitionTask.get(statement.getClass());
        if (task != null) {
            // todo format as json
            return explainTask(statement, task, parameters);
        }

        Plan plan;
        switch (planType) {
            case IO:
                plan = getLogicalPlan(session, statement, parameters, warningCollector, query);
                return textIOPlan(plan.getRoot(), metadata, session);
            case LOGICAL:
                plan = getLogicalPlan(session, statement, parameters, warningCollector, query);
                return jsonLogicalPlan(plan.getRoot(), plan.getTypes(), metadata.getFunctionAndTypeManager(), plan.getStatsAndCosts(), session);
            case DISTRIBUTED:
                SubPlan subPlan = getDistributedPlan(session, statement, parameters, warningCollector, query);
                return jsonDistributedPlan(subPlan, metadata.getFunctionAndTypeManager(), session);
            default:
                throw new PrestoException(NOT_SUPPORTED, format("Unsupported explain plan type %s for JSON format", planType));
        }
    }

    public Plan getLogicalPlan(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        return getLogicalPlan(session, statement, parameters, warningCollector, new PlanNodeIdAllocator(), query);
    }

    public Plan getLogicalPlan(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector, PlanNodeIdAllocator idAllocator, String query)
    {
        // analyze statement
        Analysis analysis = session.getRuntimeStats()
                .recordWallAndCpuTime(ANALYZE_TIME_NANOS, () -> analyze(session, statement, parameters, warningCollector, query));

        final VariableAllocator planVariableAllocator = new VariableAllocator();
        LogicalPlanner logicalPlanner = new LogicalPlanner(
                session,
                idAllocator,
                metadata,
                planVariableAllocator,
                sqlParser);

        PlanNode planNode = session.getRuntimeStats().recordWallAndCpuTime(
                LOGICAL_PLANNER_TIME_NANOS,
                () -> logicalPlanner.plan(analysis));

        Optimizer optimizer = new Optimizer(
                session,
                metadata,
                planOptimizers,
                planChecker,
                planVariableAllocator,
                idAllocator,
                warningCollector,
                statsCalculator,
                costCalculator,
                true);

        return session.getRuntimeStats().recordWallAndCpuTime(
                OPTIMIZER_TIME_NANOS,
                () -> optimizer.validateAndOptimizePlan(planNode, OPTIMIZED_AND_VALIDATED));
    }

    public SubPlan getDistributedPlan(Session session, Statement statement, List<Expression> parameters, WarningCollector warningCollector, String query)
    {
        PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
        Plan plan = getLogicalPlan(session, statement, parameters, warningCollector, idAllocator, query);
        return session.getRuntimeStats()
                .recordWallAndCpuTime(FRAGMENT_PLAN_TIME_NANOS, () -> planFragmenter.createSubPlans(session, plan, false, idAllocator, warningCollector));
    }
}
