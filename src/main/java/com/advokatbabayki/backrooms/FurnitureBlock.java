package com.advokatbabayki.backrooms;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Rotatable furniture. Geometry lives in gen_furniture_models.py. */
public class FurnitureBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<FurnitureBlock> CODEC = simpleCodec(FurnitureBlock::new);
    public static final IntegerProperty VARIANT_TWO = IntegerProperty.create("variant", 0, 1);
    public static final IntegerProperty VARIANT_THREE = IntegerProperty.create("variant", 0, 2);

    private final VoxelShape shape;

    public FurnitureBlock(BlockBehaviour.Properties properties) {
        this(properties, Shapes.block());
    }

    public FurnitureBlock(BlockBehaviour.Properties properties, VoxelShape shape) {
        super(properties);
        this.shape = shape;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return shape;
    }

    /** Furniture with two texture variants. */
    public static class TwoVariant extends FurnitureBlock {
        public TwoVariant(BlockBehaviour.Properties properties, VoxelShape shape) {
            super(properties, shape);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(VARIANT_TWO);
        }
    }

    /** Furniture with three texture variants, used by the desks. */
    public static class ThreeVariant extends FurnitureBlock {
        public ThreeVariant(BlockBehaviour.Properties properties, VoxelShape shape) {
            super(properties, shape);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(VARIANT_THREE);
        }
    }
}
