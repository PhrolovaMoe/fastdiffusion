package moe.phrolova.client;

import java.util.LinkedHashMap;
import java.util.Map;
import moe.phrolova.FastDiffusion;
import moe.phrolova.world.TerrainBiomeSource;
import moe.phrolova.world.TerrainChunkGenerator;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** "Customize" screen of the Diffusion world type: picks the map scale (1:1 to 1:6) and feature generation. */
final class CustomizeDiffusionScreen extends Screen {
    static final int MIN_SCALE = 1, MAX_SCALE = 6;

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final CreateWorldScreen parent;
    private final RegistryAccess registries;
    private int scale;
    private boolean generateOres;
    private boolean generateUndergroundPatches;
    private StringWidget info;

    CustomizeDiffusionScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(Component.translatable("fastdiffusion.customize.title"));
        this.parent = parent;
        this.registries = context.worldgenLoadContext();
        var generator = context.selectedDimensions().overworld();
        this.scale = generator instanceof TerrainChunkGenerator g ? g.scale() : MAX_SCALE;
        this.generateOres = !(generator instanceof TerrainChunkGenerator g) || g.generateOres();
        this.generateUndergroundPatches = !(generator instanceof TerrainChunkGenerator g) || g.generateUndergroundPatches();
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(8));
        content.defaultCellSetting().alignHorizontallyCenter();
        content.addChild(new ScaleSlider());
        info = content.addChild(new StringWidget(310, font.lineHeight, Component.empty(), font));
        content.addChild(new MultiLineTextWidget(Component.translatable("fastdiffusion.customize.hint").withColor(0xA0A0A0), font)
                .setMaxWidth(310).setCentered(true));
        content.addChild(CycleButton.onOffBuilder(generateOres).create(0, 0, 310, 20,
                Component.translatable("fastdiffusion.customize.ores"), (button, value) -> generateOres = value));
        content.addChild(new MultiLineTextWidget(Component.translatable("fastdiffusion.customize.ores.hint").withColor(0xA0A0A0), font)
                .setMaxWidth(310).setCentered(true));
        content.addChild(CycleButton.onOffBuilder(generateUndergroundPatches).create(0, 0, 310, 20,
                Component.translatable("fastdiffusion.customize.underground_patches"),
                (button, value) -> generateUndergroundPatches = value));
        content.addChild(new MultiLineTextWidget(Component.translatable("fastdiffusion.customize.underground_patches.hint").withColor(0xA0A0A0), font)
                .setMaxWidth(310).setCentered(true));
        updateInfo();

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> {
            int chosen = scale;
            boolean ores = generateOres;
            boolean patches = generateUndergroundPatches;
            parent.getUiState().updateDimensions((access, dims) -> withSettings(access, dims, chosen, ores, patches));
            onClose();
        }).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void updateInfo() {
        int height = registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(dimensionType(scale)).height();
        String metres = String.valueOf(30.0f / scale).replaceAll("\\.0$", "");
        info.setMessage(Component.translatable("fastdiffusion.customize.info", metres, height));
    }

    /** Swaps the overworld for a generator and dimension type of the given scale; other dimensions are kept. */
    private static WorldDimensions withSettings(RegistryAccess.Frozen access, WorldDimensions dims, int scale, boolean generateOres, boolean generateUndergroundPatches) {
        ResourceLocation id = id(scale);
        var settings = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, id));
        var type = access.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(dimensionType(scale));
        var generator = new TerrainChunkGenerator(new TerrainBiomeSource(access.lookupOrThrow(Registries.BIOME)), settings, scale, generateOres, generateUndergroundPatches);
        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>(dims.dimensions());
        stems.put(LevelStem.OVERWORLD, new LevelStem(type, generator));
        return new WorldDimensions(stems);
    }

    private static ResourceKey<DimensionType> dimensionType(int scale) {
        return ResourceKey.create(Registries.DIMENSION_TYPE, id(scale));
    }

    private static ResourceLocation id(int scale) {
        return ResourceLocation.fromNamespaceAndPath(FastDiffusion.MODID, "scale_" + scale);
    }

    private final class ScaleSlider extends AbstractSliderButton {
        ScaleSlider() {
            super(0, 0, 310, 20, Component.empty(), toValue(scale));
            updateMessage();
        }

        private static double toValue(int scale) {
            return (scale - MIN_SCALE) / (double) (MAX_SCALE - MIN_SCALE);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("fastdiffusion.customize.scale", scale));
        }

        @Override
        protected void applyValue() {
            scale = MIN_SCALE + (int) Math.round(value * (MAX_SCALE - MIN_SCALE));
            value = toValue(scale);
            updateInfo();
        }
    }
}
