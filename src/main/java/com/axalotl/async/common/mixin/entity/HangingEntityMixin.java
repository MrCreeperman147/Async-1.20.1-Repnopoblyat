package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.ParallelProcessor;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {

    /**
     * checkSupportingBlock() appelle level.getBlockState() sur le bloc support,
     * ce qui peut déclencher un chargement de chunk depuis un thread async
     * → race condition dans DistanceManager + "Hanging entity at invalid position".
     *
     * Si on est sur un thread async, on déféré le check au Server Thread via
     * server.execute(). L'entité n'est pas supprimée immédiatement si le bloc
     * support manque — elle le sera au prochain tick sur le main thread.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void async$tick(CallbackInfo ci) {
        if (!ParallelProcessor.isAsyncThread()) return;

        // Sur thread async : déléguer le tick complet au main thread
        net.minecraft.server.MinecraftServer srv = ParallelProcessor.getServer();
        if (srv != null) {
            final HangingEntity self = (HangingEntity)(Object)this;
            srv.execute(self::tick);
            ci.cancel();
        }
    }
}
