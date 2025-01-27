/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
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

package appeng.me.cluster.implementations;

import java.util.Iterator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.IAEMultiBlock;
import appeng.me.cluster.MBCalculator;

public class CraftingCPUCalculator extends MBCalculator<CraftingBlockEntity, CraftingCPUCluster> {

    public CraftingCPUCalculator(final CraftingBlockEntity t) {
        super(t);
    }

    @Override
    public boolean checkMultiblockScale(final BlockPos min, final BlockPos max) {
        if (max.getX() - min.getX() > 16) {
            return false;
        }

        if (max.getY() - min.getY() > 16) {
            return false;
        }

        if (max.getZ() - min.getZ() > 16) {
            return false;
        }

        return true;
    }

    @Override
    public CraftingCPUCluster createCluster(final ServerLevel level, final BlockPos min, final BlockPos max) {
        return new CraftingCPUCluster(min, max);
    }

    @Override
    public boolean verifyInternalStructure(final ServerLevel level, final BlockPos min, final BlockPos max) {
        boolean storage = false;

        for (BlockPos blockPos : BlockPos.betweenClosed(min, max)) {
            final IAEMultiBlock<?> te = (IAEMultiBlock<?>) level.getBlockEntity(blockPos);

            if (te == null || !te.isValid()) {
                return false;
            }

            if (!storage && te instanceof CraftingBlockEntity) {
                storage = ((CraftingBlockEntity) te).getStorageBytes() > 0;
            }
        }

        return storage;
    }

    @Override
    public void updateBlockEntities(final CraftingCPUCluster c, final ServerLevel level, final BlockPos min,
            final BlockPos max) {
        for (BlockPos blockPos : BlockPos.betweenClosed(min, max)) {
            final CraftingBlockEntity te = (CraftingBlockEntity) level.getBlockEntity(blockPos);
            te.updateStatus(c);
            c.addBlockEntity(te);
        }

        c.done();

        final Iterator<CraftingBlockEntity> i = c.getBlockEntities();
        while (i.hasNext()) {
            var gh = i.next();
            var n = gh.getGridNode();
            if (n != null) {
                final IGrid g = n.getGrid();
                if (g != null) {
                    g.postEvent(new GridCraftingCpuChange(n));
                    return;
                }
            }
        }
    }

    @Override
    public boolean isValidBlockEntity(final BlockEntity te) {
        return te instanceof CraftingBlockEntity;
    }
}
