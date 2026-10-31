package pingplus.voicechat.client.gui.glass;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class GlassPipelines {
    public static final RenderPipeline WEATHER = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/weather_glass"))
                    .withVertexShader(id("core/weather_glass"))
                    .withFragmentShader(id("core/weather_glass"))
                    .withCull(false).build());
    public static final RenderPipeline LOGO = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_logo"))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_logo"))
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
    private static final RenderPipeline[] BUTTONS = new RenderPipeline[9];
    private static final RenderPipeline[] EDGELESS = new RenderPipeline[9];
    private static final RenderPipeline[] BLUR_X = new RenderPipeline[9];
    private static final RenderPipeline[] BLUR_Y = new RenderPipeline[9];
    static {
        for (int i = 0; i <= 8; i++) {
            BUTTONS[i] = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_button_" + i))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_button"))
                    .withShaderDefine("SHADOW_SCALE", i * .25f)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
            EDGELESS[i] = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_edgeless_" + i))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_button"))
                    .withShaderDefine("SHADOW_SCALE", i * .25f)
                    .withShaderDefine("NO_EDGES")
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
            BLUR_X[i] = blur("glass_blur_horizontal_" + i, "HORIZONTAL", i * .25f);
            BLUR_Y[i] = blur("glass_blur_vertical_" + i, "VERTICAL", i * .25f);
        }
    }
    public static final RenderPipeline BUTTON = BUTTONS[4];
    public static RenderPipeline button() { return BUTTONS[GlassEffectSettings.shadowStep()]; }
    public static RenderPipeline hud(boolean edges) {
        return edges ? button() : EDGELESS[GlassEffectSettings.shadowStep()];
    }
    public static boolean isButton(RenderPipeline pipeline) {
        for (RenderPipeline button : BUTTONS) if (pipeline == button) return true;
        for (RenderPipeline button : EDGELESS) if (pipeline == button) return true;
        return false;
    }
    public static RenderPipeline blurHorizontal() { return BLUR_X[GlassEffectSettings.blurStep()]; }
    public static RenderPipeline blurVertical() { return BLUR_Y[GlassEffectSettings.blurStep()]; }
    public static final RenderPipeline CONTROL = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_control"))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_control"))
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
    public static final RenderPipeline BAR = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_bar"))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_bar"))
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
    public static final RenderPipeline BOSS_BAR = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(id("pipeline/glass_bossbar"))
                    .withVertexShader(id("core/glass_button"))
                    .withFragmentShader(id("core/glass_bossbar"))
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withCull(false).build());
    public static final RenderPipeline BLUR_HORIZONTAL = BLUR_X[4];
    public static final RenderPipeline BLUR_VERTICAL = BLUR_Y[4];

    private static RenderPipeline blur(String name, String direction, float scale) {
        return RenderPipelines.register(RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/glass_blur"))
                .withShaderDefine(direction)
                .withShaderDefine("BLUR_SCALE", scale)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false).build());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("voicechat", path);
    }

    // Force registration before Minecraft loads and compiles resource-pack shaders.
    public static void initialize() {}
    private GlassPipelines() {}
}
