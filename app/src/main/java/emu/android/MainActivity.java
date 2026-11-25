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

        int pad = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.verticalGradient(Ui.BG_TOP, Ui.BG_BOTTOM));
        root.setPadding(pad, pad, pad, pad);

        // ---- header -------------------------------------------------------
        TextView title = Ui.text(this, "MilliEmu", 34, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLetterSpacing(0.02f);
        root.addView(title);

        TextView subtitle = Ui.text(this, "Retro emulator hub", 13, Ui.TEXT_DIM, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 14));
        root.addView(subtitle);

        // ---- which systems this build runs --------------------------------
        LinearLayout cores = new LinearLayout(this);
        cores.setOrientation(LinearLayout.HORIZONTAL);
        cores.setGravity(Gravity.CENTER_HORIZONTAL);
        addCoreChip(cores, "NES", Ui.coreColor("nes"));
        addCoreChip(cores, "GB / GBC", Ui.coreColor("game boy"));
        addCoreChip(cores, "GBA", Ui.coreColor("advance"));
        addCoreChip(cores, "NDS", Ui.coreColor("ds"));
        root.addView(cores);

        root.addView(Ui.spacer(this, 18));

        // ---- primary action ----------------------------------------------
        Button open = new Button(this);
        open.setText("Open ROM…");
        open.setTextSize(17);
        open.setTextColor(0xFFFFFFFF);
        open.setAllCaps(false);
        open.setBackground(Ui.roundedGradient(Ui.ACCENT, Ui.ACCENT_END, Ui.dp(this, 28)));
        open.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRom();
            }
        });
        root.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        root.addView(Ui.spacer(this, 22));

        // ---- recent list --------------------------------------------------
        TextView recentLabel = Ui.text(this, "RECENT", 12, Ui.TEXT_FAINT, true);
        recentLabel.setLetterSpacing(0.14f);
        recentLabel.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 10));
        root.addView(recentLabel);

        emptyHint = Ui.text(this, "No ROMs yet — open a .nes, .gb, .gbc, .gba or .nds file to start.\n\n"
                + "Everything runs locally: no network, no accounts, no ads.", 14, Ui.TEXT_DIM, false);
        emptyHint.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        emptyHint.setLineSpacing(Ui.dp(this, 4), 1f);
        root.addView(emptyHint);

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(recentList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshRecent();
    }

    private void addCoreChip(LinearLayout parent, String label, int color) {
        TextView chip = Ui.text(this, label, 11, color, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        chip.setBackground(Ui.roundedOutline((color & 0x00FFFFFF) | 0x22000000, Ui.dp(this, 20),
                (color & 0x00FFFFFF) | 0x88000000, Ui.dp(this, 1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 6);
        parent.addView(chip, params);
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
            String extension = RomLibrary.extensionOf(rom.getName());
            String coreName = CoreRegistry.coreNameFor(extension);
            int accent = Ui.coreColor(coreName);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, Ui.dp(this, 12), 0);
            row.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));

            // colour strip on the left, tinted by the system
            View strip = new View(this);
            strip.setBackground(Ui.roundedGradient(accent, (accent & 0x00FFFFFF) | 0xFF000000,
                    Ui.dp(this, 14)));
            LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                    Ui.dp(this, 5), ViewGroup.LayoutParams.MATCH_PARENT);
            stripParams.rightMargin = Ui.dp(this, 12);
            row.addView(strip, stripParams);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

            TextView name = Ui.text(this, rom.getName(), 15, Ui.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            column.addView(name);

            TextView core = Ui.text(this, coreName == null ? "Unknown system" : coreName,
                    12, accent, false);
            core.setPadding(0, Ui.dp(this, 2), 0, 0);
            column.addView(core);

            row.addView(column, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView play = Ui.text(this, "▶", 15, accent, true);
            row.addView(play);

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
            params.bottomMargin = Ui.dp(this, 8);
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
}
