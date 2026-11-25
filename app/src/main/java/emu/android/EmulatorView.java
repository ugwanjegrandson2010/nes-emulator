package emu.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import emu.EmulatorCore;

import java.util.ArrayDeque;

/**
 * Draws emulated frames into a SurfaceView, plays audio, and renders an on-screen gamepad.
 *
 * <p>All emulator calls happen on one dedicated thread. UI actions (save, load, reset) are posted
 * to that thread as commands so the core is never touched from two threads at once.
 */
public final class EmulatorView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    public static final int BTN_A = 0x01;
    public static final int BTN_B = 0x02;
    public static final int BTN_SELECT = 0x04;
    public static final int BTN_START = 0x08;
    public static final int BTN_UP = 0x10;
    public static final int BTN_DOWN = 0x20;
    public static final int BTN_LEFT = 0x40;
    public static final int BTN_RIGHT = 0x80;

    private static final long FRAME_NANOS = 1_000_000_000L / 60L;

    public interface Listener {
        void onCrashed(Throwable error);

        void onFps(int framesPerSecond);
    }

    private final SurfaceHolder holder;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dest = new Rect();
    private final RectF upRect = new RectF();
    private final RectF downRect = new RectF();
    private final RectF leftRect = new RectF();
    private final RectF rightRect = new RectF();
    private final RectF startRect = new RectF();
    private final RectF selectRect = new RectF();
    private final PointF aCenter = new PointF();
    private final PointF bCenter = new PointF();
    private float buttonRadius;

    private final ArrayDeque<Runnable> commands = new ArrayDeque<Runnable>();
    private final SparseArray<PointF> pointers = new SparseArray<PointF>();

    private EmulatorCore core;
    private AudioOutput audio;
    private Listener listener;
    private Bitmap bitmap;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile int buttonMask;
    private float fps;

    public EmulatorView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        fillPaint.setColor(0x59FFFFFF);
        strokePaint.setColor(0x8AFFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);
        textPaint.setColor(0xE6FFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void attach(EmulatorCore core, AudioOutput audio, Listener listener) {
        this.core = core;
        this.audio = audio;
        this.listener = listener;
        this.bitmap = Bitmap.createBitmap(core.width(), core.height(), Bitmap.Config.ARGB_8888);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            startLoop();
        }
    }

    public void detach() {
        stopLoop();
        bitmap = null;
        core = null;
        audio = null;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            buttonMask = 0;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Runs {@code command} on the emulation thread, between frames. */
    public void postCommand(Runnable command) {
        synchronized (commands) {
            commands.add(command);
        }
    }

    public long framesRendered() {
        return frames;
    }

    private long frames;

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (core != null) {
            startLoop();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        layoutControls(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopLoop();
    }

    private void startLoop() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "emulation");
        thread.start();
    }

    private void stopLoop() {
        running = false;
        Thread current = thread;
        thread = null;
        if (current != null) {
            current.interrupt();
            try {
                current.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        long nextFrame = System.nanoTime();
        long fpsWindowStart = nextFrame;
        int framesInWindow = 0;

        while (running) {
            runPendingCommands();

            if (paused) {
                sleepQuietly(40);
                nextFrame = System.nanoTime();
                continue;
            }

            try {
                core.setButtons(0, buttonMask);
                core.stepFrame();
                if (audio != null) {
                    audio.pump(core);
                }
                drawFrame();
                frames++;
            } catch (Throwable error) {
                if (listener != null) {
                    listener.onCrashed(error);
                }
                running = false;
                break;
            }

            framesInWindow++;
            long now = System.nanoTime();
            if (now - fpsWindowStart >= 1_000_000_000L) {
                fps = framesInWindow * 1_000_000_000f / (now - fpsWindowStart);
                framesInWindow = 0;
                fpsWindowStart = now;
                if (listener != null) {
                    listener.onFps(Math.round(fps));
                }
            }

            nextFrame += FRAME_NANOS;
            long sleepNanos = nextFrame - System.nanoTime();
            if (sleepNanos > 1_500_000L) {
                sleepQuietly((int) (sleepNanos / 1_000_000L));
            } else if (sleepNanos < -200_000_000L) {
                nextFrame = System.nanoTime();
            }
        }
    }

    private void sleepQuietly(int millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runPendingCommands() {
        while (true) {
            Runnable command;
            synchronized (commands) {
                command = commands.poll();
            }
            if (command == null) {
                return;
            }
            try {
                command.run();
            } catch (RuntimeException e) {
                if (listener != null) {
                    listener.onCrashed(e);
                }
            }
        }
    }

    // ------------------------------------------------------------ drawing

    private void drawFrame() {
        if (!holder.getSurface().isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.BLACK);

            int width = core.width();
            int height = core.height();
            int[] pixels = core.framebuffer();
            if (pixels != null && pixels.length == width * height) {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
                computeDestination(canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(bitmap, null, dest, bitmapPaint);
            }

            layoutControls(canvas.getWidth(), canvas.getHeight());
            drawControls(canvas);
            if (paused) {
                drawPausedOverlay(canvas);
            }
        } catch (RuntimeException ignored) {
            // a transient surface error should not kill emulation
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** Letterbox to the core's aspect ratio (4:3 for the NES, 10:9 for the Game Boy). */
    private void computeDestination(int viewWidth, int viewHeight) {
        float targetAspect = core.aspectRatio();
        int width = viewWidth;
        int height = Math.round(width / targetAspect);
        if (height > viewHeight) {
            height = viewHeight;
            width = Math.round(height * targetAspect);
        }
        int left = (viewWidth - width) / 2;
        int top = (viewHeight - height) / 2;
        dest.set(left, top, left + width, top + height);
    }

    private void layoutControls(int width, int height) {
        float unit = Math.min(width, height);
        float pad = unit * 0.05f;
        float dpadSize = unit * 0.30f;
        float arm = dpadSize / 3f;
        float cx = pad + dpadSize / 2f;
        float cy = height - pad - dpadSize / 2f;

        upRect.set(cx - arm / 2f, cy - arm * 1.5f, cx + arm / 2f, cy - arm / 2f);
        downRect.set(cx - arm / 2f, cy + arm / 2f, cx + arm / 2f, cy + arm * 1.5f);
        leftRect.set(cx - arm * 1.5f, cy - arm / 2f, cx - arm / 2f, cy + arm / 2f);
        rightRect.set(cx + arm / 2f, cy - arm / 2f, cx + arm * 1.5f, cy + arm / 2f);

        buttonRadius = unit * 0.072f;
        aCenter.set(width - pad - buttonRadius, height - pad - buttonRadius * 3f);
        bCenter.set(width - pad - buttonRadius * 3f, height - pad - buttonRadius);

        float smallWidth = unit * 0.10f;
        float smallHeight = unit * 0.042f;
        float smallTop = height - pad - smallHeight;
        selectRect.set(width / 2f - smallWidth * 1.25f, smallTop,
                width / 2f - smallWidth * 0.25f, smallTop + smallHeight);
        startRect.set(width / 2f + smallWidth * 0.25f, smallTop,
                width / 2f + smallWidth * 1.25f, smallTop + smallHeight);

        textPaint.setTextSize(unit * 0.038f);
    }

    private void drawControls(Canvas canvas) {
        float radius = buttonRadius * 0.35f;
        float labelSize = textPaint.getTextSize();

        canvas.drawRoundRect(upRect, radius, radius, fillPaint);
        canvas.drawRoundRect(downRect, radius, radius, fillPaint);
        canvas.drawRoundRect(leftRect, radius, radius, fillPaint);
        canvas.drawRoundRect(rightRect, radius, radius, fillPaint);
        canvas.drawRoundRect(startRect, radius, radius, fillPaint);
        canvas.drawRoundRect(selectRect, radius, radius, fillPaint);
        canvas.drawCircle(aCenter.x, aCenter.y, buttonRadius, fillPaint);
        canvas.drawCircle(bCenter.x, bCenter.y, buttonRadius, fillPaint);

        canvas.drawCircle(aCenter.x, aCenter.y, buttonRadius, strokePaint);
        canvas.drawCircle(bCenter.x, bCenter.y, buttonRadius, strokePaint);

        textPaint.setTextSize(labelSize);
        canvas.drawText("A", aCenter.x, aCenter.y + labelSize / 3f, textPaint);
        canvas.drawText("B", bCenter.x, bCenter.y + labelSize / 3f, textPaint);
        textPaint.setTextSize(labelSize * 0.7f);
        canvas.drawText("START", startRect.centerX(), startRect.centerY() + labelSize * 0.25f, textPaint);
        canvas.drawText("SELECT", selectRect.centerX(), selectRect.centerY() + labelSize * 0.25f, textPaint);

        textPaint.setTextSize(labelSize * 0.8f);
        canvas.drawText("▲", upRect.centerX(), upRect.centerY() + labelSize * 0.3f, textPaint);
        canvas.drawText("▼", downRect.centerX(), downRect.centerY() + labelSize * 0.3f, textPaint);
        canvas.drawText("◀", leftRect.centerX(), leftRect.centerY() + labelSize * 0.3f, textPaint);
        canvas.drawText("▶", rightRect.centerX(), rightRect.centerY() + labelSize * 0.3f, textPaint);
    }

    private void drawPausedOverlay(Canvas canvas) {
        fillPaint.setColor(0x88000000);
        canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), fillPaint);
        fillPaint.setColor(0x59FFFFFF);
        textPaint.setTextSize(canvas.getHeight() * 0.06f);
        canvas.drawText("PAUSED", canvas.getWidth() / 2f, canvas.getHeight() / 2f, textPaint);
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pointers.clear();
            } else {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    if (action == MotionEvent.ACTION_POINTER_UP && id == event.getPointerId(event.getActionIndex())) {
                        pointers.remove(id);
                        continue;
                    }
                    pointers.put(id, new PointF(event.getX(i), event.getY(i)));
                }
            }
            buttonMask = computeMask();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int computeMask() {
        int mask = 0;
        for (int i = 0; i < pointers.size(); i++) {
            PointF point = pointers.valueAt(i);
            mask |= maskFor(point.x, point.y);
        }
        return mask;
    }

    private int maskFor(float x, float y) {
        int mask = 0;
        if (upRect.contains(x, y)) {
            mask |= BTN_UP;
        }
        if (downRect.contains(x, y)) {
            mask |= BTN_DOWN;
        }
        if (leftRect.contains(x, y)) {
            mask |= BTN_LEFT;
        }
        if (rightRect.contains(x, y)) {
            mask |= BTN_RIGHT;
        }
        if (selectRect.contains(x, y)) {
            mask |= BTN_SELECT;
        }
        if (startRect.contains(x, y)) {
            mask |= BTN_START;
        }
        if (distance(x, y, aCenter.x, aCenter.y) <= buttonRadius * 1.15f) {
            mask |= BTN_A;
        }
        if (distance(x, y, bCenter.x, bCenter.y) <= buttonRadius * 1.15f) {
            mask |= BTN_B;
        }
        return mask;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
