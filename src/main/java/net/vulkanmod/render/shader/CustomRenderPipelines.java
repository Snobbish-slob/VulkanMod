package net.vulkanmod.render.shader;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;

public class CustomRenderPipelines {
    public static final List<RenderPipeline> pipelines = new ArrayList<>();

    public static final RenderPipeline.Snippet GUI_TRIANGLES_SNIPPET = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                                                                           .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                                           .buildSnippet();

    public static final RenderPipeline GUI_TRIANGLES = register(RenderPipeline.builder(GUI_TRIANGLES_SNIPPET).withLocation("pipeline/gui").build());

    static RenderPipeline register(RenderPipeline pipeline) {
        pipelines.add(pipeline);
        return pipeline;
    }
}
