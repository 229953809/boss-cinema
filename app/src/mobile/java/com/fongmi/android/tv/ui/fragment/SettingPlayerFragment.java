package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPlayerBinding;
import com.fongmi.android.tv.impl.BufferListener;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.setting.LyricsSetting;
import com.fongmi.android.tv.setting.PlayerButtonSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.BufferDialog;
import com.fongmi.android.tv.ui.dialog.LutDialog;
import com.fongmi.android.tv.ui.dialog.PlayerButtonConfigDialog;
import com.fongmi.android.tv.ui.dialog.PlayerOsdDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;
import java.util.Locale;

public class SettingPlayerFragment extends BaseFragment implements UaListener, BufferListener, SpeedListener {

    private static final long LYRICS_OFFSET_MIN_MS = -3000L;
    private static final long LYRICS_OFFSET_MAX_MS = 3000L;
    private static final long LYRICS_OFFSET_STEP_MS = 500L;
    private static final long KARAOKE_DELAY_MIN_MS = -1000L;
    private static final long KARAOKE_DELAY_MAX_MS = 1000L;
    private static final long KARAOKE_DELAY_STEP_MS = 100L;

    private FragmentSettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] background;
    private String[] backBuffer;
    private String[] bufferBytes;
    private String[] caption;
    private String[] kernel;
    private String[] karaokeDifficulty;
    private String[] lyricsSize;
    private String[] lyricsSource;
    private String[] padLiveMode;
    private String[] playCache;
    private String[] render;
    private String[] scale;
    private String[] osd;

    public static SettingPlayerFragment newInstance() {
        return new SettingPlayerFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        format = new DecimalFormat("0.#");
        mBinding.uaText.setText(Setting.getUa());
        mBinding.aacText.setText(getSwitch(PlayerSetting.isPreferAAC()));
        mBinding.tunnelText.setText(getSwitch(PlayerSetting.isTunnel()));
        mBinding.exo4kCompatText.setText(getSwitch(PlayerSetting.isExoEnhanced()));
        setPadLiveModeText();
        setPlayerButtonsText();
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        mBinding.bufferText.setText(String.valueOf(PlayerSetting.getBuffer()));
        mBinding.bufferBytesText.setText((bufferBytes = ResUtil.getStringArray(R.array.select_buffer_bytes))[PlayerSetting.getBufferBytesOption()]);
        mBinding.backBufferText.setText((backBuffer = ResUtil.getStringArray(R.array.select_back_buffer))[PlayerSetting.getBackBufferOption()]);
        mBinding.playCacheText.setText((playCache = ResUtil.getStringArray(R.array.select_play_cache))[PlayerSetting.getPlayCacheOption()]);
        setPreloadText();
        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));
        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));
        mBinding.audioPassThroughText.setText(getSwitch(PlayerSetting.isAudioPassThrough()));
        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));
        mBinding.caption.setVisibility(PlayerSetting.hasCaption() ? View.VISIBLE : View.GONE);
        mBinding.osdText.setText(getOsdText(osd = ResUtil.getStringArray(R.array.select_player_osd)));
        mBinding.lyricsOffsetText.setText(getLyricsOffsetText());
        mBinding.lyricsRowsText.setText(getLyricsRowsText());
        mBinding.lyricsSizeText.setText((lyricsSize = ResUtil.getStringArray(R.array.select_lyrics_size))[PlayerSetting.getLyricsTextSizeOption()]);
        mBinding.lyricsSourceText.setText((lyricsSource = ResUtil.getStringArray(R.array.select_lyrics_source))[LyricsSetting.getSourceMode()]);
        mBinding.karaokeModeText.setText(getSwitch(PlayerSetting.isKaraokeMode()));
        mBinding.karaokeDifficultyText.setText((karaokeDifficulty = ResUtil.getStringArray(R.array.select_karaoke_difficulty))[PlayerSetting.getKaraokeDifficulty()]);
        mBinding.karaokeMicDelayText.setText(getKaraokeDelayText());
        mBinding.desktopLyricsText.setText(getSwitch(PlayerSetting.isDesktopLyrics()));
        setLyricsCacheText();
        mBinding.kernelText.setText((kernel = ResUtil.getStringArray(R.array.select_player_kernel))[PlayerSetting.getPlayer()]);
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.lutText.setText(LutSetting.getSummary());
        mBinding.renderText.setText((render = ResUtil.getStringArray(R.array.select_render))[PlayerSetting.getRender()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        mBinding.backgroundText.setText((background = ResUtil.getStringArray(R.array.select_background))[PlayerSetting.getBackground()]);
    }

    @Override
    protected void initEvent() {
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.aac.setOnClickListener(this::setAAC);
        mBinding.kernel.setOnClickListener(this::onKernel);
        mBinding.scale.setOnClickListener(this::onScale);
        mBinding.lut.setOnClickListener(this::onLut);
        mBinding.osd.setOnClickListener(this::onOsd);
        mBinding.lyricsOffset.setOnClickListener(this::onLyricsOffset);
        mBinding.lyricsRows.setOnClickListener(this::onLyricsRows);
        mBinding.lyricsSize.setOnClickListener(this::onLyricsSize);
        mBinding.lyricsSource.setOnClickListener(this::onLyricsSource);
        mBinding.karaokeMode.setOnClickListener(this::setKaraokeMode);
        mBinding.karaokeDifficulty.setOnClickListener(this::onKaraokeDifficulty);
        mBinding.karaokeMicDelay.setOnClickListener(this::onKaraokeMicDelay);
        mBinding.desktopLyrics.setOnClickListener(this::setDesktopLyrics);
        mBinding.desktopLyrics.setOnLongClickListener(this::resetDesktopLyricsPosition);
        mBinding.lyricsCache.setOnClickListener(this::clearLyricsCache);
        mBinding.playerButtons.setOnClickListener(view -> PlayerButtonConfigDialog.show(this, this::setPlayerButtonsText));
        mBinding.padLive.setOnClickListener(this::setPadLiveMode);
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.buffer.setOnClickListener(this::onBuffer);
        mBinding.bufferBytes.setOnClickListener(this::onBufferBytes);
        mBinding.backBuffer.setOnClickListener(this::onBackBuffer);
        mBinding.playCache.setOnClickListener(this::onPlayCache);
        mBinding.preload.setOnClickListener(this::setPreload);
        mBinding.preloadThread.setOnClickListener(this::onPreloadThread);
        mBinding.preloadSize.setOnClickListener(this::onPreloadSize);
        mBinding.preloadTime.setOnClickListener(this::onPreloadTime);
        mBinding.autoChange.setOnClickListener(this::setAutoChange);
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.tunnel.setOnClickListener(this::setTunnel);
        mBinding.exo4kCompat.setOnClickListener(this::setExo4KCompat);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.audioDecode.setOnClickListener(this::setAudioDecode);
        mBinding.audioPassThrough.setOnClickListener(this::setAudioPassThrough);
        mBinding.videoDecode.setOnClickListener(this::setVideoDecode);
    }

    private void onUa(View view) {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        mBinding.uaText.setText(ua);
        Setting.putUa(ua);
    }

    private void setAAC(View view) {
        PlayerSetting.putPreferAAC(!PlayerSetting.isPreferAAC());
        mBinding.aacText.setText(getSwitch(PlayerSetting.isPreferAAC()));
    }

    private void onKernel(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_kernel).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(kernel, PlayerSetting.getPlayer(), (dialog, which) -> {
            mBinding.kernelText.setText(kernel[which]);
            PlayerSetting.putPlayer(which);
            dialog.dismiss();
        }).show();
    }

    private void onScale(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_scale).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(scale, PlayerSetting.getScale(), (dialog, which) -> {
            mBinding.scaleText.setText(scale[which]);
            PlayerSetting.putScale(which);
            dialog.dismiss();
        }).show();
    }

    private void onLut(View view) {
        LutDialog.show(this, () -> mBinding.lutText.setText(LutSetting.getSummary()));
    }

    private void onOsd(View view) {
        PlayerOsdDialog.show(this, osd, getOsdChecked(), checked -> {
            setOsdChecked(checked);
            mBinding.osdText.setText(getOsdText(osd));
        });
    }

    private void onLyricsOffset(View view) {
        String[] items = getLyricsOffsetItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_lyrics_offset).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, getLyricsOffsetIndex(), (dialog, which) -> {
            PlayerSetting.putLyricsTimeOffsetMs(LYRICS_OFFSET_MIN_MS + which * LYRICS_OFFSET_STEP_MS);
            mBinding.lyricsOffsetText.setText(getLyricsOffsetText());
            dialog.dismiss();
        }).show();
    }

    private void onLyricsSource(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_lyrics_source).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(lyricsSource, LyricsSetting.getSourceMode(), (dialog, which) -> {
            LyricsSetting.putSourceMode(which);
            mBinding.lyricsSourceText.setText(lyricsSource[which]);
            dialog.dismiss();
        }).show();
    }

    private void onLyricsRows(View view) {
        String[] items = getLyricsRowsItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_lyrics_rows).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, PlayerSetting.getLyricsRows() - 1, (dialog, which) -> {
            PlayerSetting.putLyricsRows(which + 1);
            mBinding.lyricsRowsText.setText(getLyricsRowsText());
            dialog.dismiss();
        }).show();
    }

    private void onLyricsSize(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_lyrics_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(lyricsSize, PlayerSetting.getLyricsTextSizeOption(), (dialog, which) -> {
            PlayerSetting.putLyricsTextSizeOption(which);
            mBinding.lyricsSizeText.setText(lyricsSize[which]);
            dialog.dismiss();
        }).show();
    }

    private void clearLyricsCache(View view) {
        LyricsRepository.clearCache();
        setLyricsCacheText();
        Notify.show(R.string.player_lyrics_cache_cleared);
    }

    private void setKaraokeMode(View view) {
        PlayerSetting.putKaraokeMode(!PlayerSetting.isKaraokeMode());
        mBinding.karaokeModeText.setText(getSwitch(PlayerSetting.isKaraokeMode()));
    }

    private void onKaraokeDifficulty(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_karaoke_difficulty).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(karaokeDifficulty, PlayerSetting.getKaraokeDifficulty(), (dialog, which) -> {
            PlayerSetting.putKaraokeDifficulty(which);
            mBinding.karaokeDifficultyText.setText(karaokeDifficulty[which]);
            dialog.dismiss();
        }).show();
    }

    private void onKaraokeMicDelay(View view) {
        String[] items = getKaraokeDelayItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_karaoke_mic_delay).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, getKaraokeDelayIndex(), (dialog, which) -> {
            PlayerSetting.putKaraokeMicDelayMs(KARAOKE_DELAY_MIN_MS + which * KARAOKE_DELAY_STEP_MS);
            mBinding.karaokeMicDelayText.setText(getKaraokeDelayText());
            dialog.dismiss();
        }).show();
    }

    private void setDesktopLyrics(View view) {
        boolean enabled = !PlayerSetting.isDesktopLyrics();
        PlayerSetting.putDesktopLyrics(enabled);
        mBinding.desktopLyricsText.setText(getSwitch(enabled));
        if (enabled && !canDrawOverlays()) openOverlayPermission();
    }

    private boolean resetDesktopLyricsPosition(View view) {
        PlayerSetting.resetDesktopLyricsPosition();
        Notify.show(R.string.player_desktop_lyrics_position_reset);
        return true;
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(requireContext());
    }

    private void openOverlayPermission() {
        Notify.show(R.string.player_desktop_lyrics_permission);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + requireContext().getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void setLyricsCacheText() {
        mBinding.lyricsCacheText.setText(getString(R.string.player_lyrics_cache_value, LyricsRepository.cacheCount()));
    }

    private void setPlayerButtonsText() {
        mBinding.playerButtonsText.setText(getString(R.string.player_button_config_summary, PlayerButtonSetting.getVisibleCount(), PlayerButtonSetting.getTotalCount()));
    }

    private void setPadLiveModeText() {
        mBinding.padLive.setVisibility(ResUtil.isPad() ? View.VISIBLE : View.GONE);
        mBinding.padLiveText.setText((padLiveMode = ResUtil.getStringArray(R.array.select_pad_live_mode))[PlayerSetting.getPadLiveMode()]);
    }

    private void setPadLiveMode(View view) {
        int index = (PlayerSetting.getPadLiveMode() + 1) % padLiveMode.length;
        PlayerSetting.putPadLiveMode(index);
        mBinding.padLiveText.setText(padLiveMode[index]);
    }

    private boolean[] getOsdChecked() {
        return new boolean[]{PlayerSetting.isOsdTitle(), PlayerSetting.isOsdResolution(), PlayerSetting.isOsdTime(), PlayerSetting.isOsdProgress(), PlayerSetting.isOsdTraffic(), PlayerSetting.isOsdMini(), PlayerSetting.isOsdDiagnostics()};
    }

    private void setOsdChecked(boolean[] checked) {
        PlayerSetting.putOsdTitle(checked[0]);
        PlayerSetting.putOsdResolution(checked[1]);
        PlayerSetting.putOsdTime(checked[2]);
        PlayerSetting.putOsdProgress(checked[3]);
        PlayerSetting.putOsdTraffic(checked[4]);
        PlayerSetting.putOsdMini(checked[5]);
        PlayerSetting.putOsdDiagnostics(checked[6]);
    }

    private String getOsdText(String[] items) {
        boolean[] checked = getOsdChecked();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < checked.length; i++) {
            if (!checked[i]) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(items[i]);
        }
        return builder.length() == 0 ? getString(R.string.setting_off) : builder.toString();
    }

    private String[] getLyricsOffsetItems() {
        int count = (int) ((LYRICS_OFFSET_MAX_MS - LYRICS_OFFSET_MIN_MS) / LYRICS_OFFSET_STEP_MS) + 1;
        String[] items = new String[count];
        for (int i = 0; i < count; i++) items[i] = formatLyricsOffset(LYRICS_OFFSET_MIN_MS + i * LYRICS_OFFSET_STEP_MS);
        return items;
    }

    private int getLyricsOffsetIndex() {
        long value = Math.min(Math.max(PlayerSetting.getLyricsTimeOffsetMs(), LYRICS_OFFSET_MIN_MS), LYRICS_OFFSET_MAX_MS);
        return (int) ((value - LYRICS_OFFSET_MIN_MS) / LYRICS_OFFSET_STEP_MS);
    }

    private String[] getLyricsRowsItems() {
        String[] items = new String[5];
        for (int i = 0; i < items.length; i++) items[i] = getString(R.string.player_lyrics_rows_value, i + 1);
        return items;
    }

    private String getLyricsRowsText() {
        return getString(R.string.player_lyrics_rows_value, PlayerSetting.getLyricsRows());
    }

    private String getLyricsOffsetText() {
        return formatLyricsOffset(PlayerSetting.getLyricsTimeOffsetMs());
    }

    private String[] getKaraokeDelayItems() {
        int count = (int) ((KARAOKE_DELAY_MAX_MS - KARAOKE_DELAY_MIN_MS) / KARAOKE_DELAY_STEP_MS) + 1;
        String[] items = new String[count];
        for (int i = 0; i < count; i++) items[i] = formatLyricsOffset(KARAOKE_DELAY_MIN_MS + i * KARAOKE_DELAY_STEP_MS);
        return items;
    }

    private int getKaraokeDelayIndex() {
        long value = Math.min(Math.max(PlayerSetting.getKaraokeMicDelayMs(), KARAOKE_DELAY_MIN_MS), KARAOKE_DELAY_MAX_MS);
        return (int) ((value - KARAOKE_DELAY_MIN_MS) / KARAOKE_DELAY_STEP_MS);
    }

    private String getKaraokeDelayText() {
        return formatLyricsOffset(PlayerSetting.getKaraokeMicDelayMs());
    }

    private String formatLyricsOffset(long valueMs) {
        if (valueMs == 0) return "0s";
        return String.format(Locale.getDefault(), "%+.1fs", valueMs / 1000f);
    }

    private void onSpeed(View view) {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed));
        PlayerSetting.putSpeed(speed);
    }

    private void onBuffer(View view) {
        BufferDialog.show(this);
    }

    @Override
    public void setBuffer(int times) {
        mBinding.bufferText.setText(String.valueOf(times));
        PlayerSetting.putBuffer(times);
    }

    private void onBufferBytes(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_buffer_bytes).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(bufferBytes, PlayerSetting.getBufferBytesOption(), (dialog, which) -> {
            mBinding.bufferBytesText.setText(bufferBytes[which]);
            PlayerSetting.putBufferBytesOption(which);
            dialog.dismiss();
        }).show();
    }

    private void onBackBuffer(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_back_buffer).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(backBuffer, PlayerSetting.getBackBufferOption(), (dialog, which) -> {
            mBinding.backBufferText.setText(backBuffer[which]);
            PlayerSetting.putBackBufferOption(which);
            dialog.dismiss();
        }).show();
    }

    private void onPlayCache(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_cache).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(playCache, PlayerSetting.getPlayCacheOption(), (dialog, which) -> {
            mBinding.playCacheText.setText(playCache[which]);
            PlayerSetting.putPlayCacheOption(which);
            dialog.dismiss();
        }).show();
    }

    private void setPreload(View view) {
        PreloadSetting.putPreload(!PreloadSetting.isPreload());
        setPreloadText();
    }

    private void onPreloadThread(View view) {
        String[] items = getPreloadThreadItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_preload_threads).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, PreloadSetting.getPreloadThreads() - PreloadSetting.MIN_THREADS, (dialog, which) -> {
            PreloadSetting.putPreloadThreads(PreloadSetting.MIN_THREADS + which);
            setPreloadText();
            dialog.dismiss();
        }).show();
    }

    private void onPreloadSize(View view) {
        String[] items = getPreloadSizeItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_preload_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, getPreloadSizeIndex(), (dialog, which) -> {
            PreloadSetting.putPreloadSizeMb(PreloadSetting.MIN_SIZE_MB + which * PreloadSetting.STEP_SIZE_MB);
            setPreloadText();
            dialog.dismiss();
        }).show();
    }

    private void onPreloadTime(View view) {
        String[] items = getPreloadTimeItems();
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_preload_time).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, getPreloadTimeIndex(), (dialog, which) -> {
            PreloadSetting.putPreloadTimeSeconds(PreloadSetting.MIN_TIME_SECONDS + which * PreloadSetting.STEP_TIME_SECONDS);
            setPreloadText();
            dialog.dismiss();
        }).show();
    }

    private void setPreloadText() {
        boolean preload = PreloadSetting.isPreload();
        mBinding.preloadText.setText(getSwitch(preload));
        mBinding.preloadThread.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadSize.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadTime.setVisibility(preload ? View.VISIBLE : View.GONE);
        mBinding.preloadThreadText.setText(getString(R.string.player_preload_threads_value, PreloadSetting.getPreloadThreads()));
        mBinding.preloadSizeText.setText(FileUtil.byteCountToDisplaySize(PreloadSetting.getPreloadSizeBytes()));
        mBinding.preloadTimeText.setText(getString(R.string.player_preload_time_value, PreloadSetting.getPreloadTimeSeconds()));
    }

    private String[] getPreloadThreadItems() {
        String[] items = new String[PreloadSetting.MAX_THREADS - PreloadSetting.MIN_THREADS + 1];
        for (int i = 0; i < items.length; i++) items[i] = getString(R.string.player_preload_threads_value, PreloadSetting.MIN_THREADS + i);
        return items;
    }

    private String[] getPreloadSizeItems() {
        String[] items = new String[getPreloadSizeCount()];
        for (int i = 0; i < items.length; i++) items[i] = FileUtil.byteCountToDisplaySize((PreloadSetting.MIN_SIZE_MB + i * PreloadSetting.STEP_SIZE_MB) * 1024L * 1024L);
        return items;
    }

    private String[] getPreloadTimeItems() {
        String[] items = new String[getPreloadTimeCount()];
        for (int i = 0; i < items.length; i++) items[i] = getString(R.string.player_preload_time_value, PreloadSetting.MIN_TIME_SECONDS + i * PreloadSetting.STEP_TIME_SECONDS);
        return items;
    }

    private int getPreloadSizeIndex() {
        return Math.min(Math.max((PreloadSetting.getPreloadSizeMb() - PreloadSetting.MIN_SIZE_MB) / PreloadSetting.STEP_SIZE_MB, 0), getPreloadSizeCount() - 1);
    }

    private int getPreloadTimeIndex() {
        return Math.min(Math.max((PreloadSetting.getPreloadTimeSeconds() - PreloadSetting.MIN_TIME_SECONDS) / PreloadSetting.STEP_TIME_SECONDS, 0), getPreloadTimeCount() - 1);
    }

    private int getPreloadSizeCount() {
        return (PreloadSetting.MAX_SIZE_MB - PreloadSetting.MIN_SIZE_MB) / PreloadSetting.STEP_SIZE_MB + 1;
    }

    private int getPreloadTimeCount() {
        return (PreloadSetting.MAX_TIME_SECONDS - PreloadSetting.MIN_TIME_SECONDS) / PreloadSetting.STEP_TIME_SECONDS + 1;
    }

    private void setAutoChange(View view) {
        PlayerSetting.putAutoChange(!PlayerSetting.isAutoChange());
        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));
    }

    private void setRender(View view) {
        if (PlayerSetting.isTunnel() && PlayerSetting.getRender() == 0) setTunnel(view);
        int index = (PlayerSetting.getRender() + 1) % render.length;
        mBinding.renderText.setText(render[index]);
        PlayerSetting.putRender(index);
        mBinding.exo4kCompatText.setText(getSwitch(PlayerSetting.isExoEnhanced()));
    }

    private void setTunnel(View view) {
        PlayerSetting.putTunnel(!PlayerSetting.isTunnel());
        mBinding.tunnelText.setText(getSwitch(PlayerSetting.isTunnel()));
        if (PlayerSetting.isTunnel() && PlayerSetting.getRender() == 1) setRender(view);
    }

    private void setExo4KCompat(View view) {
        PlayerSetting.putExoEnhanced(!PlayerSetting.isExoEnhanced());
        mBinding.exo4kCompatText.setText(getSwitch(PlayerSetting.isExoEnhanced()));
        mBinding.renderText.setText(render[PlayerSetting.getRender()]);
    }

    private void setCaption(View view) {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
        mBinding.captionText.setText(caption[PlayerSetting.isCaption() ? 1 : 0]);
    }

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private void onBackground(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_background).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(background, PlayerSetting.getBackground(), (dialog, which) -> {
            mBinding.backgroundText.setText(background[which]);
            PlayerSetting.putBackground(which);
            dialog.dismiss();
        }).show();
    }

    private void setAudioDecode(View view) {
        PlayerSetting.putAudioPrefer(!PlayerSetting.isAudioPrefer());
        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));
    }

    private void setAudioPassThrough(View view) {
        PlayerSetting.putAudioPassThrough(!PlayerSetting.isAudioPassThrough());
        mBinding.audioPassThroughText.setText(getSwitch(PlayerSetting.isAudioPassThrough()));
    }

    private void setVideoDecode(View view) {
        PlayerSetting.putVideoPrefer(!PlayerSetting.isVideoPrefer());
        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
