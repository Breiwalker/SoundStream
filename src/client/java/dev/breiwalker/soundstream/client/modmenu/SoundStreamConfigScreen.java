package dev.breiwalker.soundstream.client.modmenu;

import dev.breiwalker.soundstream.client.config.SoundStreamConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public final class SoundStreamConfigScreen extends OptionsSubScreen {
    private static final float[] VOLUME_STEPS = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    private static final int[] PREBUFFER_STEPS = {50, 100, 200, 400};

    private Button enabledButton;
    private Button volumeButton;
    private Button prebufferButton;

    public SoundStreamConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("soundstream.config.title"));
    }

    @Override
    protected void addOptions() {
        SoundStreamConfig config = SoundStreamConfig.get();

        enabledButton = Button.builder(enabledText(config), button -> {
            config.setEnabled(!config.isEnabled());
            config.save();
            button.setMessage(enabledText(config));
        }).width(150).build();

        volumeButton = Button.builder(volumeText(config), button -> {
            config.setVolume(nextVolume(config.getVolume()));
            config.save();
            button.setMessage(volumeText(config));
        }).width(150).build();

        prebufferButton = Button.builder(prebufferText(config), button -> {
            config.setPrebufferMillis(nextPrebuffer(config.getPrebufferMillis()));
            config.save();
            button.setMessage(prebufferText(config));
        }).width(150).build();

        Button resetButton = Button.builder(Component.translatable("soundstream.config.reset"), button -> {
            config.reset();
            config.save();
            refreshLabels(config);
        }).width(150).build();

        list.addHeader(Component.translatable("soundstream.config.hint"));
        list.addSmall(enabledButton, volumeButton);
        list.addSmall(prebufferButton, resetButton);
    }

    private void refreshLabels(SoundStreamConfig config) {
        enabledButton.setMessage(enabledText(config));
        volumeButton.setMessage(volumeText(config));
        prebufferButton.setMessage(prebufferText(config));
    }

    private static Component enabledText(SoundStreamConfig config) {
        return Component.translatable("soundstream.config.enabled",
                Component.translatable(config.isEnabled()
                        ? "soundstream.config.on"
                        : "soundstream.config.off"));
    }

    private static Component volumeText(SoundStreamConfig config) {
        return Component.translatable("soundstream.config.volume",
                Component.literal(Math.round(config.getVolume() * 100.0f) + "%"));
    }

    private static Component prebufferText(SoundStreamConfig config) {
        return Component.translatable("soundstream.config.prebuffer",
                Component.literal(Integer.toString(config.getPrebufferMillis())));
    }

    private static float nextVolume(float current) {
        for (int i = 0; i < VOLUME_STEPS.length; i++) {
            if (current < VOLUME_STEPS[i] - 0.001f) {
                return VOLUME_STEPS[i];
            }
        }
        return VOLUME_STEPS[0];
    }

    private static int nextPrebuffer(int current) {
        for (int step : PREBUFFER_STEPS) {
            if (current < step) {
                return step;
            }
        }
        return PREBUFFER_STEPS[0];
    }
}
