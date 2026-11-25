package emu.gb;

import emu.EmulatorCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** {@link EmulatorCore} implementation for the original monochrome Game Boy (DMG). */
public final class GbCore implements EmulatorCore {

    private static final int STATE_MAGIC = 0x47423031;  // "GB01"

    private GbMachine machine;
    private GbCartridge cartridge;
    private int sampleRate = 44100;

    @Override
    public String name() {
        return "Game Boy";
    }

    @Override
    public String[] extensions() {
        return new String[]{"gb", "gbc"};
    }

    @Override
    public void loadRom(byte[] rom) throws IOException {
        GbCartridge parsed = GbCartridge.load(rom);
        if (parsed.colorOnly) {
            throw new IOException("Game Boy Color exclusive game: only DMG-compatible games run on this core");
        }
        machine = new GbMachine(parsed);
        machine.apu.setSampleRate(sampleRate);
        cartridge = parsed;
    }

    public String cartridgeInfo() {
        return cartridge == null ? "no cartridge" : cartridge.summary();
    }

    public GbCartridge cartridge() {
        return cartridge;
    }

    public GbMachine machine() {
        return machine;
    }

    @Override
    public void reset() {
        if (machine != null) {
            machine.reset();
        }
    }

    @Override
    public void stepFrame() {
        if (machine != null) {
            machine.stepFrame();
        }
    }

    @Override
    public int width() {
        return GbPpu.WIDTH;
    }

    @Override
    public int height() {
        return GbPpu.HEIGHT;
    }

    @Override
    public int[] framebuffer() {
        return machine == null ? new int[0] : machine.ppu.framebuffer;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int rate) {
        sampleRate = rate;
        if (machine != null) {
            machine.apu.setSampleRate(rate);
        }
    }

    @Override
    public int readAudio(short[] out, int offset, int max) {
        return machine == null ? 0 : machine.apu.readAudio(out, offset, max);
    }

    @Override
    public void setButtons(int port, int mask) {
        if (machine != null) {
            machine.setButtons(port, mask);
        }
    }

    @Override
    public byte[] saveState() {
        if (machine == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(STATE_MAGIC);
            out.writeInt(1);
            machine.saveState(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write save state", e);
        }
    }

    @Override
    public void loadState(byte[] state) throws IOException {
        if (machine == null) {
            throw new IOException("no game loaded");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        if (in.readInt() != STATE_MAGIC) {
            throw new IOException("not a save state for this core");
        }
        int version = in.readInt();
        if (version != 1) {
            throw new IOException("unsupported save state version " + version);
        }
        machine.loadState(in);
    }
}
