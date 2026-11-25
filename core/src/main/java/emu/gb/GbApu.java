package emu.gb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * DMG audio: two pulse channels (one with frequency sweep), the wave channel and the noise
 * channel, driven by the 512 Hz frame sequencer and resampled to the requested output rate.
 */
public final class GbApu {

    private static final int[] DUTY = {0x01, 0x81, 0x87, 0x7E};
    private static final int[] NOISE_DIVISORS = {8, 16, 32, 48, 64, 80, 96, 112};

    private static final class Channel {
        boolean enabled;
        boolean dac;
        int duty;
        int dutyPos;
        int timer;
        int length;
        boolean lengthEnabled;
        int volume;
        int envelopePeriod;
        int envelopeDirection;
        int envelopeTimer;
        int frequency;
        int shadowFrequency;
        int sweepPeriod;
        int sweepDirection;
        int sweepShift;
        int sweepTimer;
        boolean sweepEnabled;
        int noiseLfsr = 0x7FFF;
    }

    private final Channel ch1 = new Channel();
    private final Channel ch2 = new Channel();
    private final Channel ch3 = new Channel();
    private final Channel ch4 = new Channel();

    private final byte[] waveRam = new byte[16];
    private int nr50 = 0x77;
    private int nr51 = 0xF3;
    private boolean powered = true;
    private boolean waveDac;

    private int frameTimer;
    private int frameStep;
    private double sampleCounter;
    private int sampleRate = 44100;
    private double cyclesPerSample = 4194304.0 / 44100.0;

    private short[] buffer = new short[8192];
    private int readIndex;
    private int writeIndex;
    private int length;

    public void reset() {
        ch1.enabled = ch2.enabled = ch3.enabled = ch4.enabled = false;
        ch1.dac = ch2.dac = ch3.dac = ch4.dac = false;
        ch1.dutyPos = ch2.dutyPos = ch3.dutyPos = ch4.dutyPos = 0;
        ch1.volume = ch2.volume = ch4.volume = 0;
        ch1.length = ch2.length = ch3.length = ch4.length = 0;
        ch1.frequency = ch2.frequency = ch3.frequency = 0;
        ch1.timer = ch2.timer = ch3.timer = ch4.timer = 0;
        ch4.noiseLfsr = 0x7FFF;
        nr50 = 0x77;
        nr51 = 0xF3;
        powered = true;
        waveDac = false;
        frameTimer = 0;
        frameStep = 0;
        sampleCounter = 0;
        readIndex = 0;
        writeIndex = 0;
        length = 0;
    }

    public void setSampleRate(int rate) {
        if (rate > 8000) {
            sampleRate = rate;
            cyclesPerSample = 4194304.0 / rate;
        }
    }

    int readRegister(int address) {
        switch (address) {
            case 0xFF26:
                int value = 0x70;
                if (powered) {
                    value |= 0x80;
                    value |= ch1.enabled ? 0x01 : 0;
                    value |= ch2.enabled ? 0x02 : 0;
                    value |= ch3.enabled ? 0x04 : 0;
                    value |= ch4.enabled ? 0x08 : 0;
                }
                return value;
            case 0xFF30: case 0xFF31: case 0xFF32: case 0xFF33:
            case 0xFF34: case 0xFF35: case 0xFF36: case 0xFF37:
            case 0xFF38: case 0xFF39: case 0xFF3A: case 0xFF3B:
            case 0xFF3C: case 0xFF3D: case 0xFF3E: case 0xFF3F:
                return waveRam[address - 0xFF30] & 0xFF;
            default:
                return 0xFF;  // write-only registers read as $FF on DMG
        }
    }

