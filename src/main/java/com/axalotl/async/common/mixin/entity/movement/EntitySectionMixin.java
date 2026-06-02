package com.axalotl.async.common.mixin.entity.movement;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;




import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;

@Mixin(EntitySection.class)
public class EntitySectionMixin<T extends EntityAccess> {

    @Shadow
    @Final
    private ClassInstanceMultiMap<T> storage;

    @Shadow
    private Visibility chunkStatus;

    @Unique
    private final AtomicReference<Visibility> async$atomicStatus = new AtomicReference<>(Visibility.HIDDEN);

    @Unique
    private final Object async$storageLock = new Object();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void async$init(Class<?> clazz, Visibility status, CallbackInfo ci) {
        async$atomicStatus.set(status != null ? status : Visibility.HIDDEN);
    }

    /**
     * @author FurryMileon
     * @reason Make add thread-safe with per-instance lock
     */
    @Overwrite
    public void add(T entity) {
        synchronized (async$storageLock) {
            this.storage.add(entity);
        }
    }

    /**
     * @author FurryMileon
     * @reason Make remove thread-safe with per-instance lock
     */
    @Overwrite
    public boolean remove(T entity) {
        synchronized (async$storageLock) {
            return this.storage.remove(entity);
        }
    }

    /**
     * @author FurryMileon
     * @reason Make getStatus thread-safe via atomic read
     */
    @Overwrite
    public Visibility getStatus() {
        return async$atomicStatus.get();
    }

    /**
     * @author FurryMileon
     * @reason Make updateChunkStatus thread-safe via atomic swap
     */
    @Overwrite
    public Visibility updateChunkStatus(Visibility status) {
        Visibility safeStatus = status != null ? status : Visibility.HIDDEN;
        Visibility old = async$atomicStatus.getAndSet(safeStatus);
        this.chunkStatus = safeStatus;
        return old != null ? old : Visibility.HIDDEN;
    }

    @WrapMethod(method = "getEntities()Ljava/util/stream/Stream;")
    private Stream<T> getEntities(Operation<Stream<T>> original) {
        return storage.stream()
                .filter(Objects::nonNull)
                .toList()
                .stream();
    }

    /**
     * @author MrCreeperman147
     * @reason Make isEmpty thread-safe via storageLock — fixes ac_collide deadlock
     */
    @Overwrite
    public boolean isEmpty() {
        synchronized (async$storageLock) {
            return this.storage.isEmpty();
        }
    }

    /**
     * @author MrCreeperman147
     * @reason Make typed getEntities thread-safe via snapshot — fixes ac_collide deadlock
     */
    @Overwrite
    public <U extends T> AbortableIterationConsumer.Continuation getEntities(EntityTypeTest<T, U> filter, AABB bounds, AbortableIterationConsumer<? super U> consumer) {
        List<T> snapshot;
        synchronized (async$storageLock) {
            snapshot = new ArrayList<>(this.storage);
        }
        for (T entity : snapshot) {
            U casted = filter.tryCast(entity);
            if (casted != null && casted.getBoundingBox().intersects(bounds)) {
                if (consumer.accept(casted) == AbortableIterationConsumer.Continuation.ABORT) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

}