package com.viby.core.playback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.settings.Settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class EqualizerControllerTest {

    @TempDir
    Path temp;

    @Test
    void persistsStateAndAppliesItOnLoad() {
        Settings settings = new Settings(temp.resolve("settings.json"), temp);
        Equalizer first = new Equalizer(48_000, 2);
        EqualizerController controller = new EqualizerController(first, settings);
        controller.setEnabled(true);
        controller.applyPreset("Rock");
        controller.setPreamp(-3);
        assertEquals("Rock", controller.preset());

        Equalizer second = new Equalizer(48_000, 2);
        EqualizerController reloaded = new EqualizerController(second,
                new Settings(temp.resolve("settings.json"), temp));
        assertTrue(reloaded.isEnabled() && second.isEnabled());
        assertEquals("Rock", reloaded.preset());
        assertArrayEquals(EqPresets.curve("Rock"), second.gains());
        assertEquals(-3, second.preamp());
    }

    @Test
    void manualEditsBecomeCustomAndCanBeSavedAsPreset() {
        Settings settings = new Settings(temp.resolve("settings.json"), temp);
        EqualizerController controller = new EqualizerController(new Equalizer(48_000, 2), settings);
        controller.setGain(3, 7.5f, false);
        assertEquals(EqualizerController.PRESET_CUSTOM, controller.preset());
        controller.saveCurrentAsPreset("Мой");
        assertTrue(controller.presetNames().contains("Мой"));
        assertTrue(controller.isCustomPreset("Мой"));
        assertFalse(controller.isCustomPreset("Rock"));
        assertThrows(IllegalArgumentException.class, () -> controller.saveCurrentAsPreset("Rock"));

        controller.applyPreset("Zero");
        controller.applyPreset("Мой");
        assertEquals(7.5f, controller.gain(3));
        controller.deletePreset("Мой");
        assertFalse(controller.presetNames().contains("Мой"));
    }
}
