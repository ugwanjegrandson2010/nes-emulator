package emu.android;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Shared colours and small view builders, so both screens look like one app. */
final class Ui {

    static final int BG_TOP = 0xFF1B1038;
    static final int BG_BOTTOM = 0xFF08060F;
    static final int CARD = 0xFF241A3D;
    static final int CARD_HIGHLIGHT = 0xFF31234F;
    static final int TEXT = 0xFFF4F0FF;
    static final int TEXT_DIM = 0xFFA99BC9;
    static final int TEXT_FAINT = 0xFF6F6390;
    static final int ACCENT = 0xFF9B6DFF;
    static final int ACCENT_END = 0xFFE45CF0;

    private Ui() {
    }

    /** One accent colour per system, used by the chips and the recent-game cards. */
    static int coreColor(String coreName) {
        if (coreName == null) {
            return ACCENT;
        }
        String name = coreName.toLowerCase(java.util.Locale.US);
        if (name.contains("advance")) {
            return 0xFF5AA9FF;
        }
        if (name.contains("ds")) {
            return 0xFFFF6FA8;
        }
        if (name.contains("game boy")) {
            return 0xFF7BE08A;
        }
        if (name.contains("nes")) {
            return 0xFFFFB454;
        }
        return ACCENT;
    }

    static GradientDrawable verticalGradient(int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(0f);
        return drawable;
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    static GradientDrawable roundedGradient(int start, int end, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        drawable.setCornerRadius(radius);
        return drawable;
    }

    static GradientDrawable roundedOutline(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    static TextView text(Context context, String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new ViewGroup.LayoutParams(1, dp(context, heightDp)));
        return view;
    }
}
