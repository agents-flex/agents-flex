package com.agentsflex.image.siliconflow;

import com.agentsflex.core.util.Maps;

import java.util.Map;

public class SiliconflowImageModels {


    /**
     * 由 Black Forest Labs 开发的 120 亿参数文生图模型，采用潜在对抗扩散蒸馏技术，能够在 1 到 4 步内生成高质量图像。该模型性能媲美闭源替代品，并在 Apache-2.0 许可证下发布，适用于个人、科研和商业用途。
     */
    public static final String flux_1_schnell = "FLUX.1-schnell";

    /**
     * 由 Stability AI 开发并开源的文生图大模型，其创意图像生成能力位居行业前列。具备出色的指令理解能力，能够支持反向 Prompt 定义来精确生成内容。
     */
    public static final String Stable_Diffusion_3 = "Stable Diffusion 3";
    public static final String Stable_Diffusion_XL = "Stable Diffusion XL";
    public static final String Stable_Diffusion_2_1 = "Stable Diffusion 2.1";
    public static final String Stable_Diffusion_Turbo = "Stable Diffusion Turbo";
    public static final String Stable_Diffusion_XL_Turbo = "Stable Diffusion XL Turbo";
    public static final String Stable_Diffusion_XL_Lighting = "Stable Diffusion XL Lighting";


    private static Map<String, Object> modelsPathMapping = Maps
        .of(flux_1_schnell, "/v1/black-forest-labs/FLUX.1-schnell/text-to-image")
        .put(Stable_Diffusion_3, "/v1/stabilityai/stable-diffusion-3-medium/text-to-image")
        .put(Stable_Diffusion_XL, "/v1/stabilityai/stable-diffusion-xl-base-1.0/text-to-image")
        .put(Stable_Diffusion_2_1, "/v1/stabilityai/stable-diffusion-2-1/text-to-image")
        .put(Stable_Diffusion_Turbo, "/v1/stabilityai/sd-turbo/text-to-image")
        .put(Stable_Diffusion_XL_Turbo, "/v1/stabilityai/sdxl-turbo/text-to-image")
        .put(Stable_Diffusion_XL_Lighting, "/v1/ByteDance/SDXL-Lightning/text-to-image")
        ;

    public static String getPath(String model) {
        return (String) modelsPathMapping.get(model);
    }
}
