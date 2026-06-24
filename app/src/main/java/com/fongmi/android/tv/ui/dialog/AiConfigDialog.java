package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.service.AiRecommendationService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class AiConfigDialog {

    private final FragmentActivity activity;
    private Runnable onDismiss;
    private AiConfig config;

    private SwitchMaterial enabled;
    private TextInputEditText endpoint;
    private TextInputEditText apiKey;
    private TextInputEditText model;

    public static AiConfigDialog create(FragmentActivity activity) {
        return new AiConfigDialog(activity);
    }

    private AiConfigDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public AiConfigDialog onDismiss(Runnable callback) {
        this.onDismiss = callback;
        return this;
    }

    public void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_config, null);
        enabled = view.findViewById(R.id.enabled);
        endpoint = view.findViewById(R.id.endpoint);
        apiKey = view.findViewById(R.id.apiKey);
        model = view.findViewById(R.id.model);
        View prompt = view.findViewById(R.id.prompt);
        MaterialButton test = view.findViewById(R.id.test);

        config = AiConfig.objectFrom(Setting.getAiConfig());
        enabled.setChecked(config.isEnabled());
        endpoint.setText(config.getEndpoint());
        apiKey.setText(config.getApiKey());
        model.setText(config.getModel());
        prompt.setOnClickListener(v -> showPromptConfig());
        test.setOnClickListener(v -> testConfig(test));

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_ai_recommendation)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> onSave())
                .setNegativeButton(R.string.dialog_negative, null)
                .setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .show();
    }

    private void showPromptConfig() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_prompt_config, null);
        TextInputEditText recommendPrompt = view.findViewById(R.id.recommendPrompt);
        recommendPrompt.setText(config.getRecommendPrompt());
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_ai_prompt_config)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, null)
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            config.setRecommendPrompt(text(recommendPrompt));
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void onSave() {
        readConfigFromInput();
        Setting.putAiConfig(config.toJson());
    }

    private void testConfig(MaterialButton button) {
        readConfigFromInput();
        button.setEnabled(false);
        Notify.show(R.string.dialog_ai_test_running);
        Task.execute(() -> {
            AiRecommendationService.TestResult result = AiRecommendationService.testConfig(config);
            activity.runOnUiThread(() -> {
                button.setEnabled(true);
                if (result.isSuccess()) {
                    Notify.show(activity.getString(R.string.dialog_ai_test_success, result.getCount(), result.getSampleTitle()));
                } else {
                    Notify.show(activity.getString(R.string.dialog_ai_test_failed, result.getMessage()));
                }
            });
        });
    }

    private void readConfigFromInput() {
        config.setEnabled(enabled.isChecked());
        config.setEndpoint(text(endpoint));
        config.setApiKey(text(apiKey));
        config.setModel(text(model));
    }

    private static String text(TextInputEditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }
}
