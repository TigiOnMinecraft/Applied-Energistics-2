/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.worldgen.meteorite.fallout;

import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import appeng.worldgen.meteorite.MeteoriteBlockPutter;

public class FalloutCopy extends Fallout {
    private static final float SPECIFIED_BLOCK_THRESHOLD = 0.9f;
    private static final float AIR_BLOCK_THRESHOLD = 0.8f;
    private static final float BLOCK_THRESHOLD_STEP = 0.1f;

    private final BlockState block;
    private final MeteoriteBlockPutter putter;

    public FalloutCopy(LevelAccessor level, BlockPos pos, MeteoriteBlockPutter putter,
            BlockState skyStone, Random random) {
        super(putter, skyStone, random);
        this.putter = putter;
        // TODO 1.18 its no longer possible to get a biome specific surface builder top material
        // this.block = level.getBiome(pos).getGenerationSettings().getSurfaceBuilderConfig().getTopMaterial();
        this.block = Blocks.DIRT.defaultBlockState();
    }

    @Override
    public void getRandomFall(final LevelAccessor level, BlockPos pos) {
        var a = random.nextFloat();
        if (a > SPECIFIED_BLOCK_THRESHOLD) {
            this.putter.put(level, pos, this.block);
        } else {
            this.getOther(level, pos, a);
        }
    }

    public void getOther(final LevelAccessor level, BlockPos pos, float a) {

    }

    @Override
    public void getRandomInset(LevelAccessor level, BlockPos pos) {
        var a = random.nextFloat();
        if (a > SPECIFIED_BLOCK_THRESHOLD) {
            this.putter.put(level, pos, this.block);
        } else if (a > AIR_BLOCK_THRESHOLD) {
            this.putter.put(level, pos, Blocks.AIR.defaultBlockState());
        } else {
            this.getOther(level, pos, a - BLOCK_THRESHOLD_STEP);
        }
    }
}
