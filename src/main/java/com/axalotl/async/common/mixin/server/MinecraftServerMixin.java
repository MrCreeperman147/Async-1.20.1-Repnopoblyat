package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, priority = Integer.MAX_VALUE)
public abstract class MinecraftServerMixin extends ReentrantBlockableEventLoop<TickTask> implements CommandSource, AutoCloseable {

    public MinecraftServerMixin(String string) {
        super(string);
    }

    // @Redirect est fragile sur les call sites obfusqués en production — le refmap ne résout pas
    // toujours correctement isSameThread() dans reloadResources hors environnement dev Parchment.
    // @WrapOperation (MixinExtras) ne dépend pas du refmap pour résoudre le call site cible.
    @WrapOperation(method = "reloadResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean onServerExecutionThreadPatch(MinecraftServer minecraftServer, Operation<Boolean> original) {
        return ParallelProcessor.isMainThread();
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void beforeStopServer(CallbackInfo ci) {
        ParallelProcessor.stop();
    }
}