package emu;

/**
 * The contract every emulation core in the app implements. A hub (the UI) only ever talks to
 * this interface, so adding another system means adding one implementation and registering it.
 */
public interface EmulatorCore {

    /** Human readable core name, e.g. "NES / Famicom". */
    String name();

    /** Lower-case file extensions this core can load, e.g. {"nes"}. */
    String[] extensions();

    /** Loads a ROM image. Implementations must return the core to a running state. */
    void loadRom(byte[] rom) throws java.io.IOException;

    void reset();

    /** Runs exactly one video frame. */
    void stepFrame();

    int width();

    int height();

    /** Display aspect ratio (width / height) the host should letterbox to. */
    default float aspectRatio() {
        return width() / (float) height();
    }

    /** ARGB pixels, width * height, refreshed by {@link #stepFrame()}. */
    int[] framebuffer();

    int sampleRate();

    /** Drains interleaved mono audio samples; returns the number of samples written. */
    int readAudio(short[] out, int offset, int max);

    /** Controller state, bitmask of the BUTTON_* constants of the concrete core. */
    void setButtons(int port, int mask);

    byte[] saveState();

    void loadState(byte[] state) throws java.io.IOException;
}
