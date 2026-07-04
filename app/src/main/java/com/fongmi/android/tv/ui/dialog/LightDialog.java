package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

public final class LightDialog {

    private LightDialog() {
    }

    public static Dialog create(Context context, CharSequence title, View content) {
        return create(context, title, content, null, null, null, null);
    }

    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative) {
        return create(context, title, content, positive, onPositive, negative, onNegative, null, null);
    }

    public static Dialog create(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, String neutral, View.OnClickListener onNeutral) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root(context, title, content, positive, listener(dialog, onPositive), negative, listener(dialog, onNegative), neutral, listener(dialog, onNeutral)));
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(d -> applyWindow(dialog, context));
        return dialog;
    }

    private static View.OnClickListener listener(Dialog dialog, View.OnClickListener listener) {
        return view -> {
            if (listener != null) listener.onClick(view);
            else dialog.dismiss();
        };
    }

    private static View root(Context context, CharSequence title, View content, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, String neutral, View.OnClickListener onNeutral) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.shape_shell_proxy_dialog);
        int padding = ResUtil.dp2px(24);
        root.setPadding(padding, padding, padding, padding);

        if (title != null) {
            MaterialTextView titleView = new MaterialTextView(context);
            titleView.setText(title);
            titleView.setTextColor(Color.parseColor("#202124"));
            titleView.setTextSize(18);
            titleView.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (content != null) {
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            contentParams.topMargin = title == null ? 0 : ResUtil.dp2px(16);
            root.addView(content, contentParams);
        }

        if (positive != null || negative != null || neutral != null) root.addView(actions(context, positive, onPositive, negative, onNegative, neutral, onNeutral), actionParams());
        return root;
    }

    private static LinearLayout actions(Context context, String positive, View.OnClickListener onPositive, String negative, View.OnClickListener onNegative, String neutral, View.OnClickListener onNeutral) {
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (neutral != null) actions.addView(button(context, neutral, false, onNeutral));
        if (negative != null) actions.addView(button(context, negative, false, onNegative));
        if (positive != null) actions.addView(button(context, positive, true, onPositive));
        return actions;
    }

    private static LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(18);
        return params;
    }

    private static MaterialButton button(Context context, String text, boolean primary, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setMinWidth(ResUtil.dp2px(88));
        button.setMinHeight(ResUtil.dp2px(40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setTextColor(ContextCompat.getColorStateList(context, primary ? R.color.dialog_primary_button_text : R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, primary ? R.color.dialog_primary_button_bg : R.color.dialog_outlined_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(primary ? 0 : ResUtil.dp2px(1));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(40));
        params.leftMargin = ResUtil.dp2px(12);
        button.setLayoutParams(params);
        return button;
    }

    private static void applyWindow(Dialog dialog, Context context) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(context);
        params.width = Math.min(Math.round(ResUtil.getScreenWidth(context) * (land ? 0.52f : 0.9f)), ResUtil.dp2px(560));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.58f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }
}
