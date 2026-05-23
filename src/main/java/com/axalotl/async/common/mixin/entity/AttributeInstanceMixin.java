package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.parallelised.ConcurrentCollections;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(AttributeInstance.class)
public class AttributeInstanceMixin {

    @Shadow
    @Final
    @Mutable
    private Map<UUID, AttributeModifier> modifierById;

    @Shadow
    @Final
    @Mutable
    private Set<AttributeModifier> permanentModifiers;

    @Shadow
    @Final
    @Mutable
    private Map<AttributeModifier.Operation, Set<AttributeModifier>> modifiersByOperation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void makeThreadSafe(CallbackInfo ci) {
        modifierById = new java.util.concurrent.ConcurrentHashMap<>(modifierById);
        permanentModifiers = ConcurrentCollections.newHashSet();
    }
}
