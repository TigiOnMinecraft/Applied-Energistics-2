/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
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

package appeng.helpers;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.config.Actionable;
import appeng.api.config.Upgrades;
import appeng.api.implementations.IUpgradeInventory;
import appeng.api.implementations.IUpgradeableObject;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.GenericStack;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.IStorageMonitorableAccessor;
import appeng.api.storage.StorageChannels;
import appeng.api.storage.StorageHelper;
import appeng.api.storage.data.AEFluidKey;
import appeng.api.storage.data.AEItemKey;
import appeng.api.storage.data.AEKey;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.core.settings.TickRates;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.MEMonitorPassThrough;
import appeng.parts.automation.StackUpgradeInventory;
import appeng.parts.automation.UpgradeInventory;
import appeng.util.ConfigInventory;
import appeng.util.ConfigManager;
import appeng.util.Platform;
import appeng.util.inv.InternalInventoryHost;

/**
 * Contains behavior for interface blocks and parts, which is independent of the storage channel.
 */
public abstract class DualityInterface<T extends AEKey> implements ICraftingRequester, IUpgradeableObject,
        IConfigurableObject, InternalInventoryHost {

    protected final MEMonitorPassThrough<AEItemKey> items = new MEMonitorPassThrough<>(StorageChannels.items());
    protected final MEMonitorPassThrough<AEFluidKey> fluids = new MEMonitorPassThrough<>(StorageChannels.fluids());

    protected final IInterfaceHost host;
    protected final IManagedGridNode mainNode;
    protected final IActionSource actionSource;
    protected final IActionSource interfaceRequestSource;
    private final MultiCraftingTracker craftingTracker;
    private final UpgradeInventory upgrades;
    private final IStorageMonitorableAccessor accessor = this::getMonitorable;
    private final ConfigManager cm = new ConfigManager((manager, setting) -> {
        onConfigChanged();
    });
    /**
     * Work planned by {@link #updatePlan()} to be performed by {@link #usePlan}. Positive amounts mean restocking from
     * the network is required while negative amounts mean moving to the network is required.
     */
    private final GenericStack[] plannedWork = new GenericStack[getStorageSlots()];
    private int priority;
    /**
     * Configures what and how much to stock in this inventory.
     */
    private final ConfigInventory<T> config = ConfigInventory.configStacks(getChannel(), getStorageSlots(),
            this::readConfig);
    /**
     * True if the interface is configured to stock certain types of resources.
     */
    private boolean hasConfig = false;
    private final ConfigInventory<T> storage = ConfigInventory.storage(getChannel(), getStorageSlots(),
            this::updatePlan);

    public DualityInterface(IManagedGridNode gridNode, IInterfaceHost host, ItemStack is) {
        this.host = host;
        this.mainNode = gridNode
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(IGridTickable.class, new Ticker());
        this.actionSource = new MachineSource(mainNode::getNode);

        this.fluids.setChangeSource(this.actionSource);
        this.items.setChangeSource(this.actionSource);

        this.interfaceRequestSource = new InterfaceRequestSource(mainNode::getNode);

        gridNode.addService(ICraftingRequester.class, this);
        this.upgrades = new StackUpgradeInventory(is, this, 1);
        this.craftingTracker = new MultiCraftingTracker(this, 9);
    }

    protected abstract IStorageChannel<T> getChannel();

    protected abstract int getStorageSlots();

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.host.saveChanges();
    }

    private void readConfig() {
        this.hasConfig = !this.config.isEmpty();
        updatePlan();
        this.notifyNeighbors();
    }

    public void writeToNBT(CompoundTag tag) {
        this.config.writeToChildTag(tag, "config");
        this.storage.writeToChildTag(tag, "storage");
        this.upgrades.writeToNBT(tag, "upgrades");
        this.cm.writeToNBT(tag);
        this.craftingTracker.writeToNBT(tag);
        tag.putInt("priority", this.priority);
    }

    public void readFromNBT(CompoundTag tag) {
        this.craftingTracker.readFromNBT(tag);
        this.upgrades.readFromNBT(tag, "upgrades");
        this.config.readFromChildTag(tag, "config");
        this.storage.writeToChildTag(tag, "storage");
        this.cm.readFromNBT(tag);
        this.readConfig();
        this.priority = tag.getInt("priority");
    }

    public IStorageMonitorableAccessor getGridStorageAccessor() {
        return accessor;
    }

    private class Ticker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(final IGridNode node) {
            return new TickingRequest(TickRates.Interface.getMin(), TickRates.Interface.getMax(), !hasWorkToDo(),
                    true);
        }

        @Override
        public TickRateModulation tickingRequest(final IGridNode node, final int ticksSinceLastCall) {
            if (!mainNode.isActive()) {
                return TickRateModulation.SLEEP;
            }

            boolean couldDoWork = updateStorage();
            return hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                    : TickRateModulation.SLEEP;
        }
    }

    /**
     * If the request is for a local inventory operation of an AE interface, returns the priority of that interface.
     */
    protected final OptionalInt getRequestInterfacePriority(IActionSource src) {
        return src.context(InterfaceRequestContext.class)
                .map(ctx -> OptionalInt.of(ctx.getPriority()))
                .orElseGet(OptionalInt::empty);
    }

    protected final boolean hasWorkToDo() {
        for (var requiredWork : this.plannedWork) {
            if (requiredWork != null) {
                return true;
            }
        }

        return false;
    }

    public void notifyNeighbors() {
        if (this.mainNode.isActive()) {
            this.mainNode.ifPresent((grid, node) -> {
                grid.getTickManager().wakeDevice(node);
            });
        }

        final BlockEntity te = this.host.getBlockEntity();
        if (te != null && te.getLevel() != null) {
            Platform.notifyBlocksOfNeighbors(te.getLevel(), te.getBlockPos());
        }
    }

    public void gridChanged() {
        var grid = mainNode.getGrid();
        if (grid != null) {
            this.items.setMonitor(grid.getStorageService().getInventory(StorageChannels.items()));
            this.fluids.setMonitor(grid.getStorageService().getInventory(StorageChannels.fluids()));
        } else {
            this.items.setMonitor(null);
            this.fluids.setMonitor(null);
        }

        this.notifyNeighbors();
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.cm;
    }

    public ConfigInventory<T> getStorage() {
        return storage;
    }

    public ConfigInventory<T> getConfig() {
        return config;
    }

    private IStorageMonitorable getMonitorable(IActionSource src) {
        // If the given action source can access the grid, return the real inventory
        if (Platform.canAccess(mainNode, src)) {
            return this::getInventory;
        }

        // Otherwise, return a fallback that only exposes the local interface inventory
        return this::getLocalInventory;
    }

    /**
     * Gets the inventory that is exposed to an ME compatible API user if they have access to the grid this interface is
     * a part of. This is normally accessed by storage buses.
     * <p/>
     * If the interface has configured slots, it will <b>always</b> expose its local inventory instead of the grid's
     * inventory.
     */
    private <T extends AEKey> IMEMonitor<T> getInventory(IStorageChannel<T> channel) {
        if (hasConfig) {
            return getLocalInventory(channel);
        }

        if (channel == StorageChannels.items()) {
            return this.items.cast(channel);
        } else if (channel == StorageChannels.fluids()) {
            return this.fluids.cast(channel);
        }

        return null;
    }

    /**
     * Returns an ME compatible monitor for the interface's local storage for a given storage channel.
     */
    protected abstract <T extends AEKey> IMEMonitor<T> getLocalInventory(IStorageChannel<T> channel);

    private class InterfaceRequestSource extends MachineSource {
        private final InterfaceRequestContext context;

        InterfaceRequestSource(IActionHost v) {
            super(v);
            this.context = new InterfaceRequestContext();
        }

        @Override
        public <T> Optional<T> context(Class<T> key) {
            if (key == InterfaceRequestContext.class) {
                return Optional.of(key.cast(this.context));
            }

            return super.context(key);
        }
    }

    private class InterfaceRequestContext {
        public int getPriority() {
            return priority;
        }
    }

    private boolean updateStorage() {
        boolean didSomething = false;

        for (int x = 0; x < plannedWork.length; x++) {
            var work = plannedWork[x];
            if (work != null) {
                var what = getChannel().tryCast(work.what());
                if (what != null) {
                    var amount = (int) work.amount();
                    didSomething = this.usePlan(x, what, amount) || didSomething;
                } else {
                    plannedWork[x] = null;
                }
            }
        }

        return didSomething;
    }

    private boolean usePlan(final int x, T what, int amount) {
        boolean changed = tryUsePlan(x, what, amount);

        if (changed) {
            this.updatePlan(x);
        }

        return changed;
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return this.craftingTracker.getRequestedJobs();
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        int slot = this.craftingTracker.getSlot(link);
        return storage.insert(slot, what, amount, mode);
    }

    @Override
    public void jobStateChange(final ICraftingLink link) {
        this.craftingTracker.jobStateChange(link);
    }

    @Nonnull
    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    @Nullable
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    /**
     * Check if there's any work to do to get into the state configured by {@link #config} and wake up the machine if
     * necessary.
     */
    private void updatePlan() {
        var hadWork = this.hasWorkToDo();
        for (int x = 0; x < this.config.size(); x++) {
            this.updatePlan(x);
        }
        var hasWork = this.hasWorkToDo();

        if (hadWork != hasWork) {
            mainNode.ifPresent((grid, node) -> {
                if (hasWork) {
                    grid.getTickManager().alertDevice(node);
                } else {
                    grid.getTickManager().sleepDevice(node);
                }
            });
        }
    }

    /**
     * Compute the delta between the desired state in {@link #config} and the current contents of the local storage and
     * make a plan on what needs to be changed in {@link #plannedWork}.
     */
    private void updatePlan(int slot) {
        var req = this.config.getStack(slot);
        var stored = this.storage.getStack(slot);

        if (req == null && stored != null) {
            this.plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
        } else if (req != null) {
            if (stored == null) {
                // Nothing stored, request from network
                this.plannedWork[slot] = req;
            } else if (req.what().equals(stored.what())) {
                if (req.amount() != stored.amount()) {
                    // Already correct type, but incorrect amount, equilize the difference
                    this.plannedWork[slot] = new GenericStack(req.what(), req.amount() - stored.amount());
                } else {
                    this.plannedWork[slot] = null;
                }
            } else {
                // Requested item differs from stored -> push back into storage before fulfilling request
                this.plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
            }
        } else {
            // Slot matches desired state
            this.plannedWork[slot] = null;
        }
    }

    /**
     * Execute on plan made in {@link #updatePlan(int)}
     */
    private boolean tryUsePlan(int slot, T what, int amount) {
        var grid = mainNode.getGrid();
        if (grid == null) {
            return false;
        }

        var networkInv = grid.getStorageService().getInventory(getChannel());
        var energySrc = grid.getEnergyService();

        if (this.craftingTracker.isBusy(slot)) {
            // We are waiting for a crafting result for this slot, check its status before taking other actions
            return this.handleCrafting(slot, what, amount);
        } else if (amount > 0) {
            // Move from network into interface
            // Ensure the plan isn't outdated
            if (storage.insert(slot, what, amount, Actionable.SIMULATE) != amount) {
                return true;
            }

            var acquired = (int) StorageHelper.poweredExtraction(energySrc, networkInv, what, amount,
                    this.interfaceRequestSource);
            if (acquired > 0) {
                var inserted = storage.insert(slot, what, acquired, Actionable.MODULATE);
                if (inserted < acquired) {
                    throw new IllegalStateException("bad attempt at managing inventory. Voided items: " + inserted);
                }
                return true;
            } else {
                return this.handleCrafting(slot, what, amount);
            }
        } else if (amount < 0) {
            // Move from interface to network storage
            amount = -amount;

            // Make sure the storage has enough items to execute the plan
            var inSlot = storage.getStack(slot);
            if (!what.matches(inSlot) || inSlot.amount() < amount) {
                return true;
            }

            var inserted = (int) StorageHelper.poweredInsert(energySrc, networkInv, what, amount,
                    this.interfaceRequestSource);

            // Remove the items we just injected somewhere else into the network.
            if (inserted > 0) {
                storage.extract(slot, what, inserted, Actionable.MODULATE);
            }

            return inserted > 0;
        }

        // else wtf?
        return false;
    }

    private boolean handleCrafting(int x, T key, long amount) {
        var grid = mainNode.getGrid();
        if (grid != null && upgrades.getInstalledUpgrades(Upgrades.CRAFTING) > 0 && key != null) {
            return this.craftingTracker.handleCrafting(x, key, amount,
                    this.host.getBlockEntity().getLevel(),
                    grid.getCraftingService(),
                    this.actionSource);
        }

        return false;
    }

    private void cancelCrafting() {
        this.craftingTracker.cancel();
    }

    private void onConfigChanged() {
        this.host.saveChanges();
    }

    public void saveChanges() {
        this.host.saveChanges();
    }

    @Override
    public void onChangeInventory(InternalInventory inv, int slot, ItemStack removedStack, ItemStack newStack) {
        // Cancel crafting if the crafting card is removed
        if (inv == upgrades && upgrades.getInstalledUpgrades(Upgrades.CRAFTING) == 0) {
            this.cancelCrafting();
        }
    }

    @Override
    public boolean isClientSide() {
        Level level = this.host.getBlockEntity().getLevel();
        return level == null || level.isClientSide();
    }

    public void addDrops(final List<ItemStack> drops) {
        for (var is : this.upgrades) {
            if (!is.isEmpty()) {
                drops.add(is);
            }
        }

        for (int i = 0; i < this.storage.size(); i++) {
            var stack = storage.getStack(i);
            if (stack != null && stack.what() instanceof AEItemKey itemKey) {
                drops.add(itemKey.toStack((int) stack.amount()));
            }
        }
    }

}
