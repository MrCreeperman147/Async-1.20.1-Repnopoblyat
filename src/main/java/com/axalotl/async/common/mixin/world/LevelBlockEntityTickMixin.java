package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.ParallelProcessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Front B (PROTOTYPE) — parallélisation du ticking des block entities.
 *
 * <p>Calqué sur l'approche entité de {@link com.axalotl.async.common.mixin.world.ServerLevelMixin}
 * (redirect de la boucle de tick + barrière de fin). Ici on intercepte l'unique site d'appel
 * {@code TickingBlockEntity.tick()} dans {@code Level.tickBlockEntities()} et on le route vers
 * {@link ParallelProcessor#callBlockEntityTick}, qui décide sync/async selon la whitelist.
 *
 * <p>Comportement par défaut = vanilla strict : tant que {@code enableAsyncBlockEntities} est false
 * ou que la whitelist {@code parallelBlockEntities} ne matche pas le type, le tick reste synchrone.
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {

    @Redirect(
            method = "tickBlockEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"
            )
    )
    private void async$routeBlockEntityTick(TickingBlockEntity bte) {
        ParallelProcessor.callBlockEntityTick((Level) (Object) this, bte);
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void async$drainBlockEntityTick(CallbackInfo ci) {
        ParallelProcessor.postBlockEntityTick();
    }
}
