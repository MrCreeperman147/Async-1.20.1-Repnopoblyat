package com.axalotl.async.common.mixin.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class OptionalMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final Map<String, String> MIXIN_TO_TARGET = Map.of(
            "com.axalotl.async.common.mixin.entity.MekanismCapabilityMixin",
            "mekanism.common.capabilities.resolver.BasicCapabilityResolver",
            "com.axalotl.async.common.mixin.entity.LeafcutterAntAIMixin",
            "com.github.alexthe666.alexsmobs.entity.ai.LeafcutterAntAIForageLeaves"
    );

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String targetClass = MIXIN_TO_TARGET.get(mixinClassName);
        if (targetClass == null) return true;
        try {
            Class.forName(targetClass, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Skipping optional mixin {} — {} not found", mixinClassName, targetClass);
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}