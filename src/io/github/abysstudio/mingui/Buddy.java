package io.github.abysstudio.mingui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A little companion that wanders along the bottom of the screen over every app. Its mood follows today's
 * progress: droopy and grey with nothing done, bouncy and green when the routine is finished.
 * Tap it to open the checklist; hold and drag to move it (it keeps wandering at the height you drop it).
 */
final class Buddy {
    private static final long FRAME_MS = 33;
    static final long SNOOZE_MS = 60 * 60_000;

    private final Context c;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final float d;
    /** The buddy's own window: fixed size, so it never jumps when the speech cloud comes and goes. */
    private android.widget.FrameLayout root;
    /** Speech cloud in a separate window, centred above the buddy's head. */
    private CloudView cloud;
    private WindowManager.LayoutParams cloudLp;
    /** The cloud's window stays added once created; hiding makes it fully transparent and untouchable. */
    private boolean cloudAdded, cloudShown;
    private BuddyView body;
    private WindowManager.LayoutParams lp;

    private float x, targetX = -1;
    /** Window y of the buddy itself (without the speech bubble); -1 = default, near the bottom. */
    private float homeY;
    private boolean dragging;
    private long idleUntil, bubbleUntil, nextChatter, lastStateCheck;
    private float mood = -1;
    private boolean done;

    private final Runnable frame = this::frame;