    public void writeRegister(int address, int value) {
        if (address >= 0xFF30 && address <= 0xFF3F) {
            waveRam[address - 0xFF30] = (byte) value;
            return;
        }
        if (!powered && address != 0xFF26) {
            return;  // while the APU is off only NR52 answers
        }
        switch (address) {
            case 0xFF10:  // NR10 sweep
                ch1.sweepPeriod = (value >> 4) & 0x07;
                ch1.sweepDirection = (value >> 3) & 0x01;
                ch1.sweepShift = value & 0x07;
                if (ch1.sweepShift > 0 || ch1.sweepPeriod > 0) {
                    ch1.sweepEnabled = true;
                }
                break;
            case 0xFF11:  // NR11 duty + length
                ch1.dutyPos = 0;
                ch1.length = 64 - (value & 0x3F);
                ch1.duty = (value >> 6) & 0x03;
                break;
            case 0xFF12:  // NR12 envelope
                ch1.volume = (value >> 4) & 0x0F;
                ch1.envelopeDirection = (value >> 3) & 0x01;
                ch1.envelopePeriod = value & 0x07;
                ch1.dac = (value & 0xF8) != 0;
                if (!ch1.dac) {
                    ch1.enabled = false;
                }
                break;
            case 0xFF13:
                ch1.frequency = (ch1.frequency & 0x700) | value;
                break;
            case 0xFF14:
                ch1.frequency = (ch1.frequency & 0xFF) | ((value & 0x07) << 8);
                ch1.lengthEnabled = (value & 0x40) != 0;
                if ((value & 0x80) != 0) {
                    trigger(ch1);
                }
                break;
            case 0xFF16:
                ch2.dutyPos = 0;
                ch2.length = 64 - (value & 0x3F);
                ch2.duty = (value >> 6) & 0x03;
                break;
            case 0xFF17:
                ch2.volume = (value >> 4) & 0x0F;
                ch2.envelopeDirection = (value >> 3) & 0x01;
                ch2.envelopePeriod = value & 0x07;
                ch2.dac = (value & 0xF8) != 0;
                if (!ch2.dac) {
                    ch2.enabled = false;
                }
                break;
            case 0xFF18:
                ch2.frequency = (ch2.frequency & 0x700) | value;
                break;
            case 0xFF19:
                ch2.frequency = (ch2.frequency & 0xFF) | ((value & 0x07) << 8);
                ch2.lengthEnabled = (value & 0x40) != 0;
                if ((value & 0x80) != 0) {
                    trigger(ch2);
                }
                break;
            case 0xFF1A:
                waveDac = (value & 0x80) != 0;
                if (!waveDac) {
                    ch3.enabled = false;
                }
                break;
            case 0xFF1B:
                ch3.length = 256 - value;
                break;
            case 0xFF1C:
                ch3.volume = (value >> 5) & 0x03;
                break;
            case 0xFF1D:
                ch3.frequency = (ch3.frequency & 0x700) | value;
                break;
            case 0xFF1E:
                ch3.frequency = (ch3.frequency & 0xFF) | ((value & 0x07) << 8);
                ch3.lengthEnabled = (value & 0x40) != 0;
                if ((value & 0x80) != 0) {
                    trigger(ch3);
                }
                break;
            case 0xFF20:
                ch4.length = 64 - (value & 0x3F);
                break;
            case 0xFF21:
                ch4.volume = (value >> 4) & 0x0F;
                ch4.envelopeDirection = (value >> 3) & 0x01;
                ch4.envelopePeriod = value & 0x07;
                ch4.dac = (value & 0xF8) != 0;
                if (!ch4.dac) {
                    ch4.enabled = false;
                }
                break;
            case 0xFF22:
                ch4.frequency = value;
                break;
            case 0xFF23:
                ch4.lengthEnabled = (value & 0x40) != 0;
                if ((value & 0x80) != 0) {
                    trigger(ch4);
                }
                break;
            case 0xFF24:
                nr50 = value;
                break;
            case 0xFF25:
                nr51 = value;
                break;
            case 0xFF26:
                boolean on = (value & 0x80) != 0;
                if (!on) {
                    ch1.enabled = ch2.enabled = ch3.enabled = ch4.enabled = false;
                    nr50 = 0;
                    nr51 = 0;
                }
                powered = on;
                break;
            default:
                break;
        }
    }

    private void trigger(Channel channel) {
        channel.enabled = channel.dac;
        channel.timer = (2048 - channel.frequency) * 4;
        if (channel.timer <= 0) {
            channel.timer = 4;
        }
        if (channel.length == 0) {
            channel.length = 64;
        }
        channel.envelopeTimer = channel.envelopePeriod;
        if (channel == ch1) {
            channel.shadowFrequency = channel.frequency;
            channel.sweepTimer = channel.sweepPeriod == 0 ? 8 : channel.sweepPeriod;
            channel.sweepEnabled = channel.sweepPeriod > 0 || channel.sweepShift > 0;
        }
        if (channel == ch3) {
            channel.dutyPos = 0;
        }
        if (channel == ch4) {
            channel.noiseLfsr = 0x7FFF;
        }
    }

