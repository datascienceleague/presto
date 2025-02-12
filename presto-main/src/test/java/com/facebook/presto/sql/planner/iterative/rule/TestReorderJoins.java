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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.cost.CostComparator;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.VariableStatsEstimate;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.analyzer.FeaturesConfig.JoinDistributionType;
import com.facebook.presto.sql.analyzer.FeaturesConfig.JoinReorderingStrategy;
import com.facebook.presto.sql.planner.assertions.BasePlanTest;
import com.facebook.presto.sql.planner.assertions.PlanMatchPattern;
import com.facebook.presto.sql.planner.iterative.rule.test.RuleAssert;
import com.facebook.presto.sql.planner.iterative.rule.test.RuleTester;
import com.facebook.presto.sql.planner.plan.JoinNode.EquiJoinClause;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.facebook.airlift.testing.Closeables.closeAllRuntimeException;
import static com.facebook.presto.SystemSessionProperties.HANDLE_COMPLEX_EQUI_JOINS;
import static com.facebook.presto.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static com.facebook.presto.SystemSessionProperties.JOIN_MAX_BROADCAST_TABLE_SIZE;
import static com.facebook.presto.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static com.facebook.presto.common.function.OperatorType.EQUAL;
import static com.facebook.presto.common.function.OperatorType.LESS_THAN;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.metadata.FunctionAndTypeManager.qualifyObjectName;
import static com.facebook.presto.sql.analyzer.FeaturesConfig.JoinDistributionType.AUTOMATIC;
import static com.facebook.presto.sql.analyzer.FeaturesConfig.JoinDistributionType.BROADCAST;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.anyTree;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.equiJoinClause;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.expression;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.filter;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.join;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.project;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.tableScan;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.values;
import static com.facebook.presto.sql.planner.plan.JoinNode.DistributionType.PARTITIONED;
import static com.facebook.presto.sql.planner.plan.JoinNode.DistributionType.REPLICATED;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.INNER;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.sql.relational.Expressions.variable;

