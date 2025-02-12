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
package com.facebook.presto.operator.aggregation.noisyaggregation;

import com.facebook.presto.bytecode.DynamicClassLoader;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.type.StandardTypes;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.SqlAggregationFunction;
import com.facebook.presto.operator.aggregation.AccumulatorCompiler;
import com.facebook.presto.operator.aggregation.BuiltInAggregationFunctionImplementation;
import com.facebook.presto.operator.aggregation.state.StateCompiler;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.AccumulatorStateFactory;
import com.facebook.presto.spi.function.AccumulatorStateSerializer;
import com.facebook.presto.spi.function.aggregation.Accumulator;
import com.facebook.presto.spi.function.aggregation.AggregationMetadata;
import com.facebook.presto.spi.function.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import com.facebook.presto.spi.function.aggregation.GroupedAccumulator;
import com.google.common.collect.ImmutableList;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Random;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.operator.aggregation.AggregationUtils.generateAggregationName;
import static com.facebook.presto.operator.aggregation.noisyaggregation.NoisyCountGaussianColumnAggregationUtils.computeNoisyCount;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.function.Signature.typeVariable;
import static com.facebook.presto.spi.function.aggregation.AggregationMetadata.ParameterMetadata;
import static com.facebook.presto.spi.function.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static com.facebook.presto.spi.function.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL;
import static com.facebook.presto.spi.function.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static com.facebook.presto.util.Reflection.methodHandle;
import static com.google.common.collect.ImmutableList.toImmutableList;

public class NoisyCountGaussianColumnRandomSeedAggregation
        extends SqlAggregationFunction
{
    public static final NoisyCountGaussianColumnRandomSeedAggregation NOISY_COUNT_GAUSSIAN_RANDOM_SEED_AGGREGATION = new NoisyCountGaussianColumnRandomSeedAggregation();
    private static final String NAME = "noisy_count_gaussian";
    private static final MethodHandle INPUT_FUNCTION = methodHandle(NoisyCountGaussianColumnRandomSeedAggregation.class, "input", NoisyCountState.class, Block.class, Block.class, Block.class, int.class);
    private static final MethodHandle COMBINE_FUNCTION = methodHandle(NoisyCountGaussianColumnRandomSeedAggregation.class, "combine", NoisyCountState.class, NoisyCountState.class);
    private static final MethodHandle OUTPUT_FUNCTION = methodHandle(NoisyCountGaussianColumnRandomSeedAggregation.class, "output", NoisyCountState.class, BlockBuilder.class);

    public NoisyCountGaussianColumnRandomSeedAggregation()
    {
        super(NAME,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                parseTypeSignature(StandardTypes.BIGINT),
                ImmutableList.of(parseTypeSignature("T"), DOUBLE.getTypeSignature(), BIGINT.getTypeSignature()));
    }

    @Override
    public String getDescription()
    {
        return "Counts the non-null values and then add Gaussian noise to the true count. The noisy count is post-processed to be non-negative and rounded to bigint. Random seed is used to seed random generator. This method does not use a secure random.";
    }

    @Override
    public BuiltInAggregationFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        Type type = boundVariables.getTypeVariable("T");
        return generateAggregation(type);
    }

    private static BuiltInAggregationFunctionImplementation generateAggregation(Type type)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(NoisyCountGaussianColumnRandomSeedAggregation.class.getClassLoader());

        AccumulatorStateSerializer<NoisyCountState> stateSerializer = StateCompiler.generateStateSerializer(NoisyCountState.class, classLoader);
        AccumulatorStateFactory<NoisyCountState> stateFactory = StateCompiler.generateStateFactory(NoisyCountState.class, classLoader);
        Type intermediateType = stateSerializer.getSerializedType();

        List<Type> inputTypes = ImmutableList.of(type, DOUBLE, BIGINT);

        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, BIGINT.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                createInputParameterMetadata(type),
                INPUT_FUNCTION,
                COMBINE_FUNCTION,
                OUTPUT_FUNCTION,
                ImmutableList.of(new AccumulatorStateDescriptor(
                        NoisyCountState.class,
                        stateSerializer,
                        stateFactory)),
                BIGINT);

        Class<? extends Accumulator> accumulatorClass = AccumulatorCompiler.generateAccumulatorClass(
                Accumulator.class,
                metadata,
                classLoader);
        Class<? extends GroupedAccumulator> groupedAccumulatorClass = AccumulatorCompiler.generateAccumulatorClass(
                GroupedAccumulator.class,
                metadata,
                classLoader);
        return new BuiltInAggregationFunctionImplementation(NAME, inputTypes, ImmutableList.of(intermediateType), BIGINT,
                true, false, metadata, accumulatorClass, groupedAccumulatorClass);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type type)
    {
        return ImmutableList.of(
                new ParameterMetadata(STATE),
                new ParameterMetadata(BLOCK_INPUT_CHANNEL, type),
                new ParameterMetadata(BLOCK_INPUT_CHANNEL, DOUBLE),
                new ParameterMetadata(BLOCK_INPUT_CHANNEL, BIGINT),
                new ParameterMetadata(BLOCK_INDEX));
    }

    public static void input(NoisyCountState state, Block valueBlock, Block noiseScaleBlock, Block randomSeedBlock, int index)
    {
        double noiseScale = DOUBLE.getDouble(noiseScaleBlock, index);
        if (noiseScale < 0) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Noise scale must be >= 0");
        }
        // Update count and retain scale and random seed
        state.setCount(state.getCount() + 1);
        state.setNoiseScale(noiseScale);
        state.setRandomSeed(BIGINT.getLong(randomSeedBlock, index));
    }

    public static void combine(NoisyCountState state, NoisyCountState otherState)
    {
        state.setCount(state.getCount() + otherState.getCount());
        state.setNoiseScale(state.getNoiseScale() > 0 ? state.getNoiseScale() : otherState.getNoiseScale()); // noise scale should be > 0
        state.setRandomSeed(otherState.getRandomSeed());
    }

    public static void output(NoisyCountState state, BlockBuilder out)
    {
        if (state.getCount() == 0) {
            out.appendNull();
            return;
        }

        Random random = new Random(state.getRandomSeed());
        long noisyCountFixedSignAndType = computeNoisyCount(state.getCount(), state.getNoiseScale(), random);
        BIGINT.writeLong(out, noisyCountFixedSignAndType);
    }
}