    public void tick(int cycles) {
        frameTimer += cycles;
        while (frameTimer >= 8192) {
            frameTimer -= 8192;
            frameStep = (frameStep + 1) & 7;
            frameTick();
        }

        stepChannel(ch1, cycles, true);
        stepChannel(ch2, cycles, false);
        stepChannel(ch3, cycles, false);
        stepChannel(ch4, cycles, false);

        sampleCounter += cycles;
        while (sampleCounter >= cyclesPerSample) {
            sampleCounter -= cyclesPerSample;
            pushSample();
        }
    }

    private void frameTick() {
        switch (frameStep) {
            case 0:
                lengthTick(ch1);
                lengthTick(ch2);
                break;
            case 2:
                sweepTick();
                lengthTick(ch3);
                break;
            case 4:
                lengthTick(ch4);
                break;
            case 6:
                sweepTick();
                break;
            case 7:
                envelopeTick(ch1);
                envelopeTick(ch2);
                envelopeTick(ch4);
                break;
            default:
                break;
        }
    }

    private void lengthTick(Channel channel) {
        if (!channel.lengthEnabled || channel.length == 0) {
            return;
        }
        channel.length--;
        if (channel.length == 0) {
            channel.enabled = false;
        }
    }

    private void envelopeTick(Channel channel) {
        if (channel.envelopePeriod == 0) {
            return;
        }
        if (channel.envelopeTimer > 0) {
            channel.envelopeTimer--;
        }
        if (channel.envelopeTimer == 0) {
            channel.envelopeTimer = channel.envelopePeriod;
            if (channel.envelopeDirection == 1 && channel.volume < 15) {
                channel.volume++;
            } else if (channel.envelopeDirection == 0 && channel.volume > 0) {
                channel.volume--;
            }
        }
    }

    private void sweepTick() {
        if (!ch1.sweepEnabled || ch1.sweepPeriod == 0) {
            return;
        }
        if (ch1.sweepTimer > 0) {
            ch1.sweepTimer--;
        }
        if (ch1.sweepTimer != 0) {
            return;
        }
        ch1.sweepTimer = ch1.sweepPeriod;
        int delta = ch1.shadowFrequency >> ch1.sweepShift;
        int next = ch1.sweepDirection == 1 ? ch1.shadowFrequency - delta : ch1.shadowFrequency + delta;
        if (next > 2047 || next < 0) {
            ch1.enabled = false;
            return;
        }
        if (ch1.sweepShift > 0) {
            ch1.shadowFrequency = next;
            ch1.frequency = next;
        }
    }

    private void stepChannel(Channel channel, int cycles, boolean sweep) {
        if (!channel.enabled) {
            return;
        }
        if (channel == ch4) {
            int divisor = NOISE_DIVISORS[channel.frequency & 0x07];
            int shift = (channel.frequency >> 4) & 0x0F;
            int period = divisor << shift;
            channel.timer -= cycles;
            while (channel.timer <= 0) {
                channel.timer += Math.max(1, period);
                int bit = ((channel.noiseLfsr ^ (channel.noiseLfsr >> 1)) & 1);
                channel.noiseLfsr = (channel.noiseLfsr >> 1) | (bit << 14);
                if ((channel.frequency & 0x08) != 0) {
                    channel.noiseLfsr = (channel.noiseLfsr & ~0x40) | (bit << 6);
                }
            }
            return;
        }
        channel.timer -= cycles;
        while (channel.timer <= 0) {
            channel.timer += Math.max(4, (2048 - channel.frequency) * 4);
            channel.dutyPos = (channel.dutyPos + 1) & 7;
        }
    }

    private int channelOutput(Channel channel, boolean square) {
        if (!channel.enabled || !channel.dac) {
            return 0;
        }
        if (square) {
            int pattern = DUTY[channel.duty & 3];
            return ((pattern >> channel.dutyPos) & 1) != 0 ? channel.volume : 0;
        }
        if (channel == ch3) {
            int volumeCode = channel.volume;
            if (volumeCode == 0) {
                return 0;
            }
            int shift = volumeCode == 1 ? 0 : volumeCode - 1;
            int sample = waveRam[(channel.dutyPos >> 2) & 15] & 0xFF;
            int nibble = (channel.dutyPos & 1) == 0 ? (sample >> 4) : (sample & 0x0F);
            return nibble >> shift;
        }
        return (channel.noiseLfsr & 1) != 0 ? 0 : channel.volume;
    }