public class TestReorderJoins
        extends BasePlanTest
{
    private RuleTester tester;
    private FunctionResolution functionResolution;

    // TWO_ROWS are used to prevent node from being scalar
    private static final ImmutableList<List<RowExpression>> TWO_ROWS = ImmutableList.of(ImmutableList.of(), ImmutableList.of());
    private static final QualifiedName RANDOM = QualifiedName.of("random");

    @DataProvider
    public static Object[][] tableSpecificationPermutations()
    {
        return new Object[][] {
                {"supplier s, partsupp ps, customer c, orders o"},
                {"supplier s, partsupp ps, orders o, customer c"},
                {"supplier s, customer c, partsupp ps, orders o"},
                {"supplier s, customer c, orders o, partsupp ps"},
                {"supplier s, orders o, partsupp ps, customer c"},
                {"supplier s, orders o, customer c, partsupp ps"},
                {"partsupp ps, supplier s, customer c, orders o"},
                {"partsupp ps, supplier s, orders o, customer c"},
                {"partsupp ps, customer c, supplier s, orders o"},
                {"partsupp ps, customer c, orders o, supplier s"},
                {"partsupp ps, orders o, supplier s, customer c"},
                {"partsupp ps, orders o, customer c, supplier s"},
                {"customer c, supplier s, partsupp ps, orders o"},
                {"customer c, supplier s, orders o, partsupp ps"},
                {"customer c, partsupp ps, supplier s, orders o"},
                {"customer c, partsupp ps, orders o, supplier s"},
                {"customer c, orders o, supplier s, partsupp ps"},
                {"customer c, orders o, partsupp ps, supplier s"},
                {"orders o, supplier s, partsupp ps, customer c"},
                {"orders o, supplier s, customer c, partsupp ps"},
                {"orders o, partsupp ps, supplier s, customer c"},
                {"orders o, partsupp ps, customer c, supplier s"},
                {"orders o, customer c, supplier s, partsupp ps"},
                {"orders o, customer c, partsupp ps, supplier s"}
        };
    }

    @BeforeClass
    public void setUp()
    {
        tester = new RuleTester(
                ImmutableList.of(),
                ImmutableMap.of(
                        JOIN_DISTRIBUTION_TYPE, JoinDistributionType.AUTOMATIC.name(),
                        JOIN_REORDERING_STRATEGY, JoinReorderingStrategy.AUTOMATIC.name(),
                        HANDLE_COMPLEX_EQUI_JOINS, "true"),
                Optional.of(4));
        this.functionResolution = new FunctionResolution(tester.getMetadata().getFunctionAndTypeManager().getFunctionAndTypeResolver());
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        closeAllRuntimeException(tester);
        tester = null;
    }

    @Test
    public void testKeepsOutputSymbols()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1"), p.variable("A2")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A2")),
                                Optional.empty()))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5000)
                        .addVariableStatistics(ImmutableMap.of(
                                variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 100),
                                variable("A2", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1")),
                        Optional.empty(),
                        Optional.of(PARTITIONED),
                        values(ImmutableMap.of("A1", 0, "A2", 1)),
                        values(ImmutableMap.of("B1", 0)))
                        .withExactOutputs("A2"));
    }

    @Test
    public void testReplicatesAndFlipsWhenOneTableMuchSmaller()
    {
        assertReorderJoins()
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "1PB")
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 6400, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("B1", "A1")),
                        Optional.empty(),
                        Optional.of(REPLICATED),
                        values(ImmutableMap.of("B1", 0)),
                        values(ImmutableMap.of("A1", 0))));
    }

    @Test
    public void testRepartitionsWhenRequiredBySession()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 6400, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("B1", "A1")),
                        Optional.empty(),
                        Optional.of(PARTITIONED),
                        values(ImmutableMap.of("B1", 0)),
                        values(ImmutableMap.of("A1", 0))));
    }

    @Test
    public void testRepartitionsWhenBothTablesEqual()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1")),
                        Optional.empty(),
                        Optional.of(PARTITIONED),
                        values(ImmutableMap.of("A1", 0)),
                        values(ImmutableMap.of("B1", 0))));
    }

    @Test
    public void testReplicatesUnrestrictedWhenRequiredBySession()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "1kB")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1")),
                        Optional.empty(),
                        Optional.of(REPLICATED),
                        values(ImmutableMap.of("A1", 0)),
                        values(ImmutableMap.of("B1", 0))));
    }

    @Test
    public void testReplicatedScalarJoinEvenWhereSessionRequiresRepartitioned()
    {
        PlanMatchPattern expectedPlan = join(
                INNER,
                ImmutableList.of(equiJoinClause("A1", "B1")),
                Optional.empty(),
                Optional.of(REPLICATED),
                values(ImmutableMap.of("A1", 0)),
                values(ImmutableMap.of("B1", 0)));

        PlanNodeStatsEstimate valuesA = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(10000)
                .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                .build();
        PlanNodeStatsEstimate valuesB = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(10000)
                .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                .build();

        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), p.variable("A1")), // matches isAtMostScalar
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", valuesA)
                .overrideStats("valuesB", valuesB)
                .matches(expectedPlan);

        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesA"), p.variable("A1")), // matches isAtMostScalar
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("B1"), p.variable("A1")),
                                Optional.empty()))
                .overrideStats("valuesA", valuesA)
                .overrideStats("valuesB", valuesB)
                .matches(expectedPlan);
    }

    @Test
    public void testDoesNotFireForCrossJoin()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1")), TWO_ROWS),
                                ImmutableList.of(),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithNoStats()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                p.values(new PlanNodeId("valuesB"), p.variable("B1")),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1")),
                                Optional.empty()))
                .overrideStats("valuesA", PlanNodeStatsEstimate.unknown())
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireForNonDeterministicFilter()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), p.variable("A1")),
                                p.values(new PlanNodeId("valuesB"), p.variable("B1")),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.of(comparisonRowExpression(LESS_THAN, variable("A1", BIGINT), call(
                                        RANDOM.toString(),
                                        tester.getMetadata().getFunctionAndTypeManager().resolveFunction(
                                                Optional.empty(),
                                                Optional.empty(),
                                                qualifyObjectName(RANDOM),
                                                ImmutableList.of()),
                                        BIGINT,
                                        ImmutableList.of())))))
                .doesNotFire();
    }

    @Test
    public void testPredicatesPushedDown()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.join(
                                        INNER,
                                        p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                        p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1"), p.variable("B2")), TWO_ROWS),
                                        ImmutableList.of(),
                                        ImmutableList.of(p.variable("A1"), p.variable("B1"), p.variable("B2")),
                                        Optional.empty()),
                                p.values(new PlanNodeId("valuesC"), ImmutableList.of(p.variable("C1")), TWO_ROWS),
                                ImmutableList.of(
                                        new EquiJoinClause(p.variable("B2"), p.variable("C1"))),
                                ImmutableList.of(p.variable("A1")),
                                Optional.of(comparisonRowExpression(EQUAL, variable("A1", BIGINT), variable("B1", BIGINT)))))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5)
                        .addVariableStatistics(ImmutableMap.of(
                                variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 5),
                                variable("B2", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 5)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(1000)
                        .addVariableStatistics(ImmutableMap.of(variable("C1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .matches(
                        join(
                                INNER,
                                ImmutableList.of(equiJoinClause("C1", "B2")),
                                values("C1"),
                                join(
                                        INNER,
                                        ImmutableList.of(equiJoinClause("A1", "B1")),
                                        values("A1"),
                                        values("B1", "B2"))));
    }

    @Test
    public void testSmallerJoinFirst()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.join(
                                        INNER,
                                        p.values(new PlanNodeId("valuesA"), ImmutableList.of(p.variable("A1")), TWO_ROWS),
                                        p.values(new PlanNodeId("valuesB"), ImmutableList.of(p.variable("B1"), p.variable("B2")), TWO_ROWS),
                                        ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                        ImmutableList.of(p.variable("A1"), p.variable("B1"), p.variable("B2")),
                                        Optional.empty()),
                                p.values(new PlanNodeId("valuesC"), ImmutableList.of(p.variable("C1")), TWO_ROWS),
                                ImmutableList.of(
                                        new EquiJoinClause(p.variable("B2"), p.variable("C1"))),
                                ImmutableList.of(p.variable("A1")),
                                Optional.of(comparisonRowExpression(EQUAL, variable("A1", BIGINT), variable("B1", BIGINT)))))
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(40)
                        .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addVariableStatistics(ImmutableMap.of(
                                variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 10),
                                variable("B2", BIGINT), new VariableStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addVariableStatistics(ImmutableMap.of(variable("C1", BIGINT), new VariableStatsEstimate(99, 199, 0, 100, 100)))
                        .build())
                .matches(
                        join(
                                INNER,
                                ImmutableList.of(equiJoinClause("A1", "B1")),
                                values("A1"),
                                join(
                                        INNER,
                                        ImmutableList.of(equiJoinClause("C1", "B2")),
                                        values("C1"),
                                        values("B1", "B2"))));
    }

    @Test
    public void testReplicatesWhenNotRestricted()
    {
        int aRows = 10_000;
        int bRows = 10;

        PlanNodeStatsEstimate probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 10)))
                .build();
        PlanNodeStatsEstimate buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000, 10)))
                .build();

        // B table is small enough to be replicated in AUTOMATIC_RESTRICTED mode
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "100MB")
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), aRows, p.variable("A1")),
                                p.values(new PlanNodeId("valuesB"), bRows, p.variable("B1")),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1")),
                        Optional.empty(),
                        Optional.of(REPLICATED),
                        values(ImmutableMap.of("A1", 0)),
                        values(ImmutableMap.of("B1", 0))));

        probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addVariableStatistics(ImmutableMap.of(variable("A1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000d * 10000, 10)))
                .build();
        buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addVariableStatistics(ImmutableMap.of(variable("B1", BIGINT), new VariableStatsEstimate(0, 100, 0, 640000d * 10000, 10)))
                .build();

        // B table exceeds AUTOMATIC_RESTRICTED limit therefore it is partitioned
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "100MB")
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), aRows, p.variable("A1")),
                                p.values(new PlanNodeId("valuesB"), bRows, p.variable("B1")),
                                ImmutableList.of(new EquiJoinClause(p.variable("A1"), p.variable("B1"))),
                                ImmutableList.of(p.variable("A1"), p.variable("B1")),
                                Optional.empty()))
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1")),
                        Optional.empty(),
                        Optional.of(PARTITIONED),
                        values(ImmutableMap.of("A1", 0)),
                        values(ImmutableMap.of("B1", 0))));
    }

    @Test
    public void testReorderAndReplicate()
    {
        int aRows = 10;
        int bRows = 10_000;

        PlanNodeStatsEstimate probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addVariableStatistics(ImmutableMap.of(variable("A1", VARCHAR), new VariableStatsEstimate(0, 100, 0, 640000, 10)))
                .build();
        PlanNodeStatsEstimate buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addVariableStatistics(ImmutableMap.of(variable("B1", VARCHAR), new VariableStatsEstimate(0, 100, 0, 640000, 10)))
                .build();

        // A table is small enough to be replicated in AUTOMATIC_RESTRICTED mode
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "10MB")
                .on(p -> {
                    VariableReferenceExpression a1 = p.variable("A1", VARCHAR);
                    VariableReferenceExpression b1 = p.variable("B1", VARCHAR);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), aRows, a1),
                            p.values(new PlanNodeId("valuesB"), bRows, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1, b1),
                            Optional.empty());
                })
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .matches(join(
                        INNER,
                        ImmutableList.of(equiJoinClause("B1", "A1")),
                        Optional.empty(),
                        Optional.of(REPLICATED),
                        values(ImmutableMap.of("B1", 0)),
                        values(ImmutableMap.of("A1", 0))));
    }

    /**
     * This test asserts that join re-ordering works as expected for complex equi join clauses ('s.acctbal = c.acctbal + o.totalprice')
     * and works irrespective of the order in which tables are specified in the FROM clause
     *
     * @param tableSpecificationOrder The table specification order
     */
    @Test(dataProvider = "tableSpecificationPermutations")
    public void testComplexEquiJoinCriteria(String tableSpecificationOrder)
    {
        // For a full connected join graph, we don't see any CrossJoins
        String query = "select 1 from " + tableSpecificationOrder + " where s.suppkey = ps.suppkey and c.custkey = o.custkey and s.acctbal = c.acctbal + o.totalprice";
        PlanMatchPattern expectedPlan =
                anyTree(
                        join(INNER,
                                ImmutableList.of(equiJoinClause("PS_SUPPKEY", "S_SUPPKEY")),
                                anyTree(tableScan("partsupp", ImmutableMap.of("PS_SUPPKEY", "suppkey"))),
                                anyTree(
                                        join(INNER,
                                                ImmutableList.of(equiJoinClause("SUM", "S_ACCTBAL")),
                                                anyTree(
                                                        project(ImmutableMap.of("SUM", expression("C_ACCTBAL + O_TOTALPRICE")),
                                                                join(INNER,
                                                                        ImmutableList.of(equiJoinClause("O_CUSTKEY", "C_CUSTKEY")),
                                                                        anyTree(
                                                                                tableScan("orders", ImmutableMap.of("O_CUSTKEY", "custkey", "O_TOTALPRICE", "totalprice"))),
                                                                        anyTree(
                                                                                tableScan("customer", ImmutableMap.of("C_CUSTKEY", "custkey", "C_ACCTBAL", "acctbal")))))),
                                                anyTree(
                                                        tableScan("supplier", ImmutableMap.of("S_ACCTBAL", "acctbal", "S_SUPPKEY", "suppkey")))))));
        assertPlan(query, expectedPlan);

        // The plan is identical to the plan for the fully spelled out version of the Join
        String fullQuery = "select 1 from (supplier s inner join partsupp ps on s.suppkey = ps.suppkey) inner join (orders o inner join customer c on c.custkey = o.custkey) " +
                " on  s.acctbal = c.acctbal + o.totalprice";
        assertPlan(fullQuery, expectedPlan);
    }

    @Test
    public void testComplexEquiJoinCriteriaForDisjointGraphs()
    {
        // If the join clause is written with the Left/Right side referring to both sides of a Join node, an equi-join condition cannot be inferred
        // and the join space is broken up. Hence, we observe a CrossJoin node
        assertPlan("select 1 from supplier s, partsupp ps, customer c, orders o  where s.suppkey = ps.suppkey and c.custkey = o.custkey and s.acctbal - c.acctbal = o.totalprice",
                anyTree(
                        join(INNER,
                                ImmutableList.of(equiJoinClause("C_CUSTKEY", "O_CUSTKEY"), equiJoinClause("SUBTRACT", "O_TOTALPRICE")),
                                anyTree(
                                        project(ImmutableMap.of("SUBTRACT", expression("S_ACCTBAL - C_ACCTBAL")),
                                                join(INNER,
                                                        ImmutableList.of(), //CrossJoin
                                                        join(INNER,
                                                                ImmutableList.of(equiJoinClause("PS_SUPPKEY", "S_SUPPKEY")),
                                                                anyTree(tableScan("partsupp", ImmutableMap.of("PS_SUPPKEY", "suppkey"))),
                                                                anyTree(
                                                                        tableScan("supplier", ImmutableMap.of("S_ACCTBAL", "acctbal", "S_SUPPKEY", "suppkey")))),
                                                        anyTree(
                                                                tableScan("customer", ImmutableMap.of("C_CUSTKEY", "custkey", "C_ACCTBAL", "acctbal")))))),
                                anyTree(
                                        tableScan("orders", ImmutableMap.of("O_CUSTKEY", "custkey", "O_TOTALPRICE", "totalprice"))))));

        // The table specification order determines the join order for such cases
        // With the below table specification order, the planner adds the complex equi-join condition as a FilterNode on top of a JoinNode
        assertPlan("select 1 from orders o, customer c, supplier s, partsupp ps where s.suppkey = ps.suppkey and c.custkey = o.custkey and s.acctbal - c.acctbal = o.totalprice",
                anyTree(
                        join(INNER,
                                ImmutableList.of(equiJoinClause("PS_SUPPKEY", "S_SUPPKEY")),
                                anyTree(
                                        tableScan("partsupp", ImmutableMap.of("PS_SUPPKEY", "suppkey"))),
                                anyTree(
                                        filter("O_TOTALPRICE = S_ACCTBAL - C_ACCTBAL",
                                                join(INNER,
                                                        ImmutableList.of(), //CrossJoin
                                                        join(INNER,
                                                                ImmutableList.of(equiJoinClause("O_CUSTKEY", "C_CUSTKEY")),
                                                                anyTree(tableScan("orders", ImmutableMap.of("O_CUSTKEY", "custkey", "O_TOTALPRICE", "totalprice"))),
                                                                anyTree(
                                                                        tableScan("customer", ImmutableMap.of("C_CUSTKEY", "custkey", "C_ACCTBAL", "acctbal")))),
                                                        anyTree(
                                                                tableScan("supplier", ImmutableMap.of("S_ACCTBAL", "acctbal", "S_SUPPKEY", "suppkey")))))))));

        // For sub-graphs that are fully connected, join-reordering works with complex predicates as expected
        // The rest of the join graph is connected using a CrossJoin
        assertPlan("select 1 " +
                "from orders o, customer c, supplier s, partsupp ps, part p " +
                "where s.suppkey = ps.suppkey " +
                "    and c.custkey = o.custkey " +
                "    and s.acctbal = c.acctbal + o.totalprice" +
                "    and ps.partkey - p.partkey = 0 ",
                anyTree(
                        filter("PS_PARTKEY - P_PARTKEY = 0",
                                join(INNER,
                                        ImmutableList.of(), // CrossJoin
                                        join(INNER,
                                                ImmutableList.of(equiJoinClause("PS_SUPPKEY", "S_SUPPKEY")),
                                                anyTree(
                                                        tableScan("partsupp", ImmutableMap.of("PS_SUPPKEY", "suppkey", "PS_PARTKEY", "partkey"))),
                                                anyTree(
                                                        join(INNER,
                                                                ImmutableList.of(equiJoinClause("SUM", "S_ACCTBAL")),
                                                                anyTree(
                                                                        project(ImmutableMap.of("SUM", expression("C_ACCTBAL + O_TOTALPRICE")),
                                                                                join(INNER,
                                                                                        ImmutableList.of(equiJoinClause("O_CUSTKEY", "C_CUSTKEY")),
                                                                                        anyTree(
                                                                                                tableScan("orders", ImmutableMap.of("O_CUSTKEY", "custkey", "O_TOTALPRICE", "totalprice"))),
                                                                                        anyTree(
                                                                                                tableScan("customer", ImmutableMap.of("C_CUSTKEY", "custkey", "C_ACCTBAL", "acctbal")))))),
                                                                anyTree(
                                                                        tableScan("supplier", ImmutableMap.of("S_ACCTBAL", "acctbal", "S_SUPPKEY", "suppkey")))))),
                                        anyTree(
                                                tableScan("part", ImmutableMap.of("P_PARTKEY", "partkey")))))));
    }

    private RuleAssert assertReorderJoins()
    {
        return tester.assertThat(new ReorderJoins(new CostComparator(1, 1, 1), tester.getMetadata()));
    }

    private RowExpression comparisonRowExpression(OperatorType type, RowExpression left, RowExpression right)
    {
        return call(type.name(), functionResolution.comparisonFunction(type, left.getType(), right.getType()), BOOLEAN, left, right);
    }
}
