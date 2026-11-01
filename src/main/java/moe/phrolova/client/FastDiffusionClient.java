package moe.phrolova.client;

import moe.phrolova.FastDiffusion;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Config button in the mod list, the world type's "Customize" screen and the model loading screen. */
@Mod(value = FastDiffusion.MODID, dist = Dist.CLIENT)
public final class FastDiffusionClient {
    public FastDiffusionClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener((RegisterPresetEditorsEvent event) -> event.register(
                ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(FastDiffusion.MODID, "diffusion")),
                CustomizeDiffusionScreen::new));
        NeoForge.EVENT_BUS.addListener(ModelLoadingOverlay::onRender);
    }
}
