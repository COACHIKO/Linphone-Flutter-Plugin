package com.linphoneflutterplugin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Helper class to apply smooth animations to the incoming call screen
 * Creates a beautiful, modern Android 15-style animated UI
 */
public class IncomingCallAnimationHelper {

    /**
     * Start all animations when the incoming call screen appears
     */
    public static void startAllAnimations(
            View pulseRingOuter,
            View pulseRingInner,
            View avatarContainer,
            LinearLayout callerInfo,
            FrameLayout swipeContainer,
            LinearLayout declineHint,
            LinearLayout acceptHint,
            FrameLayout swipeButton,
            View swipeInstruction) {
        // Pulse animations for avatar rings
        startPulseAnimation(pulseRingOuter, 2000, 0);
        startPulseAnimation(pulseRingInner, 1600, 400);

        // Avatar bounce in animation
        startBounceInAnimation(avatarContainer, 600);

        // Caller info fade and slide up
        startSlideUpAnimation(callerInfo, 500, 200);

        // Swipe container slide up from bottom
        startSlideUpAnimation(swipeContainer, 600, 400);

        // Swipe hints animate left/right
        startSwipeHintAnimation(declineHint, true);
        startSwipeHintAnimation(acceptHint, false);

        // Swipe button gentle pulse
        startButtonPulseAnimation(swipeButton);

        // Instruction text fade in/out
        startFadeAnimation(swipeInstruction, 1500, 600);
    }

