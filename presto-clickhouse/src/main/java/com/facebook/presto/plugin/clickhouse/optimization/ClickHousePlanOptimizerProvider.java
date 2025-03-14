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
package com.facebook.presto.plugin.clickhouse.optimization;

import com.facebook.presto.plugin.clickhouse.ClickHouseClient;
import com.facebook.presto.plugin.clickhouse.optimization.function.OperatorTranslators;
import com.facebook.presto.spi.ConnectorPlanOptimizer;
import com.facebook.presto.spi.connector.ConnectorPlanOptimizerProvider;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.relation.DeterminismEvaluator;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;

import java.util.Set;

import static java.util.Objects.requireNonNull;

public class ClickHousePlanOptimizerProvider
        implements ConnectorPlanOptimizerProvider
{
    private final FunctionMetadataManager functionManager;
    private final StandardFunctionResolution functionResolution;
    private final RowExpressionService rowExpressionService;
    private final DeterminismEvaluator determinismEvaluator;
    private final String identifierQuote;
    private final ClickHouseQueryGenerator clickhouseQueryGenerator;

    @Inject
    public ClickHousePlanOptimizerProvider(
            ClickHouseClient clickHouseClient,
            FunctionMetadataManager functionManager,
            StandardFunctionResolution functionResolution,
            RowExpressionService rowExpressionService,
            ClickHouseQueryGenerator clickhouseQueryGenerator)
    {
        this.functionManager = requireNonNull(functionManager, "functionManager is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.identifierQuote = clickHouseClient.getIdentifierQuote();
        this.clickhouseQueryGenerator = clickhouseQueryGenerator;
        this.determinismEvaluator = rowExpressionService.getDeterminismEvaluator();
    }

    @Override
    public Set<ConnectorPlanOptimizer> getLogicalPlanOptimizers()
    {
        return ImmutableSet.of();
    }

    @Override
    public Set<ConnectorPlanOptimizer> getPhysicalPlanOptimizers()
    {
        return ImmutableSet.of(new ClickHouseComputePushdown(
                functionManager,
                functionResolution,
                determinismEvaluator,
                rowExpressionService,
                identifierQuote,
                getFunctionTranslators(),
                clickhouseQueryGenerator));
    }

    private Set<Class<?>> getFunctionTranslators()
    {
        return ImmutableSet.of(OperatorTranslators.class);
    }
}
