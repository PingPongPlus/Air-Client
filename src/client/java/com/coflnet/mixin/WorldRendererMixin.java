package com.coflnet.mixin;

import CoflCore.classes.Position;
import com.coflnet.EventSubscribers;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderWorld(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline, CameraRenderState cameraState, Matrix4fc positionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        if (EventSubscribers.positions == null || EventSubscribers.positions.isEmpty()) {
            return;
        }

        if (cameraState == null || cameraState.pos == null) {
            return;
        }

        // Create a PoseStack for world-space rendering
        PoseStack matrices = new PoseStack();
        matrices.mulPose(new org.joml.Matrix4f(positionMatrix));
        
        for (Position position : EventSubscribers.positions) {
            RenderUtils.renderHighlightBox(
                    matrices,
                    cameraState.pos,
                    new double[]{
                            position.getX(),
                            position.getY(),
                            position.getZ()
                    },
                    new double[]{
                            position.getX() + 1.0,
                            position.getY() + 1.0,
                            position.getZ() + 1.0
                    },
                    new float[]{0.3f, 1f, 0.1f, 0.5f}
            );
        }
    }
}