    private void pushSample() {
        int amplitude;
        if (!powered) {
            amplitude = 0;
        } else {
            double mix = 0;
            mix += centered(channelOutput(ch1, true), (nr51 & 0x01) != 0 || (nr51 & 0x10) != 0);
            mix += centered(channelOutput(ch2, true), (nr51 & 0x02) != 0 || (nr51 & 0x20) != 0);
            mix += centered(channelOutput(ch3, false), (nr51 & 0x04) != 0 || (nr51 & 0x40) != 0);
            mix += centered(channelOutput(ch4, false), (nr51 & 0x08) != 0 || (nr51 & 0x80) != 0);
            int volume = Math.max(nr50 & 0x07, (nr50 >> 4) & 0x07);
            amplitude = (int) (mix / 4.0 * (volume / 7.0) * 12000);
        }
        if (amplitude > Short.MAX_VALUE) {
            amplitude = Short.MAX_VALUE;
        } else if (amplitude < Short.MIN_VALUE) {
            amplitude = Short.MIN_VALUE;
        }
        if (length == buffer.length) {
            short[] grown = new short[buffer.length * 2];
            for (int i = 0; i < length; i++) {
                grown[i] = buffer[(readIndex + i) % buffer.length];
            }
            buffer = grown;
            readIndex = 0;
            writeIndex = length;
        }
        buffer[writeIndex] = (short) amplitude;
        writeIndex = (writeIndex + 1) % buffer.length;
        length++;
    }

    private static double centered(int value, boolean routed) {
        return routed ? (value - 7.5) : 0;
    }

    public int readAudio(short[] out, int offset, int max) {
        int count = Math.min(max, length);
        for (int i = 0; i < count; i++) {
            out[offset + i] = buffer[readIndex];
            readIndex = (readIndex + 1) % buffer.length;
        }
        length -= count;
        return count;
    }

    void saveState(DataOutputStream out) throws IOException {
        for (Channel channel : new Channel[]{ch1, ch2, ch3, ch4}) {
            out.writeBoolean(channel.enabled);
            out.writeBoolean(channel.dac);
            out.writeInt(channel.dutyPos);
            out.writeInt(channel.timer);
            out.writeInt(channel.length);
            out.writeBoolean(channel.lengthEnabled);
            out.writeInt(channel.volume);
            out.writeInt(channel.envelopePeriod);
            out.writeInt(channel.envelopeDirection);
            out.writeInt(channel.envelopeTimer);
            out.writeInt(channel.frequency);
            out.writeInt(channel.shadowFrequency);
            out.writeInt(channel.sweepPeriod);
            out.writeInt(channel.sweepDirection);
            out.writeInt(channel.sweepShift);
            out.writeInt(channel.sweepTimer);
            out.writeBoolean(channel.sweepEnabled);
            out.writeInt(channel.noiseLfsr);
        }
        out.write(waveRam);
        out.writeInt(nr50);
        out.writeInt(nr51);
        out.writeBoolean(powered);
        out.writeBoolean(waveDac);
        out.writeInt(frameTimer);
        out.writeInt(frameStep);
        out.writeDouble(sampleCounter);
    }

    void loadState(DataInputStream in) throws IOException {
        Channel[] channels = {ch1, ch2, ch3, ch4};
        for (Channel channel : channels) {
            channel.enabled = in.readBoolean();
            channel.dac = in.readBoolean();
            channel.dutyPos = in.readInt();
            channel.timer = in.readInt();
            channel.length = in.readInt();
            channel.lengthEnabled = in.readBoolean();
            channel.volume = in.readInt();
            channel.envelopePeriod = in.readInt();
            channel.envelopeDirection = in.readInt();
            channel.envelopeTimer = in.readInt();
            channel.frequency = in.readInt();
            channel.shadowFrequency = in.readInt();
            channel.sweepPeriod = in.readInt();
            channel.sweepDirection = in.readInt();
            channel.sweepShift = in.readInt();
            channel.sweepTimer = in.readInt();
            channel.sweepEnabled = in.readBoolean();
            channel.noiseLfsr = in.readInt();
        }
        in.readFully(waveRam);
        nr50 = in.readInt();
        nr51 = in.readInt();
        powered = in.readBoolean();
        waveDac = in.readBoolean();
        frameTimer = in.readInt();
        frameStep = in.readInt();
        sampleCounter = in.readDouble();
        readIndex = 0;
        writeIndex = 0;
        length = 0;
    }
}
