package emu;

import emu.gb.GbCore;
import emu.nes.NesCore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps ROM file extensions to emulation cores. Adding another system (Game Boy, SNES, ...) means
 * writing one {@link EmulatorCore} and registering it here; nothing in the UI changes.
 */
public final class CoreRegistry {

    public interface Factory {
        EmulatorCore create();
    }

    private static final Map<String, Factory> CORES = new LinkedHashMap<String, Factory>();
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<String, String>();

    private CoreRegistry() {
    }

    public static void register(String extension, String displayName, Factory factory) {
        String key = extension.toLowerCase(Locale.US);
        CORES.put(key, factory);
        DISPLAY_NAMES.put(key, displayName);
    }

    static {
        register("nes", "NES / Famicom", new Factory() {
            @Override
            public EmulatorCore create() {
                return new NesCore();
            }
        });
        register("gb", "Game Boy", new Factory() {
            @Override
            public EmulatorCore create() {
                return new GbCore();
            }
        });
        register("gbc", "Game Boy (DMG mode)", new Factory() {
            @Override
            public EmulatorCore create() {
                return new GbCore();
            }
        });
    }

    /** Case-insensitive lookup by extension, without the dot. Returns null when unsupported. */
    public static EmulatorCore createFor(String extension) {
        Factory factory = CORES.get(extension.toLowerCase(Locale.US));
        return factory == null ? null : factory.create();
    }

    public static EmulatorCore createForFileName(String fileName) {
        String name = fileName.toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return createFor(name.substring(dot + 1));
    }

    public static String coreNameFor(String extension) {
        return DISPLAY_NAMES.get(extension.toLowerCase(Locale.US));
    }

    public static String[] supportedExtensions() {
        return CORES.keySet().toArray(new String[0]);
    }

    public static boolean isSupported(String fileName) {
        return createForFileName(fileName) != null;
    }
}
