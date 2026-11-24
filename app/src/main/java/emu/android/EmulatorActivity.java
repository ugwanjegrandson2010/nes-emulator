package emu.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import emu.CoreRegistry;
import emu.EmulatorCore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Hosts one running game: surface, audio, save states and the overlay controls. */
public final class EmulatorActivity extends Activity implements EmulatorView.Listener {

    public static final String EXTRA_ROM_PATH = "rom_path";

    private EmulatorCore core;
    private EmulatorView view;
    private AudioOutput audio;
    private RomLibrary library;
    private File romFile;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        String path = getIntent().getStringExtra(EXTRA_ROM_PATH);
        if (path == null) {
            finish();
            return;
        }
        romFile = new File(path);
        library = new RomLibrary(this);

        core = CoreRegistry.createForFileName(romFile.getName());
        if (core == null) {
            toast("No core supports this file type");
            finish();
            return;
        }
        try {
            core.loadRom(readFile(romFile));
        } catch (IOException e) {
            toast("Could not load ROM: " + e.getMessage());
            finish();
            return;
        }

        audio = new AudioOutput();
        audio.open(core.sampleRate());

        view = new EmulatorView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.addView(chip("Pause", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean paused = !view.isPaused();
                view.setPaused(paused);
                ((TextView) v).setText(paused ? "Resume" : "Pause");
            }
        }));
        bar.addView(chip("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveState();
            }
        }));
        bar.addView(chip("Load", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadState();
            }
        }));
        bar.addView(chip("Reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.postCommand(new Runnable() {
                    @Override
                    public void run() {
                        core.reset();
                    }
                });
            }
        }));
        bar.addView(chip("Exit", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));

        status = new TextView(this);
        status.setText(core.name() + "  ·  " + romFile.getName());
        status.setTextColor(0xB3FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(bar, barParams);
        setContentView(root);

        view.attach(core, audio, this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) {
            view.setPaused(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (view != null) {
            view.detach();
        }
        if (audio != null) {
            audio.close();
        }
        super.onDestroy();
    }

    private void saveState() {
        final File target = library.stateFileFor(romFile);
        view.postCommand(new Runnable() {
            @Override
            public void run() {
                final byte[] state = core.saveState();
                try {
                    FileOutputStream out = new FileOutputStream(target);
                    out.write(state);
                    out.close();
                    notifyUi("State saved (" + state.length / 1024 + " KB)");
                } catch (IOException e) {
                    notifyUi("Could not save state: " + e.getMessage());
                }
            }
        });
    }

    private void loadState() {
        final File source = library.stateFileFor(romFile);
        if (!source.exists()) {
            toast("No saved state for this game yet");
            return;
        }
        final byte[] data;
        try {
            data = readFile(source);
        } catch (IOException e) {
            toast("Could not read state: " + e.getMessage());
            return;
        }
        view.postCommand(new Runnable() {
            @Override
            public void run() {
                try {
                    core.loadState(data);
                    notifyUi("State loaded");
                } catch (IOException e) {
                    notifyUi("Could not load state: " + e.getMessage());
                }
            }
        });
    }

    private void notifyUi(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(message);
            }
        });
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(0xF2FFFFFF);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackgroundColor(0x661A1A1F);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(6);
        chip.setLayoutParams(params);
        return chip;
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onCrashed(final Throwable error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast("Emulation stopped: " + error);
            }
        });
    }

    @Override
    public void onFps(final int framesPerSecond) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (status != null) {
                    status.setText(framesPerSecond + " fps  ·  " + core.name() + "  ·  " + romFile.getName());
                }
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static byte[] readFile(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
