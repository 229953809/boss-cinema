package androidx.media3.mpvplayer;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MpvPlayerConfig {

    private static final long DEFAULT_DEMUXER_BYTES = 64L * 1024L * 1024L;

    private final File configDir;
    private final File cacheDir;
    private final File caFile;
    private final String userAgent;
    private final String referer;
    private final String hwdec;
    private final String vo;
    private final String ao;
    private final String audioSpdif;
    private final String hwdecSoftwareFallback;
    private final String logLevel;
    private final boolean tlsVerify;
    private final long demuxerMaxBytes;
    private final long demuxerMaxBackBytes;
    private final int demuxerReadaheadSecs;
    private final int cacheSecs;
    private final int cachePauseWaitSecs;
    private final long streamBufferSize;
    private final boolean preferAac;
    private final Map<String, String> extraOptions;

    private MpvPlayerConfig(Builder builder) {
        configDir = builder.configDir;
        cacheDir = builder.cacheDir;
        caFile = builder.caFile;
        userAgent = builder.userAgent;
        referer = builder.referer;
        hwdec = builder.hwdec;
        vo = builder.vo;
        ao = builder.ao;
        audioSpdif = builder.audioSpdif;
        hwdecSoftwareFallback = builder.hwdecSoftwareFallback;
        logLevel = builder.logLevel;
        tlsVerify = builder.tlsVerify;
        demuxerMaxBytes = builder.demuxerMaxBytes;
        demuxerMaxBackBytes = builder.demuxerMaxBackBytes;
        demuxerReadaheadSecs = builder.demuxerReadaheadSecs;
        cacheSecs = builder.cacheSecs;
        cachePauseWaitSecs = builder.cachePauseWaitSecs;
        streamBufferSize = builder.streamBufferSize;
        preferAac = builder.preferAac;
        extraOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extraOptions));
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    public File configDir() {
        return configDir;
    }

    public File cacheDir() {
        return cacheDir;
    }

    public File caFile() {
        return caFile;
    }

    @Nullable
    public String userAgent() {
        return userAgent;
    }

    @Nullable
    public String referer() {
        return referer;
    }

    public String hwdec() {
        return hwdec;
    }

    public String vo() {
        return vo;
    }

    public String ao() {
        return ao;
    }

    public String audioSpdif() {
        return audioSpdif;
    }

    public String hwdecSoftwareFallback() {
        return hwdecSoftwareFallback;
    }

    public String logLevel() {
        return logLevel;
    }

    public boolean tlsVerify() {
        return tlsVerify;
    }

    public long demuxerMaxBytes() {
        return demuxerMaxBytes;
    }

    public long demuxerMaxBackBytes() {
        return demuxerMaxBackBytes;
    }

    public int demuxerReadaheadSecs() {
        return demuxerReadaheadSecs;
    }

    public int cacheSecs() {
        return cacheSecs;
    }

    public int cachePauseWaitSecs() {
        return cachePauseWaitSecs;
    }

    public long streamBufferSize() {
        return streamBufferSize;
    }

    public boolean preferAac() {
        return preferAac;
    }

    public Map<String, String> extraOptions() {
        return extraOptions;
    }

    public static final class Builder {

        private final Map<String, String> extraOptions = new LinkedHashMap<>();
        private File configDir;
        private File cacheDir;
        private File caFile;
        private String userAgent;
        private String referer;
        private String hwdec = "mediacodec,mediacodec-copy";
        private String vo = "gpu";
        private String ao = "audiotrack,opensles";
        private String audioSpdif = "no";
        private String hwdecSoftwareFallback = "3";
        private String logLevel = "all=v";
        private boolean tlsVerify = true;
        private long demuxerMaxBytes = DEFAULT_DEMUXER_BYTES;
        private long demuxerMaxBackBytes = DEFAULT_DEMUXER_BYTES;
        private int demuxerReadaheadSecs = 20;
        private int cacheSecs = 20;
        private int cachePauseWaitSecs = 1;
        private long streamBufferSize = 1024L * 1024L;
        private boolean preferAac;

        private Builder(Context context) {
            Context app = context.getApplicationContext();
            configDir = app.getFilesDir();
            cacheDir = app.getCacheDir();
            caFile = new File(app.getFilesDir(), "cacert.pem");
        }

        public Builder configDir(File configDir) {
            this.configDir = configDir;
            return this;
        }

        public Builder cacheDir(File cacheDir) {
            this.cacheDir = cacheDir;
            return this;
        }

        public Builder caFile(File caFile) {
            this.caFile = caFile;
            return this;
        }

        public Builder userAgent(@Nullable String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder referer(@Nullable String referer) {
            this.referer = referer;
            return this;
        }

        public Builder hwdec(String hwdec) {
            this.hwdec = hwdec;
            return this;
        }

        public Builder vo(String vo) {
            this.vo = vo;
            return this;
        }

        public Builder ao(String ao) {
            this.ao = ao;
            return this;
        }

        public Builder audioSpdif(String audioSpdif) {
            this.audioSpdif = audioSpdif;
            return this;
        }

        public Builder hwdecSoftwareFallback(String hwdecSoftwareFallback) {
            this.hwdecSoftwareFallback = hwdecSoftwareFallback;
            return this;
        }

        public Builder logLevel(String logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        public Builder tlsVerify(boolean tlsVerify) {
            this.tlsVerify = tlsVerify;
            return this;
        }

        public Builder demuxerMaxBytes(long demuxerMaxBytes) {
            this.demuxerMaxBytes = demuxerMaxBytes;
            return this;
        }

        public Builder demuxerMaxBackBytes(long demuxerMaxBackBytes) {
            this.demuxerMaxBackBytes = demuxerMaxBackBytes;
            return this;
        }

        public Builder demuxerReadaheadSecs(int demuxerReadaheadSecs) {
            this.demuxerReadaheadSecs = demuxerReadaheadSecs;
            return this;
        }

        public Builder cacheSecs(int cacheSecs) {
            this.cacheSecs = cacheSecs;
            return this;
        }

        public Builder cachePauseWaitSecs(int cachePauseWaitSecs) {
            this.cachePauseWaitSecs = cachePauseWaitSecs;
            return this;
        }

        public Builder streamBufferSize(long streamBufferSize) {
            this.streamBufferSize = streamBufferSize;
            return this;
        }

        public Builder preferAac(boolean preferAac) {
            this.preferAac = preferAac;
            return this;
        }

        public Builder option(String name, String value) {
            extraOptions.put(name, value);
            return this;
        }

        public MpvPlayerConfig build() {
            return new MpvPlayerConfig(this);
        }
    }
}
