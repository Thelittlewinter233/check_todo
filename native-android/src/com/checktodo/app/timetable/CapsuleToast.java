package com.checktodo.app.timetable;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

/**
 * 胶囊式提醒：从底部淡入、短暂停留、自动淡出，不打断任何操作。
 *
 * <p><b>为什么不用 Toast/对话框：</b>用户明确反馈「编辑过程中每次改动都弹提醒会被打断」。
 * Toast 在部分 ROM 上排队严重、无法控制位置；对话框则强制用户确认。胶囊条是「告知」而非
 * 「阻断」——用户可以不理会它继续操作，它自己会消失。</p>
 *
 * <p><b>为什么自己实现而不是用 Snackbar：</b>本工程零第三方依赖（连 Material 都没有），
 * 只有 appcompat；为一个提示条引入 Material 会显著增包。这里用一个带圆角背景的
 * LinearLayout + alpha 动画即可，行为完全可控。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>浮层不占布局空间：布局里用负底边距抵消自身高度；</li>
 *   <li>连续调用会<b>重置</b>计时（后者覆盖前者），不会堆叠出多行；</li>
 *   <li>所有动画/延时都在主线程，{@link #hide()} 可提前收起（如 Activity 销毁）。</li>
 * </ul>
 */
final class CapsuleToast {

    /** 默认停留时长：够看清一条 20 字以内的提示，又不至于挡太久。 */
    private static final long DEFAULT_DURATION_MS = 2600L;
    private static final long FADE_IN_MS = 160L;
    private static final long FADE_OUT_MS = 220L;

    private final Activity activity;
    private final View bar;
    private final TextView text;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideTask;

    private boolean hidden = true;

    CapsuleToast(Activity activity, View bar, TextView text) {
        this.activity = activity;
        this.bar = bar;
        this.text = text;
        this.hideTask = new Runnable() {
            @Override
            public void run() {
                hide();
            }
        };
    }

    /** 从 Activity 根布局里找胶囊条；找不到时返回 null（调用方需判空）。 */
    static CapsuleToast create(Activity activity) {
        if (activity == null) return null;
        View bar = activity.findViewById(com.checktodo.app.R.id.capsule_bar);
        TextView text = activity.findViewById(com.checktodo.app.R.id.capsule_text);
        if (bar == null || text == null) return null;
        return new CapsuleToast(activity, bar, text);
    }

    /** 显示一条提示；重复调用会覆盖上一条并重新计时。 */
    void show(CharSequence message) {
        show(message, DEFAULT_DURATION_MS);
    }

    void show(CharSequence message, long durationMs) {
        if (message == null || message.length() == 0) return;
        handler.removeCallbacks(hideTask);
        text.setText(message);
        if (hidden) {
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            hidden = false;
        } else {
            // 已显示：只换文案，轻微"弹一下"提示内容更新。
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        }
        handler.postDelayed(hideTask, Math.max(600L, durationMs));
    }

    /** 立即淡出。幂等。 */
    void hide() {
        handler.removeCallbacks(hideTask);
        if (hidden) return;
        hidden = true;
        bar.animate().cancel();
        bar.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                bar.setVisibility(View.GONE);
            }
        }).start();
    }

    /** 宿主销毁时调用，避免延时任务泄漏。 */
    void release() {
        handler.removeCallbacksAndMessages(null);
        hidden = true;
        bar.animate().cancel();
        bar.setVisibility(View.GONE);
    }
}
