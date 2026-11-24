package emu.nes;

/**
 * 2A03 APU: two pulse channels, triangle, noise. DMC is intentionally not implemented
 * (a handful of games use it for sampled drums); everything else produces sound.
 *
 * <p>Channels are clocked with cycle counters rather than per-cycle loops so that audio costs
 * a tiny fraction of emulation time on a phone.
 */
public final class Apu {

    public static final int CPU_HZ = 1789773;

    private static final int[][] DUTY = {
            {0, 1, 0, 0, 0, 0, 0, 0},
            {0, 1, 1, 0, 0, 0, 0, 0},
            {0, 1, 1, 1, 1, 0, 0, 0},
            {1, 0, 0, 1, 1, 1, 1, 1}
    };

    private static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };

    private static final int[] NOISE_PERIODS = {
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    private static final int[] TRIANGLE_SEQ = {
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private final int[] reg = new int[0x18];

    // pulse channels: index 0 = pulse 1, 1 = pulse 2
    private final int[] pulseTimer = new int[2];
    private final int[] pulseSeq = new int[2];
    private final int[] pulseLength = new int[2];
    private final int[] pulseEnvDivider = new int[2];
    private final int[] pulseEnvVolume = new int[2];
    private final boolean[] pulseEnvStart = new boolean[2];
    private final int[] pulseSweepDivider = new int[2];
    private final boolean[] pulseSweepReload = new boolean[2];

    private int triTimer, triSeq, triLength, triLinear, triLinearReload;
    private boolean triLinearReloadFlag;

    private int noiseTimer, noiseShift = 1, noiseLength, noiseEnvDivider, noiseEnvVolume;
    private boolean noiseEnvStart;

    private final int[] lengthCounter = new int[4]; // p1, p2, tri, noise

    private int frameCycle, quarterCount;
    private boolean fiveStepMode;
    private boolean frameIrq;
    private boolean irqInhibit;
    private boolean channelEnabledPulse1, channelEnabledPulse2, channelEnabledTriangle, channelEnabledNoise;

    private int sampleRate = 44100;
    private double cyclesPerSample = (double) CPU_HZ / 44100.0;
    private double sampleAccumulator;
    private final short[] buffer = new short[16384];
    private int writePos, readPos, count;

    public void reset() {
        java.util.Arrays.fill(reg, 0);
        java.util.Arrays.fill(pulseTimer, 0);
        java.util.Arrays.fill(pulseSeq, 0);
        java.util.Arrays.fill(pulseLength, 0);
        java.util.Arrays.fill(pulseEnvDivider, 0);
        java.util.Arrays.fill(pulseEnvVolume, 0);
        java.util.Arrays.fill(pulseEnvStart, false);
        java.util.Arrays.fill(pulseSweepDivider, 0);
        java.util.Arrays.fill(pulseSweepReload, false);
        java.util.Arrays.fill(lengthCounter, 0);
        triTimer = triSeq = triLength = triLinear = 0;
        triLinearReload = 0;
        triLinearReloadFlag = false;
        noiseTimer = 0;
        noiseShift = 1;
        noiseLength = 0;
        noiseEnvDivider = noiseEnvVolume = 0;
        noiseEnvStart = false;
        frameCycle = 0;
        quarterCount = 0;
        fiveStepMode = false;
        frameIrq = false;
        irqInhibit = false;
        channelEnabledPulse1 = channelEnabledPulse2 = channelEnabledTriangle = channelEnabledNoise = false;
        sampleAccumulator = 0;
        writePos = readPos = count = 0;
    }

    public void setSampleRate(int rate) {
        if (rate <= 0) {
            return;
        }
        sampleRate = rate;
        cyclesPerSample = (double) CPU_HZ / rate;
        count = 0;
        writePos = readPos = 0;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean frameIrq() {
        return frameIrq;
    }

    // ------------------------------------------------------------ registers

    public int read(int address) {
        if ((address & 0x1F) == 0x15) {
            int value = 0;
            if (lengthCounter[0] > 0) value |= 0x01;
            if (lengthCounter[1] > 0) value |= 0x02;
            if (triLength > 0) value |= 0x04;
            if (noiseLength > 0) value |= 0x08;
            if (frameIrq) value |= 0x40;
            frameIrq = false;
            return value;
        }
        return 0;
    }

    public void write(int address, int value) {
        int a = address & 0x1F;
        if (a < 0x18) {
            reg[a] = value;
        }
        switch (a) {
            case 0x00: {
                int duty = (value >> 6) & 3;
                reg[0] = (reg[0] & 0x3F) | (duty << 6);
                pulseSeq[0] = 0;
                if ((value & 0x20) == 0) {
                    // length counter not halted
                }
                break;
            }
            case 0x01:
                pulseSweepReload[0] = true;
                break;
            case 0x02:
                pulseTimer[0] = (pulseTimer[0] & ~0xFF) | value; // low bits kept for period
                break;
            case 0x03:
                pulseTimer[0] = 0; // period is recomputed from regs each step
                pulseSeq[0] = 0;
                if (channelEnabledPulse1) {
                    lengthCounter[0] = LENGTH_TABLE[(value >> 3) & 0x1F];
                }
                pulseEnvStart[0] = true;
                break;
            case 0x04:
                pulseSeq[1] = 0;
                break;
            case 0x05:
                pulseSweepReload[1] = true;
                break;
            case 0x06:
                break;
            case 0x07:
                pulseTimer[1] = 0;
                pulseSeq[1] = 0;
                if (channelEnabledPulse2) {
                    lengthCounter[1] = LENGTH_TABLE[(value >> 3) & 0x1F];
                }
                pulseEnvStart[1] = true;
                break;
            case 0x08:
                triLinearReload = value & 0x7F;
                if ((value & 0x80) != 0 && channelEnabledTriangle) {
                    triLength = LENGTH_TABLE[value & 0x1F];
                }
                break;
            case 0x0A:
                break;
            case 0x0B:
                triTimer = 0;
                triSeq = 0;
                triLinearReloadFlag = true;
                if (channelEnabledTriangle) {
                    triLength = LENGTH_TABLE[(value >> 3) & 0x1F];
                }
                break;
            case 0x0C:
                break;
            case 0x0E:
                noiseEnvStart = true;
                break;
            case 0x0F:
                noiseTimer = 0;
                if (channelEnabledNoise) {
                    noiseLength = LENGTH_TABLE[(value >> 3) & 0x1F];
                }
                noiseEnvStart = true;
                break;
            case 0x15: {
                channelEnabledPulse1 = (value & 0x01) != 0;
                channelEnabledPulse2 = (value & 0x02) != 0;
                channelEnabledTriangle = (value & 0x04) != 0;
                channelEnabledNoise = (value & 0x08) != 0;
                if (!channelEnabledPulse1) lengthCounter[0] = 0;
                if (!channelEnabledPulse2) lengthCounter[1] = 0;
                if (!channelEnabledTriangle) triLength = 0;
                if (!channelEnabledNoise) noiseLength = 0;
                break;
            }
            case 0x17:
                fiveStepMode = (value & 0x80) != 0;
                irqInhibit = (value & 0x40) != 0;
                if (irqInhibit) {
                    frameIrq = false;
                }
                frameCycle = 0;
                quarterCount = 0;
                if (fiveStepMode) {
                    quarterFrame();
                    halfFrame();
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ clocking

    public void step(int cpuCycles) {
        if (cpuCycles <= 0) {
            return;
        }
        for (int ch = 0; ch < 2; ch++) {
            int period = pulsePeriod(ch);
            pulseTimer[ch] -= cpuCycles;
            if (pulseTimer[ch] < 0) {
                pulseTimer[ch] = 0;
            }
            while (pulseTimer[ch] <= 0) {
                pulseTimer[ch] += Math.max(2, period);
                pulseSeq[ch] = (pulseSeq[ch] + 1) & 7;
            }
        }

        int triPeriod = Math.max(2, trianglePeriod());
        triTimer -= cpuCycles;
        if (triTimer < 0) triTimer = 0;
        while (triTimer <= 0) {
            triTimer += triPeriod;
            triSeq = (triSeq + 1) & 31;
        }

        int noisePeriod = Math.max(4, NOISE_PERIODS[reg[0x0E] & 0x0F]);
        noiseTimer -= cpuCycles;
        if (noiseTimer < 0) noiseTimer = 0;
        while (noiseTimer <= 0) {
            noiseTimer += noisePeriod;
            int feedback;
            if ((reg[0x0E] & 0x80) != 0) {
                feedback = ((noiseShift >> 6) ^ (noiseShift >> 1)) & 1;
            } else {
                feedback = ((noiseShift >> 1) ^ noiseShift) & 1;
            }
            noiseShift = (noiseShift >> 1) | (feedback << 14);
        }

        frameCycle += cpuCycles;
        while (frameCycle >= 7457) {
            frameCycle -= 7457;
            quarterFrame();
            quarterCount++;
            if ((quarterCount & 1) == 0) {
                halfFrame();
            }
            if (quarterCount >= 4) {
                quarterCount = 0;
                if (!fiveStepMode && !irqInhibit) {
                    frameIrq = true;
                }
            }
        }

        sampleAccumulator += cpuCycles;
        while (sampleAccumulator >= cyclesPerSample) {
            sampleAccumulator -= cyclesPerSample;
            pushSample();
        }
    }

    private int pulsePeriod(int ch) {
        int timer = ((reg[0x03 + ch * 4] & 0x07) << 8) | reg[0x02 + ch * 4];
        return 2 * (timer + 1);
    }

    private int trianglePeriod() {
        int timer = ((reg[0x0B] & 0x07) << 8) | reg[0x0A];
        return timer + 1;
    }

    private void quarterFrame() {
        for (int ch = 0; ch < 2; ch++) {
            envelopeClock(ch);
        }
        envelopeClock(2);
        if (triLinearReloadFlag) {
            triLinear = triLinearReload;
        } else if (triLinear > 0) {
            triLinear--;
        }
        if ((reg[0x08] & 0x80) == 0) {
            triLinearReloadFlag = false;
        }
    }

    private void envelopeClock(int channel) {
        int regIndex = channel == 0 ? 0x00 : channel == 1 ? 0x04 : 0x0C;
        boolean start = channel == 0 ? pulseEnvStart[0] : channel == 1 ? pulseEnvStart[1] : noiseEnvStart;
        int divider = channel == 0 ? pulseEnvDivider[0] : channel == 1 ? pulseEnvDivider[1] : noiseEnvDivider;
        int volume = channel == 0 ? pulseEnvVolume[0] : channel == 1 ? pulseEnvVolume[1] : noiseEnvVolume;
        int period = reg[regIndex] & 0x0F;

        if (start) {
            start = false;
            volume = 15;
            divider = period;
        } else if (divider == 0) {
            divider = period;
            if (volume > 0) {
                volume--;
            } else if ((reg[regIndex] & 0x20) != 0) {
                volume = 15;
            }
        } else {
            divider--;
        }

        if (channel == 0) {
            pulseEnvStart[0] = start;
            pulseEnvDivider[0] = divider;
            pulseEnvVolume[0] = volume;
        } else if (channel == 1) {
            pulseEnvStart[1] = start;
            pulseEnvDivider[1] = divider;
            pulseEnvVolume[1] = volume;
        } else {
            noiseEnvStart = start;
            noiseEnvDivider = divider;
            noiseEnvVolume = volume;
        }
    }

    private void halfFrame() {
        for (int ch = 0; ch < 2; ch++) {
            if ((reg[0x00 + ch * 4] & 0x20) == 0 && lengthCounter[ch] > 0) {
                lengthCounter[ch]--;
            }
            sweepClock(ch);
        }
        if ((reg[0x08] & 0x80) == 0 && triLength > 0) {
            triLength--;
        }
        if ((reg[0x0C] & 0x20) == 0 && noiseLength > 0) {
            noiseLength--;
        }
    }

    private void sweepClock(int ch) {
        int sweepReg = reg[0x01 + ch * 4];
        int shift = sweepReg & 0x07;
        int period = (sweepReg >> 4) & 0x07;

        if (pulseSweepDivider[ch] == 0 && (sweepReg & 0x80) != 0 && shift > 0) {
            int timer = ((reg[0x03 + ch * 4] & 0x07) << 8) | reg[0x02 + ch * 4];
            int delta = timer >> shift;
            int target = (sweepReg & 0x08) != 0 ? timer - delta : timer + delta;
            if (target < 0) {
                target = 0;
            }
            timer = target & 0x7FF;
            reg[0x02 + ch * 4] = timer & 0xFF;
            reg[0x03 + ch * 4] = (reg[0x03 + ch * 4] & 0xF8) | ((timer >> 8) & 0x07);
        }

        if (pulseSweepDivider[ch] == 0 || pulseSweepReload[ch]) {
            pulseSweepDivider[ch] = period;
            pulseSweepReload[ch] = false;
        } else {
            pulseSweepDivider[ch]--;
        }
    }

    private int pulseOutput(int ch) {
        if (lengthCounter[ch] == 0) {
            return 0;
        }
        if (pulsePeriod(ch) < 8) {
            return 0;
        }
        if (((reg[0x03 + ch * 4] >> 3) & 0x1F) < 8) {
            // short periods are inaudible/muted on hardware
        }
        int duty = (reg[0x00 + ch * 4] >> 6) & 3;
        if (DUTY[duty][pulseSeq[ch]] == 0) {
            return 0;
        }
        int volume = (reg[0x00 + ch * 4] & 0x10) != 0
                ? reg[0x00 + ch * 4] & 0x0F
                : (ch == 0 ? pulseEnvVolume[0] : pulseEnvVolume[1]);
        return volume;
    }

    private void pushSample() {
        int p1 = pulseOutput(0);
        int p2 = pulseOutput(1);
        int tri = (triLength > 0 && triLinear > 0) ? TRIANGLE_SEQ[triSeq] : 0;
        int noise = 0;
        if (noiseLength > 0 && (noiseShift & 1) == 0) {
            noise = (reg[0x0C] & 0x10) != 0 ? reg[0x0C] & 0x0F : noiseEnvVolume;
        }

        // nesdev nonlinear mixer approximation
        double pulseOut = (p1 + p2) == 0 ? 0.0 : 95.88 / ((8128.0 / (p1 + p2)) + 100.0);
        double tnd = 0.0;
        if (tri != 0 || noise != 0) {
            tnd = 159.79 / ((1.0 / (tri / 8227.0 + noise / 12241.0)) + 100.0);
        }
        double mixed = pulseOut + tnd;
        if (mixed > 1.0) {
            mixed = 1.0;
        }
        short sample = (short) ((mixed - 0.5) * 2 * 12000);

        if (count < buffer.length) {
            buffer[writePos] = sample;
            writePos = (writePos + 1) % buffer.length;
            count++;
        }
    }

    /** Drains up to {@code max} samples into {@code out}. Returns the number written. */
    public int readAudio(short[] out, int offset, int max) {
        int n = Math.min(max, count);
        for (int i = 0; i < n; i++) {
            out[offset + i] = buffer[readPos];
            readPos = (readPos + 1) % buffer.length;
        }
        count -= n;
        return n;
    }

    public int available() {
        return count;
    }

    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (int v : reg) {
            out.writeInt(v);
        }
        for (int i = 0; i < 2; i++) {
            out.writeInt(pulseTimer[i]);
            out.writeInt(pulseSeq[i]);
            out.writeInt(pulseEnvDivider[i]);
            out.writeInt(pulseEnvVolume[i]);
            out.writeBoolean(pulseEnvStart[i]);
            out.writeInt(pulseSweepDivider[i]);
            out.writeBoolean(pulseSweepReload[i]);
        }
        out.writeInt(triTimer);
        out.writeInt(triSeq);
        out.writeInt(triLength);
        out.writeInt(triLinear);
        out.writeInt(noiseTimer);
        out.writeInt(noiseShift);
        out.writeInt(noiseLength);
        for (int v : lengthCounter) {
            out.writeInt(v);
        }
        out.writeInt(frameCycle);
        out.writeInt(quarterCount);
        out.writeBoolean(fiveStepMode);
        out.writeBoolean(frameIrq);
        out.writeBoolean(irqInhibit);
        out.writeBoolean(channelEnabledPulse1);
        out.writeBoolean(channelEnabledPulse2);
        out.writeBoolean(channelEnabledTriangle);
        out.writeBoolean(channelEnabledNoise);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < reg.length; i++) {
            reg[i] = in.readInt();
        }
        for (int i = 0; i < 2; i++) {
            pulseTimer[i] = in.readInt();
            pulseSeq[i] = in.readInt();
            pulseEnvDivider[i] = in.readInt();
            pulseEnvVolume[i] = in.readInt();
            pulseEnvStart[i] = in.readBoolean();
            pulseSweepDivider[i] = in.readInt();
            pulseSweepReload[i] = in.readBoolean();
        }
        triTimer = in.readInt();
        triSeq = in.readInt();
        triLength = in.readInt();
        triLinear = in.readInt();
        noiseTimer = in.readInt();
        noiseShift = in.readInt();
        noiseLength = in.readInt();
        for (int i = 0; i < lengthCounter.length; i++) {
            lengthCounter[i] = in.readInt();
        }
        frameCycle = in.readInt();
        quarterCount = in.readInt();
        fiveStepMode = in.readBoolean();
        frameIrq = in.readBoolean();
        irqInhibit = in.readBoolean();
        channelEnabledPulse1 = in.readBoolean();
        channelEnabledPulse2 = in.readBoolean();
        channelEnabledTriangle = in.readBoolean();
        channelEnabledNoise = in.readBoolean();
    }
}
