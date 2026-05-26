package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.ParallelProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LeafcutterAntAIForageLeaves.isValidPosition() appelle level.getBlockState()
 * sans vérifier si le chunk cible est chargé, ce qui déclenche un chargement
 * de chunk depuis un thread async → race condition dans DistanceManager.
 *
 * Fix : retourner false si on est sur un thread async et que le chunk n'est
 * pas déjà chargé — la fourmi réessaiera au prochain tick.
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.ai.LeafcutterAntAIForageLeaves", remap = false)
public abstract class LeafcutterAntAIMixin {

    @Inject(method = "m_6465_", at = @At("HEAD"), cancellable = true, remap = false)
    private void async$isValidPosition(LevelReader world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!ParallelProcessor.isAsyncThread()) return;

        // Sur thread async : vérifier que le chunk est déjà chargé
        // avant toute tentative d'accès à getBlockState()
        if (!world.hasChunkAt(pos)) {
            cir.setReturnValue(false);
        }
    }
}
