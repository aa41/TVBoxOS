package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

import xyz.doikki.videoplayer.util.CutoutUtil;

public class BaseDialog extends Dialog {
    public BaseDialog(@NonNull Context context) {
        super(context, R.style.CustomDialogStyle);
    }

    public BaseDialog(Context context, int customDialogStyle) {
        super(context, customDialogStyle);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CutoutUtil.adaptCutoutAboveAndroidP(this, true);//设置刘海
        super.onCreate(savedInstanceState);
    }

    @Override
    public void show() {
        if (isContextInvalid()) {
            return;
        }
        Window window = getWindow();
        if (window == null) return;
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        super.show();
        hideSysBar();
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (getContext().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT) {
            applyPortraitWindow(window);
        }
    }

    private void applyPortraitWindow(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.58f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        View content = window.findViewById(android.R.id.content);
        if (content != null) {
            content.post(new Runnable() {
                @Override
                public void run() {
                    int inset = dp(16);
                    int width = content.getWidth() > 0 ? content.getWidth()
                            : getContext().getResources().getDisplayMetrics().widthPixels;
                    int height = content.getHeight() > 0 ? content.getHeight()
                            : getContext().getResources().getDisplayMetrics().heightPixels;
                    fitPortraitChildren(content, Math.max(1, width - inset * 2),
                            Math.max(1, height - inset * 2));
                }
            });
        }
    }

    private void fitPortraitChildren(View view, int availableWidth, int availableHeight) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int innerWidth = Math.max(1, availableWidth - group.getPaddingLeft() - group.getPaddingRight());
        int innerHeight = Math.max(1, availableHeight - group.getPaddingTop() - group.getPaddingBottom());
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params == null) continue;
            int horizontalMargins = 0;
            int verticalMargins = 0;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                horizontalMargins = margins.leftMargin + margins.rightMargin;
                verticalMargins = margins.topMargin + margins.bottomMargin;
            }
            int childMaxWidth = Math.max(1, innerWidth - horizontalMargins);
            int childMaxHeight = Math.max(1, innerHeight - verticalMargins);
            boolean changed = false;
            if ((params.width > childMaxWidth
                    || params.width == ViewGroup.LayoutParams.WRAP_CONTENT
                    && child.getMeasuredWidth() > childMaxWidth)) {
                params.width = childMaxWidth;
                changed = true;
            }
            if (params.height > childMaxHeight) {
                params.height = childMaxHeight;
                changed = true;
            }
            if (changed) child.setLayoutParams(params);
            int nextWidth = params.width > 0 ? Math.min(params.width, childMaxWidth) : childMaxWidth;
            int nextHeight = params.height > 0 ? Math.min(params.height, childMaxHeight) : childMaxHeight;
            fitPortraitChildren(child, nextWidth, nextHeight);
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private boolean isContextInvalid() {
        Context context = getContext();
        if (!(context instanceof Activity)) {
            return false;
        }
        Activity activity = (Activity) context;
        return activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed());
    }

    private void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }
}
