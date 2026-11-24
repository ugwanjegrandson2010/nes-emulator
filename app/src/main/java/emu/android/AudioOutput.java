package emu.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import emu.EmulatorCore;

/** Streams the core's mono 16-bit samples to the speaker with a small, low latency buffer. */
public final class AudioOutput {

    private AudioTrack track;
    private final short[] buffer = new short[4096];
    private boolean enabled = true;

    public void open(int sampleRate) {
        close();
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer * 2, sampleRate / 4 * 2);
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return;
            }
            track.play();
        } catch (RuntimeException e) {
            track = null;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled && track != null;
    }

    /** Drains the core's audio ring into the track. Blocks briefly when the buffer is full. */
    public void pump(EmulatorCore core) {
        if (track == null || !enabled) {
            return;
        }
        int samples = core.readAudio(buffer, 0, buffer.length);
        if (samples <= 0) {
            return;
        }
        try {
            track.write(buffer, 0, samples);
        } catch (RuntimeException ignored) {
            // a dead audio device must never stop emulation
        }
    }

    public void close() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (RuntimeException ignored) {
            }
            track.release();
            track = null;
        }
    }
}
