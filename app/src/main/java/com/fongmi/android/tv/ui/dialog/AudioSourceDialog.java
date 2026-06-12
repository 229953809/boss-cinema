package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.AudioConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AudioSourceDialog {

    private final FragmentActivity activity;
    private AlertDialog dialog;
    private EditText rulesEdit;
    private Runnable onDismiss;

    public static AudioSourceDialog create(FragmentActivity activity) {
        return new AudioSourceDialog(activity);
    }

    private AudioSourceDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public AudioSourceDialog onDismiss(Runnable callback) {
        this.onDismiss = callback;
        return this;
    }

    public void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_audio_source, null);
        rulesEdit = view.findViewById(R.id.rules);
        View manageBtn = view.findViewById(R.id.manage);

        AudioConfig config = AudioConfig.objectFrom(Setting.getAudioConfig());
        rulesEdit.setText(config.getDisplayRules());
        manageBtn.setOnClickListener(v -> showSiteManage());

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_audio_source)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, this::onSave)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.dialog_audio_site_default, (d, w) -> rulesEdit.setText(AudioConfig.defaultRulesText()))
                .setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .create();
        dialog.show();
    }

    private void onSave(DialogInterface d, int which) {
        List<String> rules = splitRules(rulesEdit.getText().toString());
        String json = "{\"configured\":true,\"enabledSites\":" + toJsonArray(rules) + "}";
        Setting.putAudioConfig(AudioConfig.objectFrom(json).toJson());
    }

    private void showSiteManage() {
        List<Site> sites = VodConfig.get().getSites().stream().filter(s -> s != null && !s.isEmpty()).toList();
        if (sites.isEmpty()) return;

        List<String> current = splitRules(rulesEdit.getText().toString());
        String[] labels = new String[sites.size()];
        boolean[] checked = new boolean[sites.size()];

        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            labels[i] = TextUtils.isEmpty(site.getName()) ? site.getKey() : site.getName() + "  " + site.getKey();
            checked[i] = matchesRule(current, site);
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_audio_site_manage)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < sites.size(); i++)
                        if (checked[i]) selected.add(sites.get(i).getKey());
                    rulesEdit.setText(String.join(";", selected));
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private boolean matchesRule(List<String> rules, Site site) {
        String key = site.getKey() == null ? "" : site.getKey().toLowerCase(Locale.ROOT);
        String name = site.getName() == null ? "" : site.getName().toLowerCase(Locale.ROOT);
        for (String rule : rules) {
            if (TextUtils.isEmpty(rule)) continue;
            String r = rule.trim().toLowerCase(Locale.ROOT);
            if (key.contains(r) || name.contains(r) || r.contains(key)) return true;
        }
        return false;
    }

    private List<String> splitRules(String text) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return result;
        for (String item : text.split("[,，;；\\n]")) {
            String s = item.trim();
            if (!s.isEmpty() && !result.contains(s)) result.add(s);
        }
        return result;
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
