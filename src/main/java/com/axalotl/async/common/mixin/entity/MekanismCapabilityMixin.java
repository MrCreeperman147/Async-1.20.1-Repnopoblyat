package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.ParallelProcessor;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mekanism's BasicCapabilityResolver.cachedCapability est @Nullable et initialisé lazily.
 * Si deux threads async appellent resolve() simultanément, l'un peut lire null
 * sur cachedCapability avant que l'autre l'ait initialisé → NPE sur isPresent().
 *
 * Fix : synchroniser resolve() sur l'instance du resolver si on est sur un thread async.
 */
@Mixin(targets = "mekanism.common.capabilities.resolver.BasicCapabilityResolver", remap = false)
public abstract class MekanismCapabilityMixin {

    @WrapMethod(method = "resolve")
    private <T> LazyOptional<T> async$resolve(Capability<T> capability, Direction direction, Operation<LazyOptional<T>> original) {
        if (!ParallelProcessor.isAsyncThread()) {
            return original.call(capability, direction);
        }
        synchronized (this) {
            return original.call(capability, direction);
        }
    }
}
