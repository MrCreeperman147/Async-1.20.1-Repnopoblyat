/*
package com.axalotl.async.common.mixin.spawn;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.function.Consumer;

@Mixin(ServerLevelAccessor.class)
public interface ServerLevelAccessorMixin {

    @WrapOperation(
            method = "addFreshEntityWithPassengers",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z")
    )
    default boolean async$syncAddFreshEntity(ServerLevelAccessor instance, Entity entity, Operation<Boolean> original) {
        if (AsyncConfig.disabled.getValue() || !AsyncConfig.enableAsyncSpawn.getValue()) {
            return original.call(instance, entity);
        }
        synchronized (ParallelProcessor.getEntityAddLock()) {
            return original.call(instance, entity);
        }
    }
}


 */