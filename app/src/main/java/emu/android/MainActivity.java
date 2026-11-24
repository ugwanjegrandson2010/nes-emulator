package emu.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import emu.CoreRegistry;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** ROM library: pick a ROM with the system file picker, keep recent ones, launch the emulator. */
public final class MainActivity extends Activity {

    private static final int REQUEST_OPEN = 1001;

    private RomLibrary library;
    private LinearLayout recentList;
    private TextView emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new RomLibrary(this);

        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101014);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MilliEmu");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Retro emulator hub  ·  cores: " + supportedCoreList());
        subtitle.setTextColor(0xFF9AA0A6);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, pad);
        root.addView(subtitle);

        Button open = new Button(this);
        open.setText("Open ROM…");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRom();
            }
        });
        root.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView recentLabel = new TextView(this);
        recentLabel.setText("RECENT");
        recentLabel.setTextColor(0xFF6B7075);
        recentLabel.setTextSize(12);
        recentLabel.setPadding(0, pad, 0, dp(6));
        root.addView(recentLabel);

        emptyHint = new TextView(this);
        emptyHint.setText("No ROMs yet. Open a .nes file to start.\n\n"
                + "Everything runs locally: no network, no accounts.");
        emptyHint.setTextColor(0xFF9AA0A6);
        emptyHint.setTextSize(14);
        root.addView(emptyHint);

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(recentList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshRecent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecent();
    }

    private String supportedCoreList() {
        String[] extensions = CoreRegistry.supportedExtensions();
        StringBuilder sb = new StringBuilder();
        for (String extension : extensions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String name = CoreRegistry.coreNameFor(extension);
            sb.append(name == null ? extension.toUpperCase() : name);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private void refreshRecent() {
        recentList.removeAllViews();
        List<File> roms = library.recentRoms();
        emptyHint.setVisibility(roms.isEmpty() ? View.VISIBLE : View.GONE);

        for (final File rom : roms) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(14), dp(12), dp(14));
            row.setBackgroundColor(0xFF1B1B21);

            TextView name = new TextView(this);
            name.setText(rom.getName());
            name.setTextColor(0xFFE8EAED);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView core = new TextView(this);
            String extension = RomLibrary.extensionOf(rom.getName());
            String coreName = CoreRegistry.coreNameFor(extension);
            core.setText(coreName == null ? "?" : coreName);
            core.setTextColor(0xFF8AB4F8);
            core.setTextSize(12);
            row.addView(core);

            final File romFile = rom;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startEmulator(romFile);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    library.forget(romFile);
                    refreshRecent();
                    toast("Removed " + romFile.getName() + " from the list");
                    return true;
                }
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            recentList.addView(row, params);
        }
    }

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_OPEN);
        } catch (RuntimeException e) {
            toast("No file picker available on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String displayName = RomLibrary.displayName(this, uri);
        if (!CoreRegistry.isSupported(displayName)) {
            toast("Unsupported file type. Supported: " + supportedCoreList());
            return;
        }
        try {
            File rom = library.importRom(uri, displayName);
            startEmulator(rom);
        } catch (IOException e) {
            toast("Could not read the ROM: " + e.getMessage());
        }
    }

    private void startEmulator(File rom) {
        Intent intent = new Intent(this, EmulatorActivity.class);
        intent.putExtra(EmulatorActivity.EXTRA_ROM_PATH, rom.getAbsolutePath());
        startActivity(intent);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
