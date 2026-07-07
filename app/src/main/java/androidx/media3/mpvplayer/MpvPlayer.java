package androidx.media3.mpvplayer;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;
import org.json.JSONArray;
import org.json.JSONObject;

@UnstableApi
public final class MpvPlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final String TAG = "TV-mpv";
    private static final long STATE_REFRESH_INTERVAL_MS = 1000;
    private static final long END_FILE_VALIDATION_DELAY_MS = 800;
    private static final long LOAD_START_RETRY_DELAY_MS = 1000;
    private static final long TRACK_REFRESH_AFTER_SUBTITLE_MS = 300;
    private static final int MAX_LOAD_START_RETRIES = 2;
    private static final double SECONDS_TO_MS = 1000.0;
    private static final double DEFAULT_SUBTITLE_TEXT_SIZE_FRACTION = 0.0533;
    private static final double MICROSECONDS_TO_SECONDS = 1_000_000.0;
    private static final String CONCAT_SOURCE_SEPARATOR = "***";
    private static final String CONCAT_SOURCE_SEPARATOR_REGEX = "\\*\\*\\*";
    private static final String CONCAT_DURATION_SEPARATOR = "|||";
    private static final String CONCAT_DURATION_SEPARATOR_REGEX = "\\|\\|\\|";
    private static final String HLS_LOAD_OPTIONS = "demuxer=lavf,demuxer-lavf-format=hls,demuxer-lavf-probesize=10485760,demuxer-lavf-analyzeduration=5";
    private static final int RECENT_LOG_LIMIT = 32;
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ORIGIN = "Origin";

    public static final String ERROR_HLS_PLAYBACK_FAILED = "MPV_HLS_PLAYBACK_FAILED";
    public static final String ERROR_LOAD_FAILED = "MPV_LOAD_FAILED";
    public static final String ERROR_UNEXPECTED_IMAGE = "MPV_UNEXPECTED_IMAGE";
    public static final String ERROR_NO_AV_DATA = "MPV_NO_AV_DATA";
    public static final String ERROR_INVALID_MEDIA_DATA = "MPV_INVALID_MEDIA_DATA";
    public static final String ERROR_DECODE_FAILED = "MPV_DECODE_FAILED";
    public static final String ERROR_VIDEO_OUTPUT_FAILED = "MPV_VIDEO_OUTPUT_FAILED";
    public static final String ERROR_DRM_UNSUPPORTED = "MPV_DRM_UNSUPPORTED";

    private static final Commands COMMANDS = new Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_RELEASE)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_TIMELINE)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SET_MEDIA_ITEM)
            .add(COMMAND_CHANGE_MEDIA_ITEMS)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(COMMAND_GET_VOLUME)
            .add(COMMAND_SET_VOLUME)
            .add(COMMAND_SET_SPEED_AND_PITCH)
            .add(COMMAND_SET_VIDEO_SURFACE)
            .add(COMMAND_GET_TRACKS)
            .add(COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            .add(COMMAND_GET_TEXT_OFFSET)
            .add(COMMAND_SET_TEXT_OFFSET)
            .add(COMMAND_GET_AUDIO_OFFSET)
            .add(COMMAND_SET_AUDIO_OFFSET)
            .build();

    private final Context context;
    private final MpvPlayerConfig config;
    private final Handler mainHandler;
    private final Runnable stateRefreshRunnable;
    private final Runnable endFileValidationRunnable;
    private final Runnable loadStartRetryRunnable;
    private final Runnable trackRefreshRunnable;
    private final MpvHlsProxy hlsProxy;
    private final List<String> recentLogs;
    private final List<ParcelFileDescriptor> contentFds;
    private MediaItem mediaItem;
    private SurfaceHolder surfaceHolder;
    private Surface surface;
    private Object videoOutput;
    private String currentPlayableUri;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private TrackSelectionParameters trackSelectionParameters;
    private Tracks currentTracks;
    private List<MediaEdition> currentChapters;
    private VideoSize videoSize;
    private int playbackState;
    private long pendingSeekPositionMs;
    private long cachedPositionMs;
    private long cachedDurationMs;
    private long cachedCacheDurationMs;
    private long textOffsetMs;
    private long audioOffsetMs;
    private boolean playWhenReady;
    private boolean loading;
    private boolean repeatOne;
    private boolean ownsSurface;
    private boolean initialized;
    private boolean released;
    private boolean surfaceAttached;
    private boolean fileLoaded;
    private boolean loadStarted;
    private boolean playbackRestarted;
    private boolean stopping;
    private boolean eofReached;
    private boolean idleActive;
    private boolean currentLikelyHls;
    private boolean sawNoAvData;
    private boolean sawInvalidData;
    private boolean sawPngVideo;
    private int loadStartRetryCount;
    private int currentChapter;
    private String lastFailureLog;
    private float subtitleTextSize;
    private float subtitlePosition;
    private float volume;

    public MpvPlayer(Context context, MpvPlayerConfig config) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.config = config;
        mainHandler = new Handler(Looper.getMainLooper());
        stateRefreshRunnable = this::refreshPlaybackState;
        endFileValidationRunnable = this::validateEarlyEndFile;
        loadStartRetryRunnable = this::retryLoadIfNotStarted;
        trackRefreshRunnable = this::refreshTracks;
        hlsProxy = new MpvHlsProxy();
        recentLogs = new ArrayList<>();
        contentFds = new ArrayList<>();
        playbackParameters = PlaybackParameters.DEFAULT;
        trackSelectionParameters = TrackSelectionParameters.DEFAULT;
        currentTracks = Tracks.EMPTY;
        currentChapters = List.of();
        videoSize = VideoSize.UNKNOWN;
        playbackState = Player.STATE_IDLE;
        pendingSeekPositionMs = C.TIME_UNSET;
        cachedDurationMs = C.TIME_UNSET;
        currentChapter = C.INDEX_UNSET;
        playWhenReady = true;
        volume = 1f;
    }

    @Override
    protected State getState() {
        int state = playbackState;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setIsLoading(loading && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                .setPlayerError(playerError)
                .setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF)
                .setPlaybackParameters(playbackParameters)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setTextOffsetMs(textOffsetMs)
                .setAudioOffsetMs(audioOffsetMs)
                .setVideoSize(videoSize)
                .setVolume(volume)
                .setCurrentMediaEditions(currentChapters)
                .setPlaylist(mediaItem == null ? ImmutableList.of() : ImmutableList.of(mediaItemData()))
                .setCurrentMediaItemIndex(mediaItem == null ? C.INDEX_UNSET : 0);
        if (mediaItem != null) {
            long duration = durationMs();
            long position = positionMs();
            PositionSupplier positionSupplier = isPlayingInternal()
                    ? PositionSupplier.getExtrapolating(position, playbackParameters.speed)
                    : PositionSupplier.getConstant(position);
            builder.setContentPositionMs(positionSupplier);
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedPositionMs(position, duration)));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(Math.max(0, bufferedPositionMs(position, duration) - position)));
        }
        return builder.build();
    }

    private MediaItemData mediaItemData() {
        long duration = durationMs();
        return new MediaItemData.Builder(mediaItem.mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setDurationUs(duration == C.TIME_UNSET ? C.TIME_UNSET : duration * 1000)
                .setIsSeekable(duration > 0)
                .setIsDynamic(duration == C.TIME_UNSET)
                .setTracks(currentTracks)
                .build();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        pendingSeekPositionMs = mediaItem != null && startPositionMs > 0 ? startPositionMs : C.TIME_UNSET;
        cachedPositionMs = Math.max(0, startPositionMs == C.TIME_UNSET ? 0 : startPositionMs);
        cachedDurationMs = C.TIME_UNSET;
        cachedCacheDurationMs = 0;
        currentTracks = Tracks.EMPTY;
        currentChapters = List.of();
        currentChapter = C.INDEX_UNSET;
        playbackState = mediaItem == null ? Player.STATE_IDLE : Player.STATE_IDLE;
        loading = false;
        fileLoaded = false;
        playbackRestarted = false;
        loadStarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        idleActive = false;
        currentPlayableUri = null;
        currentLikelyHls = false;
        resetFailureSignals();
        recentLogs.clear();
        playerError = null;
        resetMpvContextForNewMedia();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.removeCallbacks(trackRefreshRunnable);
        closeContentFds();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        mediaItem = null;
        stopInternal(true);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        openCurrent();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (initialized && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            safeSetPropertyBoolean("pause", !playWhenReady);
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        stopInternal(true);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        released = true;
        stopInternal(false);
        hlsProxy.release();
        clearVideoOutput();
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.removeCallbacks(endFileValidationRunnable);
        if (initialized) {
            try {
                MPVLib.removeObserver(this);
                MPVLib.removeLogObserver(this);
                MPVLib.destroy();
            } catch (Throwable ignored) {
            }
            initialized = false;
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        repeatOne = repeatMode == Player.REPEAT_MODE_ONE;
        if (initialized) safeSetPropertyString("loop-file", repeatOne ? "inf" : "no");
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        if (positionMs == C.TIME_UNSET) positionMs = 0;
        cachedPositionMs = Math.max(0, positionMs);
        pendingSeekPositionMs = cachedPositionMs;
        if (initialized && playbackState != Player.STATE_IDLE) {
            seekMpv(cachedPositionMs);
            if (currentLikelyHls) hlsProxy.preloadAround(cachedPositionMs);
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_BUFFERING;
        }
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        if (initialized) safeSetPropertyDouble("speed", playbackParameters.speed);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        if (initialized) safeSetPropertyDouble("volume", this.volume * 100.0);
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTrackSelectionParameters(TrackSelectionParameters parameters) {
        trackSelectionParameters = parameters == null ? TrackSelectionParameters.DEFAULT : parameters;
        applyTrackSelectionParameters();
        scheduleTrackRefresh();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long offsetMs) {
        textOffsetMs = offsetMs;
        applyTextOffset();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long offsetMs) {
        audioOffsetMs = offsetMs;
        applyAudioOffset();
        invalidateState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        this.videoOutput = videoOutput;
        setVideoOutput(videoOutput);
        return Futures.immediateVoidFuture();
    }

    public void setSubtitleStyle(float textSize, float position) {
        subtitleTextSize = textSize;
        subtitlePosition = position;
        applySubtitleStyle();
        invalidateState();
    }

    public boolean selectEdition(MediaEdition edition) {
        if (edition == null || edition.index < 0 || edition.index >= currentChapters.size()) return false;
        currentChapter = edition.index;
        if (initialized) safeSetPropertyInt("chapter", edition.index);
        refreshChapters();
        invalidateState();
        return true;
    }

    public String getRuntimeDiagnostics() {
        if (!initialized) return "";
        return join(" / ",
                "格式 " + emptyDash(stringProperty("file-format", "")),
                "视频 " + emptyDash(stringProperty("video-codec", "")),
                "音频 " + emptyDash(stringProperty("audio-codec", "")),
                "硬解 " + emptyDash(stringProperty("hwdec-current", "")),
                "输出 " + emptyDash(stringProperty("current-vo", stringProperty("vo-configured", ""))),
                "缓存 " + formatSeconds(doubleProperty("demuxer-cache-duration", 0)),
                "代理 " + hlsProxy.diagnostics(),
                "丢帧 " + Math.max(0, intProperty("frame-drop-count", 0)) + "/" + Math.max(0, intProperty("vo-drop-frame-count", 0)),
                "章节 " + chapterText());
    }

    public boolean shouldReloadLiveHls(PlaybackException error) {
        if (!currentLikelyHls || hlsProxy.isCurrentVodPlaylist()) return false;
        String message = error == null ? "" : error.getMessage();
        if (hlsProxy.isCurrentLivePlaylist() && hlsProxy.hasRecentCurrentHttpError()) return true;
        if (!hlsProxy.isCurrentLivePlaylist()) return false;
        return startsWith(message, ERROR_LOAD_FAILED)
                || startsWith(message, ERROR_HLS_PLAYBACK_FAILED)
                || (startsWith(message, ERROR_NO_AV_DATA) && durationMs() == C.TIME_UNSET);
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == this.videoOutput) {
            this.videoOutput = null;
            clearVideoOutput();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    public void eventProperty(String property) {
        postToMain(() -> handleProperty(property, null));
    }

    @Override
    public void eventProperty(String property, long value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, boolean value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, String value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void eventProperty(String property, double value) {
        postToMain(() -> handleProperty(property, value));
    }

    @Override
    public void event(int eventId) {
        postToMain(() -> handleEvent(eventId));
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        postToMain(() -> {
            if (released) return;
            String line = prefix + ": " + text;
            rememberLog(line);
            markFailureSignal(line);
            if (shouldDebugLogMpvLine(line)) SpiderDebug.log("mpv", "%s", line);
        });
    }

    private void openCurrent() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        if (mediaItem.localConfiguration.drmConfiguration != null) {
            fail(mpvError(ERROR_DRM_UNSUPPORTED, "scheme=" + mediaItem.localConfiguration.drmConfiguration.scheme), PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED);
            return;
        }
        try {
            ensureInitialized();
            playbackState = Player.STATE_BUFFERING;
            loading = true;
            playerError = null;
            fileLoaded = false;
            loadStarted = false;
            playbackRestarted = false;
            loadStartRetryCount = 0;
            eofReached = false;
            idleActive = false;
            cachedDurationMs = C.TIME_UNSET;
            cachedCacheDurationMs = 0;
            resetFailureSignals();
            recentLogs.clear();
            mainHandler.removeCallbacks(endFileValidationRunnable);
            closeContentFds();
            Map<String, String> headers = applyMediaOptions(mediaItem);
            bindVideoOutput();
            safeSetPropertyBoolean("pause", !playWhenReady);
            safeSetPropertyString("loop-file", repeatOne ? "inf" : "no");
            safeSetPropertyDouble("speed", playbackParameters.speed);
            safeSetPropertyDouble("volume", volume * 100.0);
            applyTextOffset();
            applyAudioOffset();
            applySubtitleStyle();
            currentPlayableUri = playableUri(mediaItem.localConfiguration.uri);
            currentLikelyHls = isLikelyHls(mediaItem, currentPlayableUri);
            if (shouldProxyHls(currentPlayableUri, currentLikelyHls)) {
                String originalUri = currentPlayableUri;
                currentPlayableUri = hlsProxy.proxy(originalUri, headers);
                SpiderDebug.log("mpv", "hls proxy enabled original=%s proxy=%s", originalUri, currentPlayableUri);
            } else {
                hlsProxy.clear();
            }
            Log.d(TAG, "load uri=" + currentPlayableUri + " hls=" + currentLikelyHls);
            SpiderDebug.log("mpv", "load uri=%s hls=%s surface=%s attached=%s hwdec=%s", currentPlayableUri, currentLikelyHls, surface != null && surface.isValid(), surfaceAttached, config.hwdec());
            loadCurrentUri();
            scheduleLoadStartRetry();
            invalidateState();
            startStateRefresh();
        } catch (Throwable e) {
            fail(mpvError(ERROR_LOAD_FAILED, e.getMessage(), e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    private void ensureInitialized() throws IOException {
        if (initialized) return;
        if (!MPVLib.ensureLoaded(context)) {
            Throwable e = MPVLib.getLoadError();
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IOException(e == null ? "MPV native libraries are unavailable" : e.getMessage(), e);
        }
        copySupportAssets();
        MPVLib.create(context);
        applyPreInitOptions();
        MPVLib.init();
        initialized = true;
        MPVLib.addObserver(this);
        MPVLib.addLogObserver(this);
        applyPostInitOptions();
        observeProperties();
    }

    private void applyPreInitOptions() {
        setOption("config", "yes");
        setOption("config-dir", config.configDir().getAbsolutePath());
        setOption("gpu-shader-cache-dir", config.cacheDir().getAbsolutePath());
        setOption("icc-cache-dir", config.cacheDir().getAbsolutePath());
        setOption("profile", "fast");
        setOption("vo", config.vo());
        setOption("gpu-context", "android");
        setOption("opengl-es", "yes");
        setOption("hwdec", config.hwdec());
        setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
        setOption("hwdec-software-fallback", config.hwdecSoftwareFallback());
        setOption("ao", config.ao());
        setOption("audio-spdif", config.audioSpdif());
        setOption("audio-set-media-role", "yes");
        setOption("tls-verify", config.tlsVerify() ? "yes" : "no");
        if (config.caFile().isFile()) setOption("tls-ca-file", config.caFile().getAbsolutePath());
        setOption("input-default-bindings", "yes");
        setOption("cache", "yes");
        setOption("cache-secs", String.valueOf(config.cacheSecs()));
        setOption("cache-pause", "yes");
        setOption("cache-pause-initial", "no");
        setOption("cache-pause-wait", String.valueOf(config.cachePauseWaitSecs()));
        setOption("http-allow-redirect", "yes");
        setOption("hls-bitrate", "max");
        setOption("demuxer-max-bytes", String.valueOf(config.demuxerMaxBytes()));
        setOption("demuxer-max-back-bytes", String.valueOf(config.demuxerMaxBackBytes()));
        setOption("demuxer-readahead-secs", String.valueOf(config.demuxerReadaheadSecs()));
        setOption("stream-buffer-size", String.valueOf(config.streamBufferSize()));
        setOption("sub-ass", "yes");
        setOption("embeddedfonts", "yes");
        setOption("sub-fix-timing", "yes");
        setOption("sub-use-margins", "yes");
        setOption("volume-max", "100");
        setOption("msg-level", config.logLevel());
        for (Map.Entry<String, String> entry : config.extraOptions().entrySet()) setOption(entry.getKey(), entry.getValue());
    }

    private void applyPostInitOptions() {
        setRuntimeString("save-position-on-quit", "no");
        setRuntimeString("force-window", "no");
        setRuntimeString("idle", "once");
    }

    private void observeProperties() {
        observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("time-pos/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("idle-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        observe("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("track-list", MPVLib.MpvFormat.MPV_FORMAT_STRING);
        observe("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        observe("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_STRING);
    }

    private void handleProperty(String property, @Nullable Object value) {
        if (released) return;
        switch (property) {
            case "time-pos", "time-pos/full" -> cachedPositionMs = doubleSecondsToMs(value, cachedPositionMs);
            case "duration", "duration/full" -> cachedDurationMs = doubleSecondsToMs(value, cachedDurationMs);
            case "demuxer-cache-duration" -> cachedCacheDurationMs = Math.max(0, doubleSecondsToMs(value, cachedCacheDurationMs));
            case "pause" -> {
                if (value instanceof Boolean paused) playWhenReady = !paused;
            }
            case "paused-for-cache" -> {
                loading = Boolean.TRUE.equals(value);
                if (loading) playbackState = Player.STATE_BUFFERING;
                else if (playbackState == Player.STATE_BUFFERING && fileLoaded && playbackRestarted) playbackState = Player.STATE_READY;
            }
            case "eof-reached" -> {
                eofReached = Boolean.TRUE.equals(value);
                if (eofReached) {
                    playbackState = Player.STATE_ENDED;
                    loading = false;
                    stopStateRefresh();
                }
            }
            case "idle-active" -> idleActive = Boolean.TRUE.equals(value);
            case "width", "height" -> updateVideoSize();
            case "track-list" -> handleTrackListProperty(value);
            case "chapter" -> {
                if (value instanceof Number number) currentChapter = number.intValue();
                refreshChapters();
            }
            case "chapter-list" -> handleChapterListProperty(value);
            default -> {
            }
        }
        invalidateState();
    }

    private void handleEvent(int eventId) {
        if (released) return;
        switch (eventId) {
            case MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                loadStarted = true;
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                fileLoaded = false;
                playbackRestarted = false;
                stopping = false;
                eofReached = false;
                idleActive = false;
                resetFailureSignals();
                SpiderDebug.log("mpv", "event=start-file uri=%s", currentPlayableUri);
                mainHandler.removeCallbacks(endFileValidationRunnable);
                mainHandler.removeCallbacks(loadStartRetryRunnable);
                startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (loadedUnexpectedImage()) {
                    fail(mpvError(ERROR_UNEXPECTED_IMAGE, "path=" + stringProperty("path", "")), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED);
                    return;
                }
                fileLoaded = true;
                mainHandler.removeCallbacks(endFileValidationRunnable);
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                cachedDurationMs = durationMs();
                updateVideoSize();
                refreshTracks();
                refreshChapters();
                SpiderDebug.log("mpv", "event=file-loaded duration=%d size=%dx%d path=%s", cachedDurationMs, videoSize.width, videoSize.height, stringProperty("path", ""));
                addSubtitleConfigurations();
                if (pendingSeekPositionMs != C.TIME_UNSET) {
                    seekMpv(pendingSeekPositionMs);
                    if (currentLikelyHls) hlsProxy.preloadAround(pendingSeekPositionMs);
                    pendingSeekPositionMs = C.TIME_UNSET;
                }
                safeSetPropertyBoolean("pause", !playWhenReady);
                startStateRefresh();
            }
            case MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                playbackRestarted = true;
                updateVideoSize();
                refreshTracks();
                refreshChapters();
                SpiderDebug.log("mpv", "event=playback-restart position=%d duration=%d size=%dx%d", positionMs(), durationMs(), videoSize.width, videoSize.height);
                if (playbackState != Player.STATE_ENDED) {
                    playbackState = Player.STATE_READY;
                    loading = false;
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                updateVideoSize();
                refreshTracks();
            }
            case MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> refreshTracks();
            case MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                stopStateRefresh();
                loading = false;
                if (stopping) {
                    stopping = false;
                } else if (isFailedLoadedMedia()) {
                    fail(new IOException(failedLoadedMediaMessage()), PlaybackException.ERROR_CODE_DECODING_FAILED);
                    return;
                } else if (fileLoaded || eofReached) {
                    playbackState = Player.STATE_ENDED;
                } else {
                    loading = true;
                    playbackState = Player.STATE_BUFFERING;
                    mainHandler.removeCallbacks(endFileValidationRunnable);
                    mainHandler.postDelayed(endFileValidationRunnable, END_FILE_VALIDATION_DELAY_MS);
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_IDLE -> {
                if (loading && !fileLoaded && !stopping) {
                    playbackState = Player.STATE_BUFFERING;
                    mainHandler.removeCallbacks(endFileValidationRunnable);
                    mainHandler.postDelayed(endFileValidationRunnable, END_FILE_VALIDATION_DELAY_MS);
                    startStateRefresh();
                }
            }
            case MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                playbackState = Player.STATE_IDLE;
                loading = false;
                stopStateRefresh();
            }
            default -> {
            }
        }
        invalidateState();
    }

    private boolean isLikelyHls(MediaItem item, String uri) {
        if (uri != null && uri.startsWith("edl://")) return false;
        if (item.localConfiguration != null) {
            String mimeType = item.localConfiguration.mimeType;
            if (MimeTypes.APPLICATION_M3U8.equals(mimeType)
                    || "application/vnd.apple.mpegurl".equalsIgnoreCase(mimeType)
                    || "application/x-mpegurl".equalsIgnoreCase(mimeType)
                    || "hls".equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        String lower = uri == null ? "" : uri.toLowerCase(Locale.US);
        return lower.contains("m3u8");
    }

    private boolean loadedUnexpectedImage() {
        String path = stringProperty("path", "");
        if (!isImageUri(path)) return false;
        if (!TextUtils.isEmpty(currentPlayableUri) && sameUri(path, currentPlayableUri)) return false;
        Log.w(TAG, "unexpected image path=" + path + " requested=" + currentPlayableUri);
        return true;
    }

    private boolean isImageUri(String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        String lower = uri.toLowerCase(Locale.US);
        int end = lower.length();
        int query = lower.indexOf('?');
        int fragment = lower.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        lower = lower.substring(0, end);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    private boolean sameUri(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean shouldProxyHls(String uri, boolean likelyHls) {
        if (!likelyHls || TextUtils.isEmpty(uri)) return false;
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        return !"/mpv/index.m3u8".equals(parsed.getPath()) && !"/mpv/item".equals(parsed.getPath());
    }

    private Map<String, String> applyMediaOptions(MediaItem item) {
        Map<String, String> headers = new LinkedHashMap<>(extractHeaders(item));
        String userAgent = findHeader(headers, HttpHeaders.USER_AGENT);
        String referer = findHeader(headers, HttpHeaders.REFERER);
        if (TextUtils.isEmpty(userAgent)) userAgent = config.userAgent();
        if (TextUtils.isEmpty(referer)) referer = config.referer();
        if (TextUtils.isEmpty(referer) && item.localConfiguration != null) referer = originOf(item.localConfiguration.uri);
        String origin = findHeader(headers, HEADER_ORIGIN);
        if (!TextUtils.isEmpty(userAgent)) putHeader(headers, HttpHeaders.USER_AGENT, userAgent);
        if (!TextUtils.isEmpty(referer)) putHeader(headers, HttpHeaders.REFERER, referer);
        if (TextUtils.isEmpty(origin)) origin = originOf(referer);
        if (!TextUtils.isEmpty(origin)) putHeader(headers, HEADER_ORIGIN, origin);
        if (TextUtils.isEmpty(findHeader(headers, HEADER_ACCEPT))) putHeader(headers, HEADER_ACCEPT, "*/*");
        String headerFields = buildHeaderFields(headers);
        setRuntimeString("user-agent", userAgent == null ? "" : userAgent);
        setRuntimeString("referrer", referer == null ? "" : referer);
        setRuntimeString("http-header-fields", headerFields);
        if (item.mediaMetadata.title != null) setRuntimeString("force-media-title", item.mediaMetadata.title.toString());
        SpiderDebug.log("mpv", "media options uaEmpty=%s refererEmpty=%s originEmpty=%s headerNames=%s headerFields=%s",
                TextUtils.isEmpty(userAgent), TextUtils.isEmpty(referer), TextUtils.isEmpty(origin), headerNames(headers), !TextUtils.isEmpty(headerFields));
        return headers;
    }

    private Map<String, String> extractHeaders(MediaItem item) {
        if (item.requestMetadata.extras == null) return Map.of();
        android.os.Bundle extras = item.requestMetadata.extras;
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        for (String key : extras.keySet()) {
            String value = extras.getString(key);
            if (value != null) headers.put(key, value);
        }
        return headers;
    }

    private String buildHeaderFields(Map<String, String> headers) {
        if (headers.isEmpty()) return "";
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (equalsHeader(key, HttpHeaders.USER_AGENT) || equalsHeader(key, HttpHeaders.REFERER) || equalsHeader(key, HttpHeaders.RANGE)) continue;
            fields.add(key + ": " + escapeListValue(entry.getValue()));
        }
        return String.join(",", fields);
    }

    private void putHeader(Map<String, String> headers, String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        String existing = null;
        for (String key : headers.keySet()) {
            if (equalsHeader(key, name)) {
                existing = key;
                break;
            }
        }
        headers.put(existing == null ? name : existing, value.trim());
    }

    private List<String> headerNames(Map<String, String> headers) {
        if (headers.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String key : headers.keySet()) names.add(key);
        return names;
    }

    private String escapeListValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }

    @Nullable
    private String findHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (equalsHeader(entry.getKey(), name)) return entry.getValue();
        }
        return null;
    }

    private boolean equalsHeader(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    @Nullable
    private String originOf(String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        try {
            return originOf(Uri.parse(uri));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private String originOf(Uri uri) {
        if (uri == null || TextUtils.isEmpty(uri.getScheme()) || TextUtils.isEmpty(uri.getHost())) return null;
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (port > 0 && port != 80 && port != 443) return scheme + "://" + uri.getHost() + ":" + port;
        return scheme + "://" + uri.getHost();
    }

    private String playableUri(Uri uri) throws IOException {
        String value = uri.toString();
        if (isConcatenatingUri(value)) return edlUri(value);
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new IOException("Unable to open content uri: " + uri);
            contentFds.add(fd);
            return "fd://" + fd.getFd();
        }
        return value;
    }

    private boolean isConcatenatingUri(String uri) {
        return uri != null && uri.contains(CONCAT_SOURCE_SEPARATOR) && uri.contains(CONCAT_DURATION_SEPARATOR);
    }

    private String edlUri(String uri) throws IOException {
        StringBuilder builder = new StringBuilder("edl://");
        int count = 0;
        for (String split : uri.split(CONCAT_SOURCE_SEPARATOR_REGEX)) {
            String[] info = split.split(CONCAT_DURATION_SEPARATOR_REGEX, 2);
            if (info.length < 2 || TextUtils.isEmpty(info[0])) continue;
            if (count++ > 0) builder.append(';');
            builder.append("file=").append(edlValue(info[0]));
            long durationUs = parseLong(info[1], C.TIME_UNSET);
            if (durationUs > 0) builder.append(",length=").append(String.format(Locale.US, "%.3f", durationUs / MICROSECONDS_TO_SECONDS));
        }
        if (count == 0) throw new IOException("Invalid concatenating media uri");
        SpiderDebug.log("mpv", "concat uri converted to EDL segments=%d", count);
        return builder.toString();
    }

    private String edlValue(String value) {
        return "%" + value.getBytes(StandardCharsets.UTF_8).length + "%" + value;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void addSubtitleConfigurations() {
        if (mediaItem == null || mediaItem.localConfiguration == null || mediaItem.localConfiguration.subtitleConfigurations.isEmpty()) return;
        for (MediaItem.SubtitleConfiguration sub : mediaItem.localConfiguration.subtitleConfigurations) {
            Uri uri = sub.uri;
            try {
                MPVLib.command(new String[]{"sub-add", playableUri(uri), "auto"});
            } catch (Throwable ignored) {
            }
        }
        scheduleTrackRefresh();
    }

    private void setVideoOutput(Object output) {
        detachSurfaceHolder();
        if (output instanceof SurfaceView view) {
            setSurfaceHolder(view.getHolder());
        } else if (output instanceof TextureView view && view.getSurfaceTexture() != null) {
            releaseOwnedSurface();
            surface = new Surface(view.getSurfaceTexture());
            ownsSurface = true;
        } else if (output instanceof SurfaceHolder holder) {
            setSurfaceHolder(holder);
        } else if (output instanceof Surface s) {
            releaseOwnedSurface();
            surface = s;
            ownsSurface = false;
        }
        bindVideoOutput();
    }

    private void setSurfaceHolder(SurfaceHolder holder) {
        surfaceHolder = holder;
        surfaceHolder.addCallback(surfaceCallback);
        surface = surfaceHolder.getSurface();
        ownsSurface = false;
    }

    private void bindVideoOutput() {
        if (!initialized || surface == null || !surface.isValid()) return;
        try {
            if (surfaceAttached) detachMpvSurface();
            MPVLib.attachSurface(surface);
            surfaceAttached = true;
            setRuntimeString("force-window", "yes");
            safeSetPropertyString("android-surface-size", "0x0");
            safeSetPropertyString("vo", config.vo());
            SpiderDebug.log("mpv", "surface attached surface=%s vo=%s", surface, config.vo());
        } catch (Throwable e) {
            fail(mpvError(ERROR_VIDEO_OUTPUT_FAILED, e.getMessage(), e), PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
        }
    }

    private void clearVideoOutput() {
        detachSurfaceHolder();
        detachMpvSurface();
        releaseOwnedSurface();
        surface = null;
    }

    private void detachMpvSurface() {
        if (!initialized || !surfaceAttached) return;
        try {
            safeSetPropertyString("vo", "null");
            setRuntimeString("force-window", "no");
            MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        surfaceAttached = false;
    }

    private void detachSurfaceHolder() {
        if (surfaceHolder == null) return;
        try {
            surfaceHolder.removeCallback(surfaceCallback);
        } catch (Throwable ignored) {
        }
        surfaceHolder = null;
    }

    private void releaseOwnedSurface() {
        if (ownsSurface && surface != null) surface.release();
        ownsSurface = false;
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surface = holder.getSurface();
            bindVideoOutput();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            surface = holder.getSurface();
            if (initialized) safeSetPropertyString("android-surface-size", width + "x" + height);
            bindVideoOutput();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            surface = null;
            detachMpvSurface();
        }
    };

    private void stopInternal(boolean resetState) {
        stopMpv(true);
        closeContentFds();
        loading = false;
        fileLoaded = false;
        loadStarted = false;
        playbackRestarted = false;
        loadStartRetryCount = 0;
        eofReached = false;
        currentTracks = Tracks.EMPTY;
        currentChapters = List.of();
        currentChapter = C.INDEX_UNSET;
        cachedPositionMs = 0;
        cachedCacheDurationMs = 0;
        cachedDurationMs = C.TIME_UNSET;
        videoSize = VideoSize.UNKNOWN;
        playerError = null;
        pendingSeekPositionMs = C.TIME_UNSET;
        idleActive = false;
        currentPlayableUri = null;
        currentLikelyHls = false;
        resetFailureSignals();
        hlsProxy.clear();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.removeCallbacks(trackRefreshRunnable);
        if (resetState) playbackState = Player.STATE_IDLE;
        stopStateRefresh();
        invalidateState();
    }

    private void stopMpv(boolean markStopping) {
        if (!initialized) return;
        boolean previousStopping = stopping;
        if (markStopping) stopping = true;
        try {
            MPVLib.command(new String[]{"stop"});
        } catch (Throwable ignored) {
            stopping = previousStopping;
        }
    }

    private void resetMpvContextForNewMedia() {
        if (!initialized) return;
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.removeCallbacks(endFileValidationRunnable);
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.removeCallbacks(trackRefreshRunnable);
        try {
            if (surfaceAttached) MPVLib.detachSurface();
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.removeObserver(this);
            MPVLib.removeLogObserver(this);
            MPVLib.destroy();
        } catch (Throwable ignored) {
        }
        initialized = false;
        surfaceAttached = false;
        stopping = false;
        loadStarted = false;
        loadStartRetryCount = 0;
        currentTracks = Tracks.EMPTY;
        currentChapters = List.of();
        currentChapter = C.INDEX_UNSET;
        SpiderDebug.log("mpv", "context reset for new media");
    }

    private void refreshTracks() {
        if (released || !initialized) return;
        Tracks tracks = parseTracks(stringProperty("track-list", ""));
        if (tracks.isEmpty()) tracks = readTracksFromProperties();
        updateCurrentTracks(tracks);
    }

    private void handleTrackListProperty(@Nullable Object value) {
        Tracks tracks = value instanceof String string ? parseTracks(string) : Tracks.EMPTY;
        if (tracks.isEmpty()) tracks = readTracksFromProperties();
        updateCurrentTracks(tracks);
    }

    private void scheduleTrackRefresh() {
        mainHandler.removeCallbacks(trackRefreshRunnable);
        mainHandler.postDelayed(trackRefreshRunnable, TRACK_REFRESH_AFTER_SUBTITLE_MS);
    }

    private void updateCurrentTracks(Tracks tracks) {
        if (tracks == null) tracks = Tracks.EMPTY;
        if (tracks.equals(currentTracks)) return;
        currentTracks = tracks;
        applyTrackSelectionParameters();
        SpiderDebug.log("mpv", "tracks updated groups=%d", tracks.getGroups().size());
        invalidateState();
    }

    private Tracks parseTracks(String json) {
        if (TextUtils.isEmpty(json)) return Tracks.EMPTY;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[")) return Tracks.EMPTY;
        try {
            JSONArray array = new JSONArray(trimmed);
            List<Tracks.Group> groups = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Tracks.Group group = createTrackGroup(array.optJSONObject(i));
                if (group != null) groups.add(group);
            }
            return groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        } catch (Throwable ignored) {
            return Tracks.EMPTY;
        }
    }

    private Tracks readTracksFromProperties() {
        int count = intProperty("track-list/count", 0);
        if (count <= 0) return Tracks.EMPTY;
        List<Tracks.Group> groups = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Tracks.Group group = createTrackGroup(i);
            if (group != null) groups.add(group);
        }
        return groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
    }

    @Nullable
    private Tracks.Group createTrackGroup(@Nullable JSONObject json) {
        if (json == null) return null;
        String type = json.optString("type", "");
        int media3Type = media3TrackType(type);
        if (media3Type == C.TRACK_TYPE_UNKNOWN || json.optBoolean("image", false) || json.optBoolean("albumart", false)) return null;
        String id = String.valueOf(json.optInt("id", C.INDEX_UNSET));
        if (String.valueOf(C.INDEX_UNSET).equals(id)) return null;
        Format.Builder builder = new Format.Builder()
                .setId(id)
                .setLabel(firstNonEmpty(json.optString("title", null), fileName(json.optString("external-filename", null))))
                .setLanguage(emptyToNull(json.optString("lang", null)))
                .setCodecs(emptyToNull(json.optString("codec", null)))
                .setSampleMimeType(sampleMimeType(media3Type, json.optString("codec", null)))
                .setSelectionFlags(selectionFlags(json.optBoolean("default", false), json.optBoolean("forced", false)));
        applyVideoFormat(builder, json.optInt("demux-w", 0), json.optInt("demux-h", 0), (float) json.optDouble("demux-fps", 0));
        applyAudioFormat(builder, json.optInt("demux-channel-count", 0), json.optInt("demux-samplerate", 0));
        return createTrackGroup(media3Type, type, id, builder.build(), json.optBoolean("selected", false));
    }

    @Nullable
    private Tracks.Group createTrackGroup(int index) {
        String prefix = "track-list/" + index + "/";
        String type = stringProperty(prefix + "type", "");
        int media3Type = media3TrackType(type);
        if (media3Type == C.TRACK_TYPE_UNKNOWN || booleanProperty(prefix + "image", false) || booleanProperty(prefix + "albumart", false)) return null;
        int idValue = intProperty(prefix + "id", C.INDEX_UNSET);
        if (idValue == C.INDEX_UNSET) return null;
        String id = String.valueOf(idValue);
        String codec = stringProperty(prefix + "codec", null);
        Format.Builder builder = new Format.Builder()
                .setId(id)
                .setLabel(firstNonEmpty(stringProperty(prefix + "title", null), fileName(stringProperty(prefix + "external-filename", null))))
                .setLanguage(emptyToNull(stringProperty(prefix + "lang", null)))
                .setCodecs(emptyToNull(codec))
                .setSampleMimeType(sampleMimeType(media3Type, codec))
                .setSelectionFlags(selectionFlags(booleanProperty(prefix + "default", false), booleanProperty(prefix + "forced", false)));
        applyVideoFormat(builder, intProperty(prefix + "demux-w", 0), intProperty(prefix + "demux-h", 0), (float) doubleProperty(prefix + "demux-fps", 0));
        applyAudioFormat(builder, intProperty(prefix + "demux-channel-count", 0), intProperty(prefix + "demux-samplerate", 0));
        return createTrackGroup(media3Type, type, id, builder.build(), booleanProperty(prefix + "selected", false));
    }

    private Tracks.Group createTrackGroup(int media3Type, String mpvType, String id, Format format, boolean selected) {
        TrackGroup trackGroup = new TrackGroup("mpv-" + mpvType + "-" + id, format);
        return new Tracks.Group(trackGroup, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected});
    }

    private void applyVideoFormat(Format.Builder builder, int width, int height, float frameRate) {
        if (width > 0) builder.setWidth(width);
        if (height > 0) builder.setHeight(height);
        if (frameRate > 0) builder.setFrameRate(frameRate);
    }

    private void applyAudioFormat(Format.Builder builder, int channelCount, int sampleRate) {
        if (channelCount > 0) builder.setChannelCount(channelCount);
        if (sampleRate > 0) builder.setSampleRate(sampleRate);
    }

    private int selectionFlags(boolean defaultTrack, boolean forcedTrack) {
        int flags = 0;
        if (defaultTrack) flags |= C.SELECTION_FLAG_DEFAULT;
        if (forcedTrack) flags |= C.SELECTION_FLAG_FORCED;
        return flags;
    }

    private void applyTrackSelectionParameters() {
        if (!initialized) return;
        applyTrackSelection(C.TRACK_TYPE_VIDEO);
        applyTrackSelection(C.TRACK_TYPE_AUDIO);
        applyTrackSelection(C.TRACK_TYPE_TEXT);
    }

    private void applyTrackSelection(int type) {
        String property = trackProperty(type);
        if (property == null) return;
        if (trackSelectionParameters.disabledTrackTypes.contains(type)) {
            safeSetPropertyString(property, "no");
            return;
        }
        String selectedId = selectedTrackId(type);
        safeSetPropertyString(property, TextUtils.isEmpty(selectedId) ? "auto" : selectedId);
    }

    private void applyTextOffset() {
        if (initialized) safeSetPropertyDouble("sub-delay", textOffsetMs / SECONDS_TO_MS);
    }

    private void applyAudioOffset() {
        if (initialized) safeSetPropertyDouble("audio-delay", audioOffsetMs / SECONDS_TO_MS);
    }

    private void applySubtitleStyle() {
        if (!initialized) return;
        safeSetPropertyDouble("sub-scale", subtitleScale());
        safeSetPropertyDouble("sub-pos", subtitlePosition());
        applyCaptionStyle();
    }

    @Nullable
    private String selectedTrackId(int type) {
        boolean hasOverride = false;
        for (TrackSelectionOverride override : trackSelectionParameters.overrides.values()) {
            if (override.getType() != type || !isCurrentTrackGroup(override.mediaTrackGroup)) continue;
            hasOverride = true;
            if (override.trackIndices.isEmpty()) return null;
            int index = override.trackIndices.get(0);
            if (index < 0 || index >= override.mediaTrackGroup.length) return null;
            return override.mediaTrackGroup.getFormat(index).id;
        }
        if (!hasOverride && type == C.TRACK_TYPE_AUDIO && config.preferAac()) return preferredAudioTrackId();
        return null;
    }

    @Nullable
    private String preferredAudioTrackId() {
        for (Tracks.Group group : currentTracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                String codec = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
                String mime = format.sampleMimeType == null ? "" : format.sampleMimeType.toLowerCase(Locale.US);
                if ("aac".equals(codec) || codec.contains("mp4a") || MimeTypes.AUDIO_AAC.equals(mime)) return format.id;
            }
        }
        return null;
    }

    private boolean isCurrentTrackGroup(TrackGroup group) {
        for (Tracks.Group current : currentTracks.getGroups()) {
            if (current.getMediaTrackGroup().equals(group)) return true;
        }
        return false;
    }

    @Nullable
    private String trackProperty(int type) {
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> "vid";
            case C.TRACK_TYPE_AUDIO -> "aid";
            case C.TRACK_TYPE_TEXT -> "sid";
            default -> null;
        };
    }

    private int media3TrackType(String mpvType) {
        String value = mpvType == null ? "" : mpvType.trim().toLowerCase(Locale.US);
        if ("video".equals(value)) return C.TRACK_TYPE_VIDEO;
        if ("audio".equals(value)) return C.TRACK_TYPE_AUDIO;
        if ("sub".equals(value) || "subtitle".equals(value)) return C.TRACK_TYPE_TEXT;
        return C.TRACK_TYPE_UNKNOWN;
    }

    @Nullable
    private String sampleMimeType(int type, @Nullable String codec) {
        String value = codec == null ? "" : codec.trim().toLowerCase(Locale.US);
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> switch (value) {
                case "h264", "avc", "avc1" -> MimeTypes.VIDEO_H264;
                case "hevc", "h265", "hev1", "hvc1" -> MimeTypes.VIDEO_H265;
                case "av1" -> MimeTypes.VIDEO_AV1;
                case "vp9" -> MimeTypes.VIDEO_VP9;
                case "vp8" -> MimeTypes.VIDEO_VP8;
                case "mpeg2video" -> MimeTypes.VIDEO_MPEG2;
                case "mpeg4", "msmpeg4" -> MimeTypes.VIDEO_MP4V;
                case "mjpeg" -> MimeTypes.VIDEO_MJPEG;
                default -> MimeTypes.VIDEO_UNKNOWN;
            };
            case C.TRACK_TYPE_AUDIO -> switch (value) {
                case "aac" -> MimeTypes.AUDIO_AAC;
                case "mp3", "mp2", "mp1" -> MimeTypes.AUDIO_MPEG;
                case "ac3" -> MimeTypes.AUDIO_AC3;
                case "eac3", "eac3_at" -> MimeTypes.AUDIO_E_AC3;
                case "truehd" -> MimeTypes.AUDIO_TRUEHD;
                case "dts" -> MimeTypes.AUDIO_DTS;
                case "dts-hd", "dts_hd" -> MimeTypes.AUDIO_DTS_HD;
                case "flac" -> MimeTypes.AUDIO_FLAC;
                case "opus" -> MimeTypes.AUDIO_OPUS;
                case "vorbis" -> MimeTypes.AUDIO_VORBIS;
                default -> MimeTypes.AUDIO_UNKNOWN;
            };
            case C.TRACK_TYPE_TEXT -> switch (value) {
                case "ass", "ssa" -> MimeTypes.TEXT_SSA;
                case "subrip", "srt" -> MimeTypes.APPLICATION_SUBRIP;
                case "webvtt", "vtt" -> MimeTypes.TEXT_VTT;
                case "mov_text", "tx3g" -> MimeTypes.APPLICATION_TX3G;
                case "hdmv_pgs_subtitle", "pgs" -> MimeTypes.APPLICATION_PGS;
                case "dvd_subtitle", "vobsub" -> MimeTypes.APPLICATION_VOBSUB;
                default -> MimeTypes.TEXT_UNKNOWN;
            };
            default -> null;
        };
    }

    @Nullable
    private String firstNonEmpty(@Nullable String first, @Nullable String second) {
        first = emptyToNull(first);
        return first != null ? first : emptyToNull(second);
    }

    @Nullable
    private String emptyToNull(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    @Nullable
    private String fileName(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
    }

    private void seekMpv(long positionMs) {
        try {
            MPVLib.command(new String[]{"seek", String.format(Locale.US, "%.3f", positionMs / SECONDS_TO_MS), "absolute+exact"});
        } catch (Throwable e) {
            fail(e, PlaybackException.ERROR_CODE_UNSPECIFIED);
        }
    }

    private void loadCurrentUri() {
        if (currentLikelyHls) {
            MPVLib.command(new String[]{"loadfile", currentPlayableUri, "replace", "-1", HLS_LOAD_OPTIONS});
        } else {
            MPVLib.command(new String[]{"loadfile", currentPlayableUri, "replace"});
        }
    }

    private void scheduleLoadStartRetry() {
        mainHandler.removeCallbacks(loadStartRetryRunnable);
        mainHandler.postDelayed(loadStartRetryRunnable, LOAD_START_RETRY_DELAY_MS);
    }

    private void retryLoadIfNotStarted() {
        if (released || loadStarted || fileLoaded || playerError != null) return;
        if (playbackState != Player.STATE_BUFFERING || TextUtils.isEmpty(currentPlayableUri)) return;
        if (loadStartRetryCount >= MAX_LOAD_START_RETRIES) return;
        loadStartRetryCount++;
        SpiderDebug.log("mpv", "load retry attempt=%d uri=%s idle=%s", loadStartRetryCount, currentPlayableUri, booleanProperty("idle-active", idleActive));
        try {
            loadCurrentUri();
            scheduleLoadStartRetry();
        } catch (Throwable e) {
            fail(mpvError(ERROR_LOAD_FAILED, e.getMessage(), e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
    }

    private void updateVideoSize() {
        int width = intProperty("width", 0);
        int height = intProperty("height", 0);
        if (width > 0 && height > 0) videoSize = new VideoSize(width, height);
    }

    private void startStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
        mainHandler.postDelayed(stateRefreshRunnable, STATE_REFRESH_INTERVAL_MS);
    }

    private void stopStateRefresh() {
        mainHandler.removeCallbacks(stateRefreshRunnable);
    }

    private void refreshPlaybackState() {
        if (released || mediaItem == null || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || playerError != null) return;
        cachedPositionMs = positionMs();
        cachedDurationMs = durationMs();
        cachedCacheDurationMs = Math.max(0, doublePropertyMs("demuxer-cache-duration", cachedCacheDurationMs));
        invalidateState();
        startStateRefresh();
    }

    private void validateEarlyEndFile() {
        if (released || stopping || fileLoaded || eofReached || playerError != null || playbackState != Player.STATE_BUFFERING) return;
        if (booleanProperty("idle-active", idleActive)) {
            fail(mpvError(ERROR_LOAD_FAILED, "idle-active=true"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        } else {
            startStateRefresh();
        }
    }

    private boolean isFailedLoadedMedia() {
        if (!fileLoaded) return false;
        if (sawNoAvData || sawInvalidData || sawPngVideo) return true;
        if (recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png")) return true;
        return playbackRestarted && videoSize.width <= 0 && videoSize.height <= 0 && positionMs() <= 0 && durationMs() == C.TIME_UNSET;
    }

    private void refreshChapters() {
        if (released || !initialized) return;
        List<MediaEdition> chapters = parseChapters(stringProperty("chapter-list", ""));
        if (chapters.isEmpty()) chapters = readChaptersFromProperties();
        updateCurrentChapters(chapters);
    }

    private void handleChapterListProperty(@Nullable Object value) {
        List<MediaEdition> chapters = value instanceof String string ? parseChapters(string) : List.of();
        if (chapters.isEmpty()) chapters = readChaptersFromProperties();
        updateCurrentChapters(chapters);
    }

    private void updateCurrentChapters(List<MediaEdition> chapters) {
        if (chapters == null) chapters = List.of();
        if (chapters.equals(currentChapters)) return;
        currentChapters = chapters;
        SpiderDebug.log("mpv", "chapters updated count=%d selected=%d", chapters.size(), currentChapter);
        invalidateState();
    }

    private List<MediaEdition> parseChapters(String json) {
        if (TextUtils.isEmpty(json)) return List.of();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[")) return List.of();
        try {
            JSONArray array = new JSONArray(trimmed);
            List<MediaEdition> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                items.add(MediaEdition.edition(i, secondsToUs(item.optDouble("time", 0)), chapterLabel(i, item.optString("title", null)), i == currentChapter));
            }
            return items;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private List<MediaEdition> readChaptersFromProperties() {
        int count = intProperty("chapter-list/count", 0);
        if (count <= 0) return List.of();
        List<MediaEdition> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String prefix = "chapter-list/" + i + "/";
            items.add(MediaEdition.edition(i, secondsToUs(doubleProperty(prefix + "time", 0)), chapterLabel(i, stringProperty(prefix + "title", null)), i == currentChapter));
        }
        return items;
    }

    private String chapterLabel(int index, @Nullable String title) {
        title = emptyToNull(title);
        return title == null ? "Chapter " + (index + 1) : title;
    }

    private long secondsToUs(double seconds) {
        if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return 0;
        return Math.round(seconds * 1_000_000.0);
    }

    private double subtitleScale() {
        if (subtitleTextSize <= 0) return 1.0;
        return Math.max(0.5, Math.min(2.5, subtitleTextSize / DEFAULT_SUBTITLE_TEXT_SIZE_FRACTION));
    }

    private double subtitlePosition() {
        return Math.max(0, Math.min(150, 100.0 - subtitlePosition * 100.0));
    }

    private void applyCaptionStyle() {
        CaptionStyle style = captionStyle();
        safeSetPropertyString("sub-color", mpvColor(style.foreground));
        safeSetPropertyString("sub-border-color", mpvColor(style.edge));
        safeSetPropertyString("sub-shadow-color", mpvColor(style.edge));
        safeSetPropertyString("sub-back-color", mpvColor(style.background));
        safeSetPropertyDouble("sub-border-size", style.borderSize);
        safeSetPropertyDouble("sub-shadow-offset", style.shadowOffset);
    }

    private CaptionStyle captionStyle() {
        if (!PlayerSetting.isCaption()) return new CaptionStyle(Color.WHITE, Color.BLACK, Color.TRANSPARENT, 3.0, 0.0);
        try {
            CaptioningManager manager = (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
            CaptioningManager.CaptionStyle style = manager == null ? null : manager.getUserStyle();
            if (style == null) return new CaptionStyle(Color.WHITE, Color.BLACK, Color.TRANSPARENT, 3.0, 0.0);
            int foreground = style.hasForegroundColor() ? style.foregroundColor : Color.WHITE;
            int background = style.hasBackgroundColor() ? style.backgroundColor : Color.TRANSPARENT;
            int edge = style.hasEdgeColor() ? style.edgeColor : Color.BLACK;
            int edgeType = style.hasEdgeType() ? style.edgeType : CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE;
            return switch (edgeType) {
                case CaptioningManager.CaptionStyle.EDGE_TYPE_NONE -> new CaptionStyle(foreground, edge, background, 0.0, 0.0);
                case CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> new CaptionStyle(foreground, edge, background, 0.0, 2.0);
                case CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED, CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> new CaptionStyle(foreground, edge, background, 1.0, 1.0);
                default -> new CaptionStyle(foreground, edge, background, 3.0, 0.0);
            };
        } catch (Throwable ignored) {
            return new CaptionStyle(Color.WHITE, Color.BLACK, Color.TRANSPARENT, 3.0, 0.0);
        }
    }

    private String mpvColor(int color) {
        return String.format(Locale.US, "#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    private String failedLoadedMediaMessage() {
        if (currentLikelyHls && (sawNoAvData
                || sawInvalidData
                || sawPngVideo
                || recentLogsContain("no audio or video data played", "invalid data found when processing input", "video: png")
                || playbackRestarted && videoSize.width <= 0 && videoSize.height <= 0 && positionMs() <= 0)) {
            return ERROR_HLS_PLAYBACK_FAILED + detailSuffix("hls input failed");
        }
        if (sawNoAvData || recentLogsContain("no audio or video data played")) return ERROR_NO_AV_DATA + detailSuffix("no audio or video data played");
        if (sawInvalidData || sawPngVideo || recentLogsContain("invalid data found when processing input", "video: png")) return ERROR_INVALID_MEDIA_DATA + detailSuffix("invalid media data");
        return ERROR_DECODE_FAILED + detailSuffix("no playable audio/video output");
    }

    private IOException mpvError(String code, String detail) {
        return new IOException(code + detailSuffix(detail));
    }

    private IOException mpvError(String code, @Nullable String detail, Throwable cause) {
        return new IOException(code + detailSuffix(detail), cause);
    }

    private String detailSuffix(@Nullable String detail) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(detail)) builder.append(": ").append(detail);
        String recent = recentLogSuffix();
        if (!TextUtils.isEmpty(recent)) builder.append(recent);
        return builder.toString();
    }

    private void rememberLog(String line) {
        if (recentLogs.size() >= RECENT_LOG_LIMIT) recentLogs.remove(0);
        recentLogs.add(line);
    }

    private void markFailureSignal(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        if (lower.contains("no audio or video data played")) sawNoAvData = true;
        if (lower.contains("invalid data found when processing input")) sawInvalidData = true;
        if (lower.contains("video: png")) sawPngVideo = true;
        if (sawNoAvData || sawInvalidData || sawPngVideo || lower.contains("failed") || lower.contains("error")) lastFailureLog = line;
    }

    private boolean shouldDebugLogMpvLine(String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.US);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("invalid")
                || lower.contains("no audio")
                || lower.contains("video:")
                || lower.contains("audio:")
                || lower.contains("found 'hls'")
                || lower.contains("opening")
                || lower.contains("lavf")
                || lower.contains("demux")
                || lower.contains("codec")
                || lower.contains("track");
    }

    private void resetFailureSignals() {
        sawNoAvData = false;
        sawInvalidData = false;
        sawPngVideo = false;
        lastFailureLog = null;
    }

    private boolean recentLogsContain(String... needles) {
        for (String log : recentLogs) {
            String lower = log == null ? "" : log.toLowerCase(Locale.US);
            for (String needle : needles) {
                if (lower.contains(needle)) return true;
            }
        }
        return false;
    }

    private long positionMs() {
        if (initialized) cachedPositionMs = Math.max(0, doublePropertyMs("time-pos/full", doublePropertyMs("time-pos", cachedPositionMs)));
        return cachedPositionMs;
    }

    private long durationMs() {
        if (initialized) {
            long duration = doublePropertyMs("duration/full", doublePropertyMs("duration", cachedDurationMs));
            cachedDurationMs = duration > 0 ? duration : C.TIME_UNSET;
        }
        return cachedDurationMs > 0 ? cachedDurationMs : C.TIME_UNSET;
    }

    private long bufferedPositionMs(long position, long duration) {
        if (duration == C.TIME_UNSET || duration <= 0) return position;
        if (cachedCacheDurationMs > 0) return Math.min(duration, position + cachedCacheDurationMs);
        return playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED ? duration : position;
    }

    private boolean isPlayingInternal() {
        return playbackState == Player.STATE_READY && playWhenReady && !loading;
    }

    private long doublePropertyMs(String property, long fallback) {
        try {
            Double value = MPVLib.getPropertyDouble(property);
            if (value == null || value.isNaN() || value.isInfinite()) return fallback;
            return Math.max(0, Math.round(value * SECONDS_TO_MS));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private double doubleProperty(String property, double fallback) {
        try {
            Double value = MPVLib.getPropertyDouble(property);
            if (value == null || value.isNaN() || value.isInfinite()) return fallback;
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long doubleSecondsToMs(@Nullable Object value, long fallback) {
        if (value instanceof Number number) return Math.max(0, Math.round(number.doubleValue() * SECONDS_TO_MS));
        return fallback;
    }

    private int intProperty(String property, int fallback) {
        try {
            Integer value = MPVLib.getPropertyInt(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean booleanProperty(String property, boolean fallback) {
        try {
            Boolean value = MPVLib.getPropertyBoolean(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String stringProperty(String property, String fallback) {
        try {
            String value = MPVLib.getPropertyString(property);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void fail(Throwable e, int errorCode) {
        playerError = new PlaybackException(e.getMessage(), e, errorCode);
        playbackState = Player.STATE_IDLE;
        loading = false;
        fileLoaded = false;
        closeContentFds();
        mainHandler.removeCallbacks(endFileValidationRunnable);
        stopStateRefresh();
        SpiderDebug.log("mpv", "fail code=%d message=%s diagnostics=%s", errorCode, e.getMessage(), diagnosticSummary());
        invalidateState();
    }

    private String diagnosticSummary() {
        List<String> parts = new ArrayList<>();
        parts.add("uri=" + currentPlayableUri);
        parts.add("hls=" + currentLikelyHls);
        parts.add("loaded=" + fileLoaded);
        parts.add("restart=" + playbackRestarted);
        parts.add("size=" + videoSize.width + "x" + videoSize.height);
        parts.add("position=" + cachedPositionMs);
        parts.add("duration=" + cachedDurationMs);
        parts.add("tracks=" + currentTracks.getGroups().size());
        parts.add("path=" + stringProperty("path", ""));
        parts.add("file-format=" + stringProperty("file-format", ""));
        parts.add("video-codec=" + stringProperty("video-codec", ""));
        parts.add("audio-codec=" + stringProperty("audio-codec", ""));
        parts.add("hwdec=" + stringProperty("hwdec-current", ""));
        parts.add("vo=" + stringProperty("current-vo", stringProperty("vo-configured", "")));
        return String.join(" ", parts);
    }

    private String chapterText() {
        if (currentChapters.isEmpty()) return "-";
        return (currentChapter >= 0 ? currentChapter + 1 : 0) + "/" + currentChapters.size();
    }

    private String emptyDash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private String join(String delimiter, String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) if (!TextUtils.isEmpty(value)) parts.add(value);
        return TextUtils.join(delimiter, parts);
    }

    private String formatSeconds(double seconds) {
        if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return "-";
        return String.format(Locale.US, "%.1fs", seconds);
    }

    private String recentLogSuffix() {
        if (!TextUtils.isEmpty(lastFailureLog)) return ": " + lastFailureLog;
        if (recentLogs.isEmpty()) return "";
        return ": " + recentLogs.get(recentLogs.size() - 1);
    }

    private void postToMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else mainHandler.post(runnable);
    }

    private void setOption(String name, String value) {
        if (value == null) value = "";
        try {
            MPVLib.setOptionString(name, value);
        } catch (Throwable ignored) {
        }
    }

    private void setRuntimeString(String name, String value) {
        if (value == null) value = "";
        if (initialized) {
            try {
                MPVLib.setPropertyString(name, value);
                return;
            } catch (Throwable ignored) {
            }
        }
        setOption(name, value);
    }

    private void observe(String property, int format) {
        try {
            MPVLib.observeProperty(property, format);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyBoolean(String property, boolean value) {
        try {
            MPVLib.setPropertyBoolean(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyDouble(String property, double value) {
        try {
            MPVLib.setPropertyDouble(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyInt(String property, int value) {
        try {
            MPVLib.setPropertyInt(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void safeSetPropertyString(String property, String value) {
        try {
            MPVLib.setPropertyString(property, value);
        } catch (Throwable ignored) {
        }
    }

    private void closeContentFds() {
        if (contentFds.isEmpty()) return;
        for (ParcelFileDescriptor fd : contentFds) {
            try {
                fd.close();
            } catch (IOException ignored) {
            }
        }
        contentFds.clear();
    }

    private void copySupportAssets() throws IOException {
        copyAsset("cacert.pem", config.caFile());
        writeFontsConf(new File(config.configDir(), "fonts.conf"));
    }

    private void copyAsset(String name, File outFile) throws IOException {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(name, AssetManager.ACCESS_STREAMING)) {
            long size = in.available();
            if (outFile.length() == size && size > 0) return;
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create " + parent);
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
        }
    }

    private void writeFontsConf(File file) {
        String text = "<fontconfig>\n"
                + "<dir>/system/fonts/</dir>\n"
                + "<dir>/product/fonts/</dir>\n"
                + "<cachedir>" + config.cacheDir().getAbsolutePath() + "</cachedir>\n"
                + "<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>\n"
                + "<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n"
                + "<alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>\n"
                + "</fontconfig>\n";
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private record CaptionStyle(int foreground, int edge, int background, double borderSize, double shadowOffset) {
    }
}
