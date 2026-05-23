package com.axalotl.async.common.mixin.spawn;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.config.AsyncConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(NaturalSpawner.SpawnState.class)
public abstract class SpawnStateMixin {

    @Shadow @Final private PotentialCalculator spawnPotential;
    @Shadow @Final private Object2IntOpenHashMap<MobCategory> mobCategoryCounts;
    @Shadow @Final private LocalMobCapCalculator localMobCapCalculator;
    @Shadow private BlockPos lastCheckedPos;
    @Shadow private EntityType<?> lastCheckedType;
    @Shadow private double lastCharge;

    @Unique
    private final Object async$lock = new Object();

    @Unique
    private final ConcurrentHashMap<MobCategory, AtomicInteger> async$concurrentCounts = new ConcurrentHashMap<>();

    @Inject(method = "afterSpawn", at = @At("HEAD"), cancellable = true)
    private void async$afterSpawn(Mob mob, ChunkAccess chunk, CallbackInfo ci) {
        if (AsyncConfig.disabled.getValue() || !AsyncConfig.enableAsyncSpawn.getValue()) {
            return;
        }

        ci.cancel();

        EntityType<?> entityType = mob.getType();
        BlockPos blockPos = mob.blockPosition();

        double charge;
        if (blockPos.equals(this.lastCheckedPos) && entityType == this.lastCheckedType) {
            charge = this.lastCharge;
        } else {
            MobSpawnSettings.MobSpawnCost cost = NaturalSpawner.getRoughBiome(blockPos, chunk)
                    .getMobSettings()
                    .getMobSpawnCost(entityType);
            charge = cost != null ? cost.charge() : 0.0;
        }

        MobCategory category = entityType.getCategory();

        // Écriture atomique lock-free — source de vérité pour les accès async
        this.spawnPotential.addCharge(blockPos, charge);
        async$concurrentCounts.computeIfAbsent(category, k -> new AtomicInteger(0)).incrementAndGet();
        this.localMobCapCalculator.addMob(new ChunkPos(blockPos), category);

        // Mise à jour du champ vanilla sous lock — pour la compatibilité avec les mods tiers
        synchronized (async$lock) {
            this.mobCategoryCounts.addTo(category, 1);
        }
    }

    @WrapOperation(
            method = "canSpawnForCategory",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;getInt(Ljava/lang/Object;)I")
    )
    private int async$concurrentGetCount(Object2IntOpenHashMap<MobCategory> map, Object category, Operation<Integer> original) {
        if (AsyncConfig.disabled.getValue() || !AsyncConfig.enableAsyncSpawn.getValue()) {
            return original.call(map, category);
        }

        Thread current = Thread.currentThread();
        boolean isMainThread = current == ParallelProcessor.getServer().getRunningThread();
        boolean isAsyncThread = ParallelProcessor.isServerExecutionThread();

        if (!isMainThread && !isAsyncThread) {
            ParallelProcessor.LOGGER.warn(
                    "[Async] canSpawnForCategory called from unexpected thread '{}' — " +
                            "a mod may be reading mobCategoryCounts directly. " +
                            "This bypasses thread-safe spawn counting.",
                    current.getName()
            );
        }

        AtomicInteger counter = async$concurrentCounts.get(category);
        return counter != null ? counter.get() : 0;
    }
}