    Buddy(Context c) {
        this.c = c;
        this.wm = c.getSystemService(WindowManager.class);
        this.d = c.getResources().getDisplayMetrics().density;
    }

    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c);
    }

    void show() {
        if (root != null || !canShow(c)) return;
        root = new android.widget.FrameLayout(c);
        body = new BuddyView(c);
        scale = BuddyStyle.SIZES[BuddyStyle.sizeIndex(c)];
        body.setScale(scale);
        body.setCameraDistance(8000 * d);
        // Portal behind; the buddy sits in a box that can clip it at the portal's rim.
        portal = new PortalView(c);
        root.addView(portal, new android.widget.FrameLayout.LayoutParams(-1, -1));
        clipBox = new android.widget.FrameLayout(c);
        clipBox.addView(body, new android.widget.FrameLayout.LayoutParams(bw(), bh()));
        root.addView(clipBox, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // Let a leaning / popping / stretching buddy draw into the animation margin (see animMode).
        root.setClipChildren(false);
        clipBox.setClipChildren(false);
        cloud = new CloudView(c);
        cloud.setOnClickListener(v -> {
            hideCloud();
            MainActivity.launch(c);
        });

        body.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private long downAt;
            private final float slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();

            @Override
            @SuppressWarnings("ClickableViewAccessibility")
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        downAt = SystemClock.uptimeMillis();
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && (Math.hypot(dx, dy) > slop
                                || SystemClock.uptimeMillis() - downAt > 350)) {
                            // Picked up.
                            dragging = true;
                            body.held = true;
                            hideCloud();
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        }
                        if (dragging) {
                            int[] size = screen();
                            x = Math.max(0, Math.min(size[0] - bw(), startX + dx));
                            lp.x = (int) x;
                            lp.y = (int) Math.max(0, Math.min(size[1] - bh(), startY + dy));
                            update(root, lp);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (dragging) {
                            // Dropped: wander along this new height from here.
                            dragging = false;
                            body.held = false;
                            homeY = lp.y;
                            Routine.prefs(c).edit().putFloat("buddy_y", homeY).apply();
                            targetX = x;
                            idleUntil = SystemClock.uptimeMillis() + 1200;
                            body.jump();
                            say(pickRes(R.array.say_moved), 2000);
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            body.jump();
                            body.startIdle(BuddyView.WAVE);
                            MainActivity.launch(c);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        body.held = false;
                        return true;
                }
                return false;
            }
        });

        lp = overlayParams(bw(), bh());
        int[] size = screen();
        x = size[0] * 0.2f;
        homeY = Routine.prefs(c).getFloat("buddy_y", -1);
        lp.x = (int) x;
        lp.y = (int) baseY(size);
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            root = null; // no overlay permission yet
            return;
        }
        nextChatter = SystemClock.uptimeMillis() + 8000;
        applyStyle();
        body.setPivotX(bw() / 2f);
        body.setPivotY(bh());
        // Start hidden so the first appearance comes through the portal too.
        root.setVisibility(View.GONE);
        if (!isAway()) enter();
        handler.post(frame);
    }

    private boolean leaving;

    private boolean isAway() {
        return MainActivity.visible
                || Routine.prefs(c).getLong("buddy_hidden_until", 0) > System.currentTimeMillis();
    }

    private PortalView portal;
    private android.widget.FrameLayout clipBox;
    private android.animation.ValueAnimator anim;

    /** The ground line (the buddy's feet) inside its window, where the portal opens. */
    private float ground() {
        return bh() - 6 * d * scale;
    }

    /** While a portal animation runs, the window grows a little downward so the portal fits below the feet. */
    /** Extra room around the buddy while an entrance animation runs (0 at rest). */
    private int pad;
    /** Whether the running animation clips the buddy at the portal's rim. */
    private boolean portalClip;

    /**
     * While an entrance or exit animation runs, the window grows by a margin on every side (and a bit more
     * below, for the portal), so a large buddy that leans, pops or stretches is never cut off. At rest the
     * window is exactly the buddy again, so it doesn't block touches around it.
     */
    private void animMode(boolean on) {
        pad = on ? dp(28 * scale) : 0;
        root.setPadding(pad, pad, pad, 0);
        lp.width = bw() + 2 * pad;
        lp.height = bh() + pad + (on ? dp(14 * scale) : 0);
        lp.x = (int) x - pad;
        lp.y = (int) baseY(screen()) - pad;
        update(root, lp);
        portal.ground = ground();
        clipBox.setClipBounds(on && portalClip
                ? new android.graphics.Rect(-pad, -pad, bw() + pad, (int) ground()) : null);
        if (!on) portal.reset();
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private void runAnim(long ms, android.animation.ValueAnimator.AnimatorUpdateListener step, Runnable end) {
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
        }
        animMode(true);
        anim = android.animation.ValueAnimator.ofFloat(0, ms);
        anim.setDuration(ms);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(step);
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                anim = null;
                animMode(false);
                end.run();
            }
        });
        anim.start();
    }

    /**
     * Comes back through a portal: a glowing hole opens at its feet, the buddy spins up out of it,
     * stars burst around it, the portal snaps shut and it lands with a hop and a wave.
     * Picks up from wherever an interrupted leave got to.
     */
    private void enter() {
        // Settings may have just changed (e.g. you're leaving Personalize): use them from the first frame.
        applyStyle();
        int style = BuddyStyle.entranceIndex(c);
        if (style != BuddyStyle.ENTRANCE_PORTAL) {
            simpleEnter(style);
            return;
        }
        leaving = false;
        boolean wasGone = root.getVisibility() != View.VISIBLE;
        root.setVisibility(View.VISIBLE);
        portalClip = true;
        body.setPivotX(bw() / 2f);
        body.setPivotY(bh());
        final float g = ground();
        final float startY = wasGone ? g : body.getTranslationY();
        final float startOpen = wasGone ? 0 : portal.open;
        body.facing = x < screen()[0] / 2f ? 1 : -1;
        if (wasGone) body.setAlpha(0f);
        final android.view.animation.Interpolator pop = new android.view.animation.OvershootInterpolator(2f),
                rise = new android.view.animation.OvershootInterpolator(1.3f),
                spin = new android.view.animation.DecelerateInterpolator(1.6f),
                shut = new android.view.animation.AccelerateInterpolator(2f);
        runAnim(1100, a -> {
            float t = (float) a.getAnimatedValue();
            // Portal: pops open, holds, snaps shut once the buddy is out.
            portal.open = t < 260 ? startOpen + (1 - startOpen) * pop.getInterpolation(t / 260)
                    : t < 800 ? 1 : 1 - shut.getInterpolation(clamp01((t - 800) / 220));
            portal.glow = 1;
            // Buddy: rises out of the hole with a full spin, stretched on the way up.
            float k = clamp01((t - 160) / 600);
            body.setTranslationY(startY * (1 - rise.getInterpolation(k)));
            body.setRotationY(wasGone ? 360 * spin.getInterpolation(k) : 0);
            body.setAlpha(Math.max(body.getAlpha(), Math.min(1, k * 4)));
            body.setScaleY(1.18f - 0.18f * k);
            body.setScaleX(0.82f + 0.18f * k);
            // Stars burst out as it clears the rim.
            portal.burst = t < 560 ? -1 : clamp01((t - 560) / 540);
            portal.invalidate();
        }, () -> {
            body.setRotationY(0);
            body.setScaleX(1f);
            body.setScaleY(1f);
            body.setAlpha(1f);
            body.setTranslationY(0);
            body.jump();
            body.startIdle(BuddyView.WAVE);
        });
        // Stand still through the show and the wave before wandering off again.
        idleUntil = SystemClock.uptimeMillis() + 2600;
    }

    /** Leaves through a portal: crouches, the hole opens, it spins down into it and the portal winks shut. */
    private void leave() {
        int style = BuddyStyle.entranceIndex(c);
        if (style != BuddyStyle.ENTRANCE_PORTAL) {
            simpleLeave(style);
            return;
        }
        leaving = true;
        removeCloudNow();
        portalClip = true;
        body.setPivotX(bw() / 2f);
        body.setPivotY(bh());
        final float g = ground();
        final android.view.animation.Interpolator open = new android.view.animation.DecelerateInterpolator(2f),
                fall = new android.view.animation.AccelerateInterpolator(1.6f),
                shut = new android.view.animation.AccelerateInterpolator(2f);
        runAnim(780, a -> {
            float t = (float) a.getAnimatedValue();
            portal.open = t < 200 ? open.getInterpolation(t / 200)
                    : t < 560 ? 1 : 1 - shut.getInterpolation(clamp01((t - 560) / 180));
            portal.glow = 1;
            if (t < 120) {
                // Anticipation: a little crouch.
                float k = t / 120;
                body.setScaleX(1 + 0.12f * k);
                body.setScaleY(1 - 0.15f * k);
            } else {
                float k = clamp01((t - 120) / 420);
                float f = fall.getInterpolation(k);
                body.setTranslationY(g * f);
                body.setRotationY(-360 * f);
                body.setScaleX(1.12f - 0.3f * k);
                body.setScaleY(0.85f + 0.33f * k);
            }
            // A ring of light flashes outward as it closes.
            portal.ring = t < 560 ? -1 : clamp01((t - 560) / 220);
            portal.invalidate();
        }, () -> {
            if (!leaving || root == null) return;
            leaving = false;
            root.setVisibility(View.GONE);
            body.setTranslationY(0);
            body.setRotationY(0);
            body.setAlpha(1f);
            body.setScaleX(1f);
            body.setScaleY(1f);
        });
    }

    /** Resets everything an entrance/exit animation may have changed. */
    private void restPose() {
        body.setRotation(0);
        body.setTranslationX(0);
        body.setTranslationY(0);
        body.setRotationY(0);
        body.setAlpha(1f);
        body.setScaleX(1f);
        body.setScaleY(1f);
        body.walking = false;
    }

    /** The non-portal entrances: pop, walk in from the nearest edge, fade, or none. */
    private void simpleEnter(int style) {
        leaving = false;
        boolean wasGone = root.getVisibility() != View.VISIBLE;
        root.setVisibility(View.VISIBLE);
        portalClip = false;
        body.setPivotX(bw() / 2f);
        body.setPivotY(bh());
        if (!wasGone && anim != null) {
            // Interrupted a leave: just settle back.
            runAnim(200, a -> {}, this::restPose);
            return;
        }
        final android.view.animation.Interpolator over = new android.view.animation.OvershootInterpolator(2.2f);
        switch (style) {
            case BuddyStyle.ENTRANCE_POP:
                body.setScaleX(0f);
                body.setScaleY(0f);
                runAnim(480, a -> {
                    float k = over.getInterpolation((float) a.getAnimatedValue() / 480f);
                    body.setScaleX(k);
                    body.setScaleY(k);
                }, () -> {
                    restPose();
                    body.startIdle(BuddyView.WAVE);
                });
                break;
            case BuddyStyle.ENTRANCE_WALK: {
                // Start just off the nearest edge and let the normal walking loop bring it back to where it was.
                int[] size = screen();
                float home = x;
                boolean fromLeft = home < size[0] / 2f;
                x = fromLeft ? -bw() : size[0];
                lp.x = (int) x;
                update(root, lp);
                targetX = Math.max(0, Math.min(size[0] - bw(), home));
                idleUntil = 0;
                restPose();
                break;
            }
            case BuddyStyle.ENTRANCE_SPRINT: {
                // Sprint in from the nearest edge leaning forward, then brake: lean back and skid to a stop.
                final int[] size = screen();
                final float home = Math.max(0, Math.min(size[0] - bw(), x));
                final int dir = home < size[0] / 2f ? 1 : -1;
                final float start = dir > 0 ? -bw() : size[0];
                final float brake = home - dir * dp(36);
                body.facing = dir;
                body.setPivotX(bw() / 2f);
                body.setPivotY(bh());
                final android.view.animation.Interpolator dash = new android.view.animation.AccelerateInterpolator(1.2f),
                        slide = new android.view.animation.DecelerateInterpolator(2f),
                        rock = new android.view.animation.OvershootInterpolator(3f);
                runAnim(900, a -> {
                    float t = (float) a.getAnimatedValue();
                    float px;
                    if (t < 420) {
                        float k = dash.getInterpolation(t / 420);
                        px = start + (brake - start) * k;
                        body.walking = true;
                        body.setRotation(11 * dir);
                    } else if (t < 720) {
                        float k = slide.getInterpolation((t - 420) / 300);
                        px = brake + (home - brake) * k;
                        body.walking = false;
                        body.setRotation(-13 * dir * (1 - k * 0.3f));
                    } else {
                        float k = rock.getInterpolation((t - 720) / 180);
                        px = home;
                        body.setRotation(-13 * dir * 0.7f * (1 - k));
                    }
                    x = px;
                    lp.x = (int) px - pad;
                    update(root, lp);
                    body.invalidate();
                }, () -> {
                    x = home;
                    body.setRotation(0);
                    restPose();
                    body.jump();
                    body.startIdle(BuddyView.WAVE);
                });
                break;
            }
            default:
                restPose();
                break;
        }
        idleUntil = Math.max(idleUntil, style == BuddyStyle.ENTRANCE_WALK ? 0 : SystemClock.uptimeMillis() + 1500);
    }

    private void simpleLeave(int style) {
        leaving = true;
        removeCloudNow();
        final Runnable gone = () -> {
            if (!leaving || root == null) return;
            leaving = false;
            root.setVisibility(View.GONE);
            restPose();
        };
        final android.view.animation.Interpolator antic = new android.view.animation.AnticipateInterpolator(2f);
        switch (style) {
            case BuddyStyle.ENTRANCE_POP:
                runAnim(260, a -> {
                    float k = 1 - antic.getInterpolation((float) a.getAnimatedValue() / 260f);
                    body.setScaleX(k);
                    body.setScaleY(k);
                }, gone);
                break;
            case BuddyStyle.ENTRANCE_WALK: {
                // Hurry off the nearest edge.
                final int[] size = screen();
                final float from = x;
                final boolean toLeft = from < size[0] / 2f;
                final float to = toLeft ? -bw() : size[0];
                body.facing = toLeft ? -1 : 1;
                body.walking = true;
                final float home = from;
                runAnim((long) (Math.abs(to - from) / (dp(200)) * 1000) + 150, a -> {
                    float k = (float) a.getAnimatedValue() / a.getDuration();
                    lp.x = (int) (from + (to - from) * k) - pad;
                    update(root, lp);
                    body.invalidate();
                }, () -> {
                    x = home; // come back from this spot next time
                    lp.x = (int) home;
                    update(root, lp);
                    gone.run();
                });
                break;
            }
            case BuddyStyle.ENTRANCE_SPRINT: {
                // Crouch, then dash off the nearest edge leaning forward.
                final int[] size = screen();
                final float from = x, home = x;
                final int dir = from < size[0] / 2f ? -1 : 1;
                final float to = dir < 0 ? -bw() : size[0];
                body.facing = dir;
                body.setPivotX(bw() / 2f);
                body.setPivotY(bh());
                final android.view.animation.Interpolator dash = new android.view.animation.AccelerateInterpolator(1.6f);
                runAnim(480, a -> {
                    float t = (float) a.getAnimatedValue();
                    if (t < 110) {
                        float k = t / 110;
                        body.setScaleX(1 + 0.1f * k);
                        body.setScaleY(1 - 0.14f * k);
                    } else {
                        float k = dash.getInterpolation((t - 110) / 370);
                        body.setScaleX(1.1f - 0.1f * k);
                        body.setScaleY(0.86f + 0.14f * k);
                        body.setRotation(12 * dir);
                        body.walking = true;
                        lp.x = (int) (from + (to - from) * k) - pad;
                        update(root, lp);
                    }
                    body.invalidate();
                }, () -> {
                    body.setRotation(0);
                    x = home; // reappear from this spot
                    lp.x = (int) home;
                    update(root, lp);
                    gone.run();
                });
                break;
            }
            default:
                gone.run();
                break;
        }
    }

    /** Re-checks right away (e.g. the checklist just closed) instead of waiting for the next poll. */
    void poke() {
        if (root == null) return;
        handler.removeCallbacks(frame);
        handler.post(frame);
    }

    private static final int BODY_W = 72, BODY_H = 80;
    private float scale = 1f, speedMul = 1f;
    private int chat = 1;

    private int bw() {
        return dp(BODY_W * scale);
    }

    private int bh() {
        return dp(BODY_H * scale);
    }

    private int headTop() {
        return dp(HEAD_TOP * scale);
    }

    /** Applies the personalisation settings; cheap enough to call on every state check. */
    private void applyStyle() {
        float newScale = BuddyStyle.SIZES[BuddyStyle.sizeIndex(c)];
        speedMul = BuddyStyle.SPEEDS[BuddyStyle.speedIndex(c)];
        chat = BuddyStyle.chatIndex(c);
        body.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(c)];
        body.accessory = BuddyStyle.accessoryDef(c);
        if (newScale != scale) {
            scale = newScale;
            body.setScale(scale);
            body.setLayoutParams(new android.widget.FrameLayout.LayoutParams(bw(), bh()));
            body.setPivotX(bw() / 2f);
            body.setPivotY(bh());
            if (lp != null) {
                lp.width = bw();
                lp.height = bh();
                update(root, lp);
            }
        }
    }
    /** Where the top of the buddy's head is, from the top of its window. */
    private static final int HEAD_TOP = 26;

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.windowAnimations = 0; // no system fade on add/remove: that's what made the cloud flash back
        return p;
    }

    private void update(View v, WindowManager.LayoutParams p) {
        try {
            wm.updateViewLayout(v, p);
        } catch (RuntimeException ignored) {
        }
    }

    private float baseY(int[] size) {
        return homeY >= 0 ? Math.min(homeY, size[1] - bh()) : size[1] - dp(190);
    }

    void hide() {
        handler.removeCallbacks(frame);
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
            anim = null;
        }
        removeCloudNow();
        if (cloudAdded) {
            try {
                wm.removeView(cloud);
            } catch (RuntimeException ignored) {
            }
            cloudAdded = false;
        }
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
            }
            root = null;
        }
    }

    private int[] screen() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private void frame() {
        if (root == null) return;
        long now = SystemClock.uptimeMillis();
        boolean screenOn = c.getSystemService(PowerManager.class).isInteractive();
        if (!screenOn) {
            // Nothing to animate with the screen off; check back in a bit.
            handler.postDelayed(frame, 1000);
            return;
        }
        // Stay out of the way on our own checklist (you're already there), and while snoozed.
        if (isAway()) {
            if (root.getVisibility() == View.VISIBLE && !leaving) leave();
            // Cheap check; polled often so the buddy is back the moment you leave the app.
            handler.postDelayed(frame, 150);
            return;
        }
        if (root.getVisibility() != View.VISIBLE || leaving) enter();
        if (anim != null) {
            // An entrance animation is moving the buddy: let it finish before walking around again.
            body.invalidate();
            handler.postDelayed(frame, FRAME_MS);
            return;
        }

        if (dragging) {
            body.invalidate();
            handler.postDelayed(frame, FRAME_MS);
            return;
        }

        if (now - lastStateCheck > 1500) {
            lastStateCheck = now;
            applyStyle();
            checkState();
        }

        int[] size = screen();
        float maxX = size[0] - bw();
        if (targetX < 0 || targetX > maxX) targetX = rnd.nextFloat() * maxX;
        boolean walking = now >= idleUntil;
        if (walking) {
            // Sad buddy shuffles, happy buddy trots.
            float speed = dp((18 + 62 * Math.max(0, mood)) * speedMul) * FRAME_MS / 1000f;
            float dx = targetX - x;
            if (Math.abs(dx) <= speed) {
                x = targetX;
                idleUntil = now + 1500 + rnd.nextInt(done ? 3000 : 5000);
                targetX = rnd.nextFloat() * maxX;
                body.startIdle(pickIdle());
            } else {
                x += Math.signum(dx) * speed;
                body.facing = dx > 0 ? 1 : -1;
            }
        }
        body.walking = walking;
        x = Math.max(0, Math.min(maxX, x));
        lp.x = (int) x;
        lp.y = (int) baseY(size);
        update(root, lp);

        if (cloudShown) {
            if (now > bubbleUntil) hideCloud();
            else placeCloud(size);
        }
        if (now > nextChatter) {
            // Quiet buddies only react to progress; chatty ones talk about twice as often.
            if (chat > 0) chatter();
            nextChatter = now + (chat == 2 ? 20_000 + rnd.nextInt(20_000) : 45_000 + rnd.nextInt(45_000));
        }
        body.invalidate();
        handler.postDelayed(frame, FRAME_MS);
    }

    /** What to do while standing still, depending on mood. */
    private int pickIdle() {
        if (done) return rnd.nextBoolean() ? BuddyView.DANCE : (rnd.nextInt(3) == 0 ? BuddyView.SPIN : BuddyView.WAVE);
        if (mood <= 0.01f) return rnd.nextBoolean() ? BuddyView.SIGH : BuddyView.LOOK;
        if (mood >= 0.5f) return rnd.nextBoolean() ? BuddyView.WAVE : BuddyView.LOOK;
        return BuddyView.LOOK;
    }

    /** Re-reads today's progress; reacts when you tick something off. */
    private Set<String> lastChecked;
    private long lastLevelCheck;

    /** Which little act the buddy performs for a habit, by keywords in its name. */
    static int reactionFor(String habit) {
        String h = habit.toLowerCase(java.util.Locale.ROOT);
        // English, Spanish and Catalan keywords.
        if (h.matches(".*(water|drink|hydrat|tea|juice|agua|beber|hidrat|zumo|aigua|beure|suc).*")) return BuddyView.WATER;
        if (h.matches(".*(read|book|page|study|learn|leer|lectura|libro|p[aáà]gina|estudi|aprend|llegir|llibre).*"))
            return BuddyView.BOOK;
        if (h.matches(".*(exercis|gym|run|walk|workout|train|push|squat|sport|bike|swim|steps|stretch|ejercicio"
                + "|exercici|gimn|c[oó]rrer|caminar|passeja|entren|deporte|esport|bici|nadar|nedar|estira).*"))
            return BuddyView.WORKOUT;
        if (h.matches(".*(meditat|breath|yoga|journal|calm|mindful|pray|medita|respira|diario|diari|rezar|resar).*"))
            return BuddyView.ZEN;
        return BuddyView.NONE;
    }

    private String reactionLine(int reaction) {
        switch (reaction) {
            case BuddyView.WATER: return pickRes(R.array.say_water);
            case BuddyView.BOOK: return pickRes(R.array.say_book);
            case BuddyView.WORKOUT: return pickRes(R.array.say_workout);
            case BuddyView.ZEN: return pickRes(R.array.say_zen);
            default: return pickRes(R.array.say_generic);
        }
    }

    private void checkState() {
        List<String> items = Routine.items(c);
        Set<String> checked = Routine.checked(c);
        int n = 0;
        for (String i : items) if (checked.contains(i)) n++;
        float m = items.isEmpty() ? 1 : (float) n / items.size();
        boolean nowDone = Routine.isDoneToday(c);
        if (mood >= 0 && m > mood) {
            body.jump();
            // React to the habit that was just ticked.
            int reaction = BuddyView.NONE;
            if (lastChecked != null) {
                for (String i : items) {
                    if (checked.contains(i) && !lastChecked.contains(i)) {
                        reaction = reactionFor(i);
                        break;
                    }
                }
            }
            if (nowDone) {
                body.startIdle(BuddyView.SPIN);
                say(c.getString(R.string.say_done), 3000);
            } else {
                if (reaction != BuddyView.NONE) {
                    body.startIdle(reaction);
                    idleUntil = SystemClock.uptimeMillis() + 3200; // stop walking to act it out
                }
                say(reactionLine(reaction), 3000);
            }
        }
        lastChecked = new java.util.HashSet<>(checked);
        long now = SystemClock.uptimeMillis();
        if (now - lastLevelCheck > 30_000 || (mood >= 0 && m != mood)) {
            lastLevelCheck = now;
            int level = Levels.level(Levels.xp(c));
            if (body.level > 0 && level > body.level) {
                body.startIdle(BuddyView.SPIN);
                say(c.getString(R.string.say_level, level), 3500);
            }
            body.level = level;
        }
        mood = m;
        done = nowDone;
        body.mood = m;
        body.done = nowDone;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        body.sleepy = !nowDone && (h >= 23 || h < 6);
    }

    private void chatter() {
        List<String> items = Routine.items(c);
        int left = 0;
        Set<String> checked = Routine.checked(c);
        for (String i : items) if (!checked.contains(i)) left++;
        int streak = Routine.streak(c);
        String name = BuddyStyle.name(c);
        String msg;
        if (done) {
            msg = streak > 1 ? pickRes(R.array.say_done_streak, streak, name) : pickRes(R.array.say_done_new, streak, name);
        } else if (mood <= 0) {
            msg = pickRes(R.array.say_nudge, left, name);
        } else {
            // Half the time, name the next habit and its cue: specific beats generic.
            String next = null;
            for (String h : HabitMeta.ordered(c, items)) {
                if (!checked.contains(h)) {
                    next = h;
                    break;
                }
            }
            if (next != null && rnd.nextBoolean()) {
                HabitMeta.Cue cue = HabitMeta.cue(c, next);
                msg = cue.type != HabitMeta.Cue.NONE ? c.getString(R.string.cue_say, cue.describe(c), next)
                        : c.getString(R.string.say_up_next, next);
            } else {
                msg = left == 1 ? c.getString(R.string.say_one_more) : pickRes(R.array.say_progress, left, name);
            }
        }
        say(msg, 4000);
    }

    /** For cue reminders from outside: speak (and hop) if the buddy is currently on screen. */
    boolean sayFromOutside(String text) {
        if (root == null || root.getVisibility() != View.VISIBLE) return false;
        boolean snoozed = Routine.prefs(c).getLong("buddy_hidden_until", 0) > System.currentTimeMillis();
        if (snoozed) return false;
        body.jump();
        body.startIdle(BuddyView.WAVE);
        say(text, 6000);
        return true;
    }

    private void say(String text, long ms) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        cloud.setText(text);
        cloud.animate().cancel();
        int[] size = screen();
        if (!cloudAdded) {
            cloudLp = overlayParams(cloud.wantedWidth(), cloud.wantedHeight());
            cloudLp.alpha = 0f;
            cloudLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            placeCloud(size);
            try {
                wm.addView(cloud, cloudLp);
            } catch (RuntimeException e) {
                return;
            }
            cloudAdded = true;
        }
        if (!cloudShown) {
            cloud.setAlpha(0f);
            cloud.setScaleX(0.5f);
            cloud.setScaleY(0.5f);
        }
        cloudShown = true;
        cloudLp.width = cloud.wantedWidth();
        cloudLp.height = cloud.wantedHeight();
        cloudLp.alpha = 1f;
        cloudLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        placeCloud(size);
        update(cloud, cloudLp);
        cloud.setPivotX(cloud.tailX);
        cloud.setPivotY(cloud.wantedHeight());
        cloud.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).withEndAction(null).start();
        bubbleUntil = SystemClock.uptimeMillis() + ms;
    }

    /** Centre the cloud over the buddy's head (clamped to the screen), tail pointing at the head. */
    private void placeCloud(int[] size) {
        if (cloudLp == null) return;
        float headX = lp.x + bw() / 2f;
        int cx = (int) Math.max(0, Math.min(size[0] - cloudLp.width, headX - cloudLp.width / 2f));
        cloudLp.x = cx;
        cloudLp.y = (int) Math.max(0, lp.y + headTop() - cloudLp.height);
        cloud.setTail(headX - cx);
        if (cloudShown) update(cloud, cloudLp);
    }

    /** Fade the cloud out, then make its window invisible (it isn't removed, so nothing can flash back). */
    private void hideCloud() {
        if (!cloudShown) return;
        cloudShown = false;
        cloud.animate().cancel();
        cloud.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (!cloudShown) parkCloud();
                }).start();
    }

    /** Hide immediately, no animation. */
    private void removeCloudNow() {
        cloudShown = false;
        if (cloud != null) {
            cloud.animate().cancel();
            cloud.setAlpha(0f);
        }
        parkCloud();
    }

    private void parkCloud() {
        if (!cloudAdded) return;
        cloudLp.alpha = 0f;
        cloudLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        update(cloud, cloudLp);
    }

    /** A random line from a string array; lines may use %1$d (a number) and %2$s (the buddy's name). */
    private String pickRes(int array, Object... args) {
        String[] options = c.getResources().getStringArray(array);
        String line = options[rnd.nextInt(options.length)];
        return args.length == 0 ? line : String.format(line, args);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    /** A white cartoon cloud with puffy bumps and a little thought-bubble tail; text centred inside. */
    static class CloudView extends View {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG),
                textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cloud = new Path();
        private String text = "";
        float tailX;

        CloudView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            fill.setColor(0xFFF7F8FB);
            shadow.setColor(0x40000000);
            textPaint.setColor(0xFF1B1E26);
            textPaint.setTextSize(13.5f * d);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        private float bodyH() {
            return 34 * d;
        }

        private float topPad() {
            return 9 * d; // room for the bumps above the body
        }

        private float tailH() {
            return 16 * d;
        }

        int wantedWidth() {
            return (int) Math.max(96 * d, textPaint.measureText(text) + 44 * d);
        }

        int wantedHeight() {
            return (int) (topPad() + bodyH() + tailH() + 3 * d);
        }

        void setText(String t) {
            text = t;
            requestLayout();
            invalidate();
        }

        void setTail(float x) {
            if (Math.abs(x - tailX) < 0.5f) return;
            tailX = x;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(wantedWidth(), wantedHeight());
        }

        private void buildCloud(float w) {
            float top = topPad(), bh = bodyH(), bottom = top + bh, inset = 4 * d;
            cloud.reset();
            cloud.addRoundRect(new RectF(inset, top, w - inset, bottom), bh / 2, bh / 2, Path.Direction.CW);
            // Puffy bumps along the top, evenly spaced, the middle ones a bit bigger.
            int n = Math.max(2, (int) ((w - 2 * inset) / (30 * d)));
            float span = w - 2 * inset - bh * 0.6f;
            for (int i = 0; i < n; i++) {
                float fx = n == 1 ? 0.5f : (float) i / (n - 1);
                float bx = inset + bh * 0.3f + span * fx;
                float br = bh * (0.42f + 0.14f * (float) Math.sin(fx * Math.PI));
                Path bump = new Path();
                bump.addCircle(bx, top + bh * 0.38f, br, Path.Direction.CW);
                cloud.op(bump, Path.Op.UNION);
            }
            // A couple of smaller bumps underneath.
            for (int i = 1; i < n; i++) {
                float bx = inset + bh * 0.3f + span * ((i - 0.5f) / (n - 1));
                Path bump = new Path();
                bump.addCircle(bx, bottom - bh * 0.2f, bh * 0.3f, Path.Direction.CW);
                cloud.op(bump, Path.Op.UNION);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            buildCloud(w);
            float tx = Math.max(14 * d, Math.min(w - 14 * d, tailX));
            float bottom = topPad() + bodyH();
            // Thought-bubble tail: two shrinking puffs leading down to the buddy's head.
            c.save();
            c.translate(0, 1.5f * d);
            c.drawPath(cloud, shadow);
            c.drawCircle(tx, bottom + 6 * d, 4.5f * d, shadow);
            c.drawCircle(tx, bottom + 13 * d, 2.6f * d, shadow);
            c.restore();
            c.drawPath(cloud, fill);
            c.drawCircle(tx, bottom + 6 * d, 4.5f * d, fill);
            c.drawCircle(tx, bottom + 13 * d, 2.6f * d, fill);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = topPad() + bodyH() / 2 + 2 * d;
            c.drawText(text, w / 2, cy - (fm.ascent + fm.descent) / 2, textPaint);
        }
    }

    /** The glowing hole the buddy travels through, with a star burst and a closing flash. */
    static class PortalView extends View {
        float open, glow, burst = -1, ring = -1, ground;
        private final float dd;
        private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                halo = new Paint(Paint.ANTI_ALIAS_FLAG), star = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final android.graphics.Path sp = new android.graphics.Path();

        PortalView(Context c) {
            super(c);
            dd = c.getResources().getDisplayMetrics().density;
            rim.setStyle(Paint.Style.STROKE);
        }

        void reset() {
            open = 0;
            glow = 0;
            burst = -1;
            ring = -1;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, rx = getWidth() * 0.46f * Math.max(0, open), ry = rx * 0.3f;
            if (rx > 0.5f) {
                // Soft halo, then the dark hole with a violet depth, then a bright rim.
                halo.setShader(new android.graphics.RadialGradient(cx, ground, rx * 1.5f,
                        new int[]{0x99B3A3FF, 0x337C5CFF, 0x00000000}, new float[]{0, 0.6f, 1},
                        android.graphics.Shader.TileMode.CLAMP));
                c.save();
                c.scale(1, 0.32f, cx, ground);
                c.drawCircle(cx, ground, rx * 1.5f, halo);
                c.restore();
                hole.setShader(new android.graphics.RadialGradient(cx, ground, rx,
                        new int[]{0xFF07051A, 0xFF1A1240, 0xFF5B3FD9}, new float[]{0, 0.7f, 1},
                        android.graphics.Shader.TileMode.CLAMP));
                r.set(cx - rx, ground - ry, cx + rx, ground + ry);
                c.drawOval(r, hole);
                rim.setStrokeWidth(2 * dd);
                rim.setColor(0xFFD9CFFF);
                c.drawOval(r, rim);
            }
            if (ring >= 0 && ring < 1) {
                float rr = getWidth() * 0.46f * (0.3f + 0.9f * ring);
                rim.setStrokeWidth(3 * dd * (1 - ring));
                rim.setColor(Color.argb((int) (220 * (1 - ring)), 0xD9, 0xCF, 0xFF));
                r.set(cx - rr, ground - rr * 0.3f, cx + rr, ground + rr * 0.3f);
                c.drawOval(r, rim);
            }
            if (burst >= 0 && burst < 1) {
                // Eight little stars fly up and out of the rim, spinning and fading.
                int[] colors = {0xFFFFFFFF, 0xFFFFD66B, 0xFFB3A3FF, 0xFF7DE3B5};
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * (1.08 + 0.84 * i / 7.0);
                    float e = 1 - (1 - burst) * (1 - burst);
                    float dist = getWidth() * (0.25f + 0.25f * (i % 3) / 2f) * e;
                    float px = cx + (float) Math.cos(a) * dist;
                    float py = ground - 4 * dd + (float) Math.sin(a) * dist * 1.1f + burst * burst * 10 * dd;
                    float size = (3.2f - (i % 2)) * dd * (1 - burst * 0.7f);
                    star.setColor(colors[i % colors.length]);
                    star.setAlpha((int) (255 * (1 - burst)));
                    drawStar(c, px, py, size, burst * 3 + i);
                }
            }
        }

        private void drawStar(Canvas c, float x, float y, float s, float rot) {
            sp.reset();
            for (int k = 0; k < 8; k++) {
                double a = rot + k * Math.PI / 4;
                float rr = k % 2 == 0 ? s : s * 0.38f;
                float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
                if (k == 0) sp.moveTo(px, py);
                else sp.lineTo(px, py);
            }
            sp.close();
            c.drawPath(sp, star);
        }
    }

    /** The creature: a soft blob with feet, eyes and a mouth that all follow its mood. */
    static class BuddyView extends View {
        float mood;
        static final int NONE = 0, LOOK = 1, WAVE = 2, DANCE = 3, SIGH = 4, SPIN = 5,
                WATER = 6, BOOK = 7, WORKOUT = 8, ZEN = 9;
        /** Buddy level: evolves with a sprout (3), a glow (5) and wings (8). */
        int level;
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean done, walking, sleepy, held;
        private int action = NONE;
        private long actionAt;
        int facing = 1;
        /** 0 = mood colours; otherwise fade from grey to this colour as mood rises. */
        int color;
        Accessories.Def accessory;
        /** For previews outside the overlay: keep redrawing on our own. */
        boolean selfAnimate;
        /** Draw as a dark shadow shape: a teaser for locked accessories. */
        boolean silhouette;
        private static final Paint SILHOUETTE = new Paint();

        static {
            SILHOUETTE.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF3A3F4C,
                    android.graphics.PorterDuff.Mode.SRC_IN));
        }
        private final float density;
        private float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final long born = SystemClock.uptimeMillis();
        private long jumpAt = -10_000;

        BuddyView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            d = density;
        }

        void setScale(float s) {
            d = density * s;
            invalidate();
        }

        void jump() {
            jumpAt = SystemClock.uptimeMillis();
        }

        void startIdle(int a) {
            action = a;
            actionAt = SystemClock.uptimeMillis();
        }

        private static float clamp01(float v) {
            return Math.max(0, Math.min(1, v));
        }

        private static int lerp(int a, int b, float t) {
            t = Math.max(0, Math.min(1, t));
            return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }

        @Override
        protected void onDraw(Canvas c) {
            float t = (SystemClock.uptimeMillis() - born) / 1000f;
            float w = getWidth(), h = getHeight(), cx = w / 2, ground = h - 6 * d;
            float m = Math.max(0, mood);
            float at = (SystemClock.uptimeMillis() - actionAt) / 1000f; // seconds into the idle action
            int act = walking || held ? NONE : action;

            // Hop height: tiny shuffle when sad, real bounce when happy; extra jumps on events / when done.
            float stepRate = held ? 14 : 4 + 6 * m;
            boolean moving = walking || held; // feet kick while dangling
            float hop = walking && !held ? Math.abs((float) Math.sin(t * stepRate)) * (1 + 6 * m) * d : 0;
            float since = (SystemClock.uptimeMillis() - jumpAt) / 1000f;
            // Squash & stretch: crouch, stretch on the way up, squash on landing.
            float sx = 1, sy = 1;
            if (since < 0.08f) {
                sy = 0.85f; sx = 1.12f;
            } else if (since < 0.6f) {
                float k = (since - 0.08f) / 0.52f;
                hop += (float) Math.sin(k * Math.PI) * 22 * d;
                sy = 1 + 0.15f * (float) Math.cos(k * Math.PI);
                sx = 2 - sy;
            } else if (since < 0.75f) {
                float k = (since - 0.6f) / 0.15f;
                sy = 0.82f + 0.18f * k; sx = 1.15f - 0.15f * k;
            }
            if (done && act == NONE && !walking) hop += Math.max(0, (float) Math.sin(t * 3)) * 6 * d;
            if (act == DANCE) hop += Math.abs((float) Math.sin(at * 6)) * 5 * d;
            boolean reacting = (act == WATER || act == BOOK || act == WORKOUT || act == ZEN) && at < 3.2f;
            if (reacting && act == WORKOUT) hop += Math.abs((float) Math.sin(at * 9)) * 7 * d; // jumping jacks
            if (reacting && act == ZEN) hop += (6 + (float) Math.sin(at * 2.5f) * 3) * d * Math.min(1, at * 2); // float
            float breathe = (float) Math.sin(t * 2) * d;
            if (act == SIGH && at < 1.6f) breathe -= (float) Math.sin(at / 1.6f * Math.PI) * 3 * d;

            // Shadow (shrinks as it rises).
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55000000);
            float sw = 22 * d * (1 - Math.min(0.5f, hop / (40 * d)));
            r.set(cx - sw, ground - 3 * d, cx + sw, ground + 3 * d);
            c.drawOval(r, p);

            float bodyW = 26 * d, bodyH = 22 * d + breathe;
            float sag = (1 - m) * 3 * d;
            float bottom = ground - 5 * d - hop;
            float top = bottom - bodyH * 2 + sag;
            int col = color != 0 ? lerp(0xFF6B7280, color, 0.25f + 0.75f * m)
                    : m < 0.5f ? lerp(0xFF6B7280, MainActivity.ACCENT, m * 2)
                    : lerp(MainActivity.ACCENT, MainActivity.GOOD, (m - 0.5f) * 2);

            c.save();
            // Whole-body transforms: squash/stretch from the feet, dance sway, celebration spin, dangling tilt.
            c.scale(sx, sy, cx, bottom);
            if (act == DANCE) c.rotate((float) Math.sin(at * 6) * 10, cx, bottom);
            if (act == SPIN && at < 0.9f) c.rotate(360 * clamp01(at / 0.9f), cx, (top + bottom) / 2);
            if (held) c.rotate((float) Math.sin(t * 8) * 8, cx, top);

            float midY = (top + bottom) / 2;
            // Evolution: soft pulsing aura (level 5+).
            if (level >= Levels.GLOW) {
                float gr = bodyW * 1.7f;
                int a = (int) (90 + 40 * Math.sin(t * 2));
                glow.setShader(new android.graphics.RadialGradient(cx, midY, gr,
                        new int[]{Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)), 0x00000000},
                        null, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, midY, gr, glow);
            }
            // Evolution: little flapping wings (level 8+), behind the body.
            if (level >= Levels.WINGS) {
                float flap = (float) Math.sin(t * (walking || done ? 10 : 3)) * 18;
                p.setColor(0xCCFFFFFF);
                for (int s = -1; s <= 1; s += 2) {
                    c.save();
                    float wx = cx + s * (bodyW + 2 * d), wy = top + bodyH * 0.7f;
                    c.rotate(s * (-25 + flap), wx, wy);
                    r.set(wx - (s > 0 ? 0 : 16 * d), wy - 7 * d, wx + (s > 0 ? 16 * d : 0), wy + 7 * d);
                    c.drawOval(r, p);
                    c.restore();
                }
            }

            // Feet, alternating while walking.
            p.setColor(lerp(col, Color.BLACK, 0.35f));
            float step = moving ? (float) Math.sin(t * stepRate) * 4 * d : 0;
            r.set(cx - 15 * d + step, bottom - 4 * d, cx - 5 * d + step, bottom + 4 * d);
            c.drawOval(r, p);
            r.set(cx + 5 * d - step, bottom - 4 * d, cx + 15 * d - step, bottom + 4 * d);
            c.drawOval(r, p);

            // Arms: hang when sad, swing when walking, wave / flail / dance otherwise.
            float shoulderY = top + bodyH * 1.2f;
            float armLen = 11 * d;
            float rest = 60 - 50 * m; // degrees below horizontal: droopy when sad
            float left = rest, right = rest;
            if (walking) {
                float swing = (float) Math.sin(t * stepRate) * (10 + 25 * m);
                left += swing;
                right -= swing;
            }
            if (held) {
                left = -50 + (float) Math.sin(t * 16) * 25;
                right = -50 - (float) Math.sin(t * 16) * 25;
            } else if (act == WAVE && at < 1.8f) {
                float wave = -60 + (float) Math.sin(at * 14) * 30;
                if (facing > 0) right = wave; else left = wave;
            } else if (act == DANCE) {
                left = -30 + (float) Math.sin(at * 6) * 40;
                right = -30 - (float) Math.sin(at * 6) * 40;
            } else if (reacting && act == WORKOUT) {
                boolean up = Math.sin(at * 9) > 0;
                left = right = up ? -75 : 55;
            } else if (reacting && act == WATER) {
                if (facing > 0) right = -15; else left = -15; // holding the glass up
            } else if (reacting && act == BOOK) {
                left = right = 25; // holding the book in front
            } else if (reacting && act == ZEN) {
                left = right = 0; // arms out, balanced
            } else if (act == SPIN || (done && !walking)) {
                left = right = -45;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(5 * d);
            p.setColor(lerp(col, Color.BLACK, 0.2f));
            drawArm(c, cx - bodyW - sag + 2 * d, shoulderY, -1, left, armLen);
            drawArm(c, cx + bodyW + sag - 2 * d, shoulderY, 1, right, armLen);
            p.setStyle(Paint.Style.FILL);

            // Body.
            p.setColor(col);
            r.set(cx - bodyW - sag, top, cx + bodyW + sag, bottom);
            c.drawRoundRect(r, bodyW, bodyW * 0.9f, p);
            // Soft highlight.
            p.setColor(0x22FFFFFF);
            r.set(cx - bodyW * 0.6f, top + 4 * d, cx + bodyW * 0.1f, top + bodyH * 0.7f);
            c.drawOval(r, p);

            // Cheeks when happy.
            if (m > 0.6f) {
                p.setColor(0x55FF7AA8);
                c.drawCircle(cx - 15 * d + facing * 3 * d, top + bodyH * 1.15f, 4 * d, p);
                c.drawCircle(cx + 15 * d + facing * 3 * d, top + bodyH * 1.15f, 4 * d, p);
            }

            // Eyes: blink every ~4s, half-closed when sad or sleepy, pupils look where it walks (or look around).
            float eyeY = top + bodyH * 0.8f, eyeDx = 9 * d;
            float look = facing * 2 * d;
            if (act == LOOK && at < 2.4f) look = (float) Math.sin(at * 2.6f) * 3.5f * d;
            boolean blink = (t % 4f) < 0.12f;
            float open = held ? 1.1f : sleepy ? 0.2f : blink ? 0.1f : 0.55f + 0.45f * Math.min(1, m + 0.3f);
            if (act == SIGH && at < 1.6f) open = Math.min(open, 0.25f);
            boolean happyEyes = !held && ((done && (act == DANCE || act == SPIN))
                    || (reacting && (act == WATER || act == ZEN)));
            if (reacting && act == BOOK) look = 0; // reading: eyes down at the book
            for (int s = -1; s <= 1; s += 2) {
                float ex = cx + s * eyeDx + look * 0.5f;
                if (happyEyes) {
                    // ^ ^ closed happy eyes
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * d);
                    p.setColor(0xFF1B1E26);
                    path.reset();
                    path.moveTo(ex - 4 * d, eyeY + 1 * d);
                    path.quadTo(ex, eyeY - 5 * d, ex + 4 * d, eyeY + 1 * d);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL);
                    continue;
                }
                p.setColor(Color.WHITE);
                r.set(ex - 5 * d, eyeY - 6 * d * open, ex + 5 * d, eyeY + 6 * d * open);
                c.drawOval(r, p);
                if (open > 0.15f) {
                    p.setColor(0xFF1B1E26);
                    c.drawCircle(ex + look * 0.6f, eyeY + (1 - m) * 1.5f * d, 2.6f * d, p);
                    p.setColor(Color.WHITE);
                    c.drawCircle(ex + look * 0.6f + d, eyeY + (1 - m) * 1.5f * d - d, 0.9f * d, p);
                }
            }

            // Mouth: frown -> smile; open grin when done; "o" when picked up.
            float mouthY = top + bodyH * 1.35f + sag * 0.5f;
            p.setColor(0xFF1B1E26);
            if (held) {
                c.drawCircle(cx + look * 0.5f, mouthY + 1 * d, 3 * d, p);
            } else {
                float curve = (m - 0.35f) * 10 * d; // negative = frown
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(cx - 7 * d + look * 0.5f, mouthY);
                path.quadTo(cx + look * 0.5f, mouthY + curve, cx + 7 * d + look * 0.5f, mouthY);
                if (done) {
                    p.setStyle(Paint.Style.FILL);
                    path.close();
                }
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
            }
            // Evolution: a sprout on its head (level 3+), moved aside if a hat is in the way.
            if (level >= Levels.SPROUT) {
                boolean hat = accessory != null && accessory.hat;
                float sx0 = hat ? cx - 13 * d : cx, sy0 = top + 1 * d;
                float sway = (float) Math.sin(t * 2.2f) * 2 * d;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0xFF3FAF6B);
                path.reset();
                path.moveTo(sx0, sy0);
                path.quadTo(sx0 - 1 * d, sy0 - 5 * d, sx0 + sway, sy0 - 9 * d);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF4CD787);
                c.save();
                c.rotate(-35, sx0 + sway, sy0 - 9 * d);
                r.set(sx0 + sway - 9 * d, sy0 - 12 * d, sx0 + sway, sy0 - 7 * d);
                c.drawOval(r, p);
                c.restore();
                c.save();
                c.rotate(35, sx0 + sway, sy0 - 9 * d);
                r.set(sx0 + sway, sy0 - 12 * d, sx0 + sway + 9 * d, sy0 - 7 * d);
                c.drawOval(r, p);
                c.restore();
            }
            drawAccessory(c, cx, top, bodyW, bodyH, eyeY, eyeDx, look);
            if (reacting) drawProp(c, act, at, cx, top, bodyW, bodyH, bottom);
            c.restore();

            // Sparkles when done, a sigh puff, Zzz when sleepy, motion lines while dangling.
            if (done) {
                p.setColor(0xFFFFD166);
                for (int i = 0; i < 3; i++) {
                    float phase = (t * 0.8f + i / 3f) % 1f;
                    float spx = cx + (i - 1) * 22 * d, spy = top - 4 * d - phase * 14 * d;
                    float sz = (1 - phase) * 3.5f * d;
                    c.save();
                    c.rotate(45, spx, spy);
                    c.drawRect(spx - sz, spy - sz, spx + sz, spy + sz, p);
                    c.restore();
                }
            } else if (act == SIGH && at < 1.6f) {
                float k = at / 1.6f;
                p.setColor(Color.argb((int) (160 * (1 - k)), 200, 205, 215));
                c.drawCircle(cx + facing * (16 + 10 * k) * d, top + bodyH * 1.3f - 6 * k * d, (2 + 3 * k) * d, p);
            } else if (sleepy) {
                p.setColor(MainActivity.MUTED);
                p.setTextSize(11 * d);
                float phase = (t * 0.5f) % 1f;
                c.drawText("z", cx + 18 * d, top - phase * 10 * d, p);
            }
            if (held) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(0x88FFFFFF);
                float wob = (float) Math.sin(t * 20) * 2 * d;
                c.drawLine(cx - bodyW - 8 * d, top + 6 * d + wob, cx - bodyW - 14 * d, top + 2 * d + wob, p);
                c.drawLine(cx + bodyW + 8 * d, top + 6 * d - wob, cx + bodyW + 14 * d, top + 2 * d - wob, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            selfAnimate = false;
            super.onDetachedFromWindow();
        }

        @Override
        public void draw(Canvas c) {
            if (silhouette) {
                // Render into a layer, then flatten every pixel to one shadow colour.
                int save = c.saveLayer(null, SILHOUETTE);
                super.draw(c);
                c.restoreToCount(save);
            } else {
                super.draw(c);
            }
            if (selfAnimate) postInvalidateOnAnimation();
        }

        /** Props for habit reactions: a glass of water, a book, a sweat drop, a calm halo. */
        private void drawProp(Canvas c, int act, float at, float cx, float top, float bodyW, float bodyH,
                              float bottom) {
            p.setStyle(Paint.Style.FILL);
            switch (act) {
                case WATER: {
                    float gx = cx + facing * (bodyW - 2 * d), gy = top + bodyH * 1.1f;
                    float tilt = facing * Math.min(1, at * 2) * 35;
                    c.save();
                    c.rotate(tilt, gx, gy);
                    p.setColor(0x99DDEEFF);
                    r.set(gx - 5 * d, gy - 8 * d, gx + 5 * d, gy + 8 * d);
                    c.drawRoundRect(r, 2 * d, 2 * d, p);
                    float waterH = 8 * d * (1 - Math.min(1, at / 2.5f));
                    p.setColor(0xFF3BA8FF);
                    r.set(gx - 4 * d, gy + 7 * d - waterH * 1.6f, gx + 4 * d, gy + 7 * d);
                    c.drawRect(r, p);
                    c.restore();
                    break;
                }
                case BOOK: {
                    float by = bottom - bodyH * 0.55f;
                    p.setColor(0xFFB5651D);
                    r.set(cx - 15 * d, by - 9 * d, cx + 15 * d, by + 9 * d);
                    c.drawRoundRect(r, 2 * d, 2 * d, p);
                    p.setColor(0xFFF7F3E8);
                    r.set(cx - 13 * d, by - 8 * d, cx - 1 * d, by + 7 * d);
                    c.drawRect(r, p);
                    r.set(cx + 1 * d, by - 8 * d, cx + 13 * d, by + 7 * d);
                    c.drawRect(r, p);
                    p.setColor(0x55000000);
                    for (int k = 0; k < 3; k++) {
                        float ly = by - 5 * d + k * 4 * d;
                        c.drawRect(cx - 11 * d, ly, cx - 3 * d, ly + 0.8f * d, p);
                        c.drawRect(cx + 3 * d, ly, cx + 11 * d, ly + 0.8f * d, p);
                    }
                    break;
                }
                case WORKOUT: {
                    float phase = (at * 1.5f) % 1f;
                    p.setColor(Color.argb((int) (220 * (1 - phase)), 120, 200, 255));
                    float sx = cx + bodyW * 0.8f, sy = top + 4 * d + phase * 10 * d;
                    path.reset();
                    path.moveTo(sx, sy - 4 * d);
                    path.quadTo(sx + 3 * d, sy + 1 * d, sx, sy + 2.5f * d);
                    path.quadTo(sx - 3 * d, sy + 1 * d, sx, sy - 4 * d);
                    c.drawPath(path, p);
                    break;
                }
                case ZEN: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * d);
                    float pulse = (at * 0.8f) % 1f;
                    p.setColor(Color.argb((int) (160 * (1 - pulse)), 255, 255, 255));
                    c.drawCircle(cx, (top + bottom) / 2, bodyW * (1.1f + pulse * 0.6f), p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                default:
                    break;
            }
        }

        private final Accessories.Ctx accCtx = new Accessories.Ctx();

        /** Accessories are data (see {@link Accessories}); this just hands over the live measurements. */
        private void drawAccessory(Canvas c, float cx, float top, float bodyW, float bodyH, float eyeY, float eyeDx,
                                   float look) {
            Accessories.Ctx k = accCtx;
            k.d = d;
            k.cx = cx;
            k.top = top;
            k.eyeY = eyeY;
            k.bodyW = bodyW;
            k.bodyH = bodyH;
            k.eyeDx = eyeDx;
            k.look = look;
            Accessories.draw(c, p, accessory, k);
        }

        /** Arm from the shoulder; {@code deg} below horizontal (negative = raised), mirrored by {@code side}. */
        private void drawArm(Canvas c, float sx, float sy, int side, float deg, float len) {
            double rad = Math.toRadians(deg);
            float ex = sx + side * (float) Math.cos(rad) * len;
            float ey = sy + (float) Math.sin(rad) * len;
            c.drawLine(sx, sy, ex, ey, p);
        }
    }
}
