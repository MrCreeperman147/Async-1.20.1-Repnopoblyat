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

    /*
    @Redirect(method = "reloadResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean onServerExecutionThreadPatch(MinecraftServer minecraftServer) {
        return ParallelProcessor.isMainThread();
    }
    */

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void beforeStopServer(CallbackInfo ci) {
        ParallelProcessor.stop();
    }
}