package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.parallelised.ConcurrentCollections;
import com.axalotl.async.common.parallelised.fastutil.ConcurrentLongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import java.util.Set;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    @Shadow
    @Final
    @Mutable
    Set<ChunkHolder> chunksToUpdateFutures = ConcurrentCollections.newHashSet();
    @Shadow
    @Final
    @Mutable
    LongSet ticketsToRelease = new ConcurrentLongLinkedOpenHashSet();

    @WrapMethod(method = "purgeStaleTickets")
    private void async$purgeStaleTickets(Operation<Void> original) {
        try {
            original.call();
        } catch (NullPointerException ignored) {
            // Race condition entre purgeStaleTickets et les modifications
            // de tickets depuis des threads async — ignoré, sera retenté au prochain tick
        }
    }
}