    /**
     * Pulsing animation for avatar rings
     */
    private static void startPulseAnimation(View view, long duration, long startDelay) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.9f, 1.2f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.9f, 1.2f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0.6f, 0.0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY, alpha);
        animatorSet.setDuration(duration);
        animatorSet.setStartDelay(startDelay);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setScaleX(0.9f);
                view.setScaleY(0.9f);
                view.setAlpha(0.6f);
                animatorSet.start();
            }
        });
        animatorSet.start();
    }

    /**
     * Bounce in animation with overshoot
     */
    private static void startBounceInAnimation(View view, long duration) {
        view.setScaleX(0f);
        view.setScaleY(0f);
        view.setAlpha(0f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY, alpha);
        animatorSet.setDuration(duration);
        animatorSet.setInterpolator(new OvershootInterpolator(1.5f));
        animatorSet.start();
    }

    /**
     * Slide up animation from bottom
     */
    private static void startSlideUpAnimation(View view, long duration, long startDelay) {
        view.setTranslationY(100f);
        view.setAlpha(0f);

        ObjectAnimator translateY = ObjectAnimator.ofFloat(view, "translationY", 100f, 0f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(translateY, alpha);
        animatorSet.setDuration(duration);
        animatorSet.setStartDelay(startDelay);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.start();
    }

    /**
     * Swipe hint animation - subtle left/right movement
     */
    private static void startSwipeHintAnimation(View view, boolean moveLeft) {
        float delta = moveLeft ? -15f : 15f;

        ObjectAnimator translateX = ObjectAnimator.ofFloat(view, "translationX", 0f, delta, 0f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0.6f, 1.0f, 0.6f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(translateX, alpha);
        animatorSet.setDuration(1200);
        animatorSet.setStartDelay(800);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.postDelayed(() -> animatorSet.start(), 500);
            }
        });
        animatorSet.start();
    }

    /**
     * Gentle pulse animation for the swipe button
     */
    private static void startButtonPulseAnimation(View view) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 1.1f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 1.1f, 1.0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.setDuration(2000);
        animatorSet.setStartDelay(1000);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.postDelayed(() -> animatorSet.start(), 300);
            }
        });
        animatorSet.start();
    }

    /**
     * Fade in/out animation
     */
    private static void startFadeAnimation(View view, long duration, long startDelay) {
        view.setAlpha(0f);

        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 0.8f, 0.5f, 0.8f);
        alpha.setDuration(duration);
        alpha.setStartDelay(startDelay);
        alpha.setInterpolator(new AccelerateDecelerateInterpolator());
        alpha.setRepeatCount(ValueAnimator.INFINITE);
        alpha.setRepeatMode(ValueAnimator.REVERSE);
        alpha.start();
    }

    /**
     * Animate swipe button during drag
     */
    public static void animateSwipeDrag(FrameLayout swipeButton, float translationX, float maxDistance) {
        swipeButton.setTranslationX(translationX);

        // Scale based on distance
        float progress = Math.abs(translationX) / maxDistance;
        float scale = 1.0f + (progress * 0.2f); // Scale up to 1.2x
        swipeButton.setScaleX(scale);
        swipeButton.setScaleY(scale);

        // Rotate slightly
        float rotation = translationX / maxDistance * 15f; // Max 15 degrees
        swipeButton.setRotation(rotation);
    }

    /**
     * Animate swipe button return to center
     */
    public static void animateSwipeReturn(FrameLayout swipeButton) {
        ObjectAnimator translateX = ObjectAnimator.ofFloat(swipeButton, "translationX", swipeButton.getTranslationX(),
                0f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(swipeButton, "scaleX", swipeButton.getScaleX(), 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(swipeButton, "scaleY", swipeButton.getScaleY(), 1.0f);
        ObjectAnimator rotation = ObjectAnimator.ofFloat(swipeButton, "rotation", swipeButton.getRotation(), 0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(translateX, scaleX, scaleY, rotation);
        animatorSet.setDuration(300);
        animatorSet.setInterpolator(new OvershootInterpolator());
        animatorSet.start();
    }

    /**
     * Animate swipe button acceptance (swipe right)
     */
    public static void animateSwipeAccept(FrameLayout swipeButton, View swipeContainer, Runnable onComplete) {
        float targetX = swipeContainer.getWidth();

        ObjectAnimator translateX = ObjectAnimator.ofFloat(swipeButton, "translationX", swipeButton.getTranslationX(),
                targetX);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(swipeButton, "scaleX", swipeButton.getScaleX(), 1.3f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(swipeButton, "scaleY", swipeButton.getScaleY(), 1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(swipeButton, "alpha", 1.0f, 0.0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(translateX, scaleX, scaleY, alpha);
        animatorSet.setDuration(400);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        animatorSet.start();
    }

    /**
     * Animate swipe button decline (swipe left)
     */
    public static void animateSwipeDecline(FrameLayout swipeButton, View swipeContainer, Runnable onComplete) {
        float targetX = -swipeContainer.getWidth();

        ObjectAnimator translateX = ObjectAnimator.ofFloat(swipeButton, "translationX", swipeButton.getTranslationX(),
                targetX);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(swipeButton, "scaleX", swipeButton.getScaleX(), 1.3f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(swipeButton, "scaleY", swipeButton.getScaleY(), 1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(swipeButton, "alpha", 1.0f, 0.0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(translateX, scaleX, scaleY, alpha);
        animatorSet.setDuration(400);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        animatorSet.start();
    }

    /**
     * Animate hint glow when approaching threshold
     */
    public static void animateHintGlow(View hint, boolean isActive) {
        ObjectAnimator alpha = ObjectAnimator.ofFloat(hint, "alpha", hint.getAlpha(), isActive ? 1.0f : 0.6f);
        ObjectAnimator scale = ObjectAnimator.ofFloat(hint, "scaleX", hint.getScaleX(), isActive ? 1.1f : 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(hint, "scaleY", hint.getScaleY(), isActive ? 1.1f : 1.0f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(alpha, scale, scaleY);
        animatorSet.setDuration(200);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.start();
    }

    /**
     * Stop all animations
     */
    public static void stopAllAnimations(View... views) {
        for (View view : views) {
            if (view != null) {
                view.clearAnimation();
                view.animate().cancel();
            }
        }
    }
}
