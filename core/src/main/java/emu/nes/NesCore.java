package emu.nes;

import emu.EmulatorCore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** {@link EmulatorCore} implementation for the NES / Famicom. */
public final class NesCore implements EmulatorCore {

    private static final int STATE_MAGIC = 0x4E455331; // "NES1"

    private NesMachine machine;
    private Cartridge cartridge;
    private int sampleRate = 44100;

    @Override
    public String name() {
        return "NES / Famicom";
    }

    @Override
    public String[] extensions() {
        return new String[]{"nes"};
    }

    @Override
    public void loadRom(byte[] rom) throws IOException {
        Cartridge parsed = Cartridge.load(rom);
        machine = new NesMachine(parsed);
        machine.apu.setSampleRate(sampleRate);
        cartridge = parsed;
    }

    public String cartridgeInfo() {
        return cartridge == null ? "no cartridge" : cartridge.summary();
    }

    public Cartridge cartridge() {
        return cartridge;
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
        return Ppu.WIDTH;
    }

    @Override
    public int height() {
        return Ppu.HEIGHT;
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
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
