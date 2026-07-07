# 安卓 libmpv 二次开发指南

记录时间：2026-07-07  
分支：`feature/android-mpv-player`  
关联文档：

- `plans/安卓MPV播放器集成实现与踩坑记录.md`
- `plans/安卓MPV相对Exo功能缺口TODO.md`
- `third_party/mpv-libmpv-integration-notes.md`
- `third_party/mpv-android-release-2026-04-25.txt`

## 为什么需要这份文档

需要。MPV 集成不是普通 Java 播放器封装，它同时涉及：

- libmpv C API 的初始化顺序。
- mpv options / commands / properties 三套语义。
- Android Surface 生命周期。
- Media3 `Player` 状态映射。
- 本项目 Exo 成熟播放链路的行为对齐。
- 本项目自定义 HLS proxy、header、异常分片清洗。

如果没有一份二次开发文档，后续很容易继续犯以下错误：

- 把 `loadfile` 参数位置写错。
- 把 `FILE_LOADED` 当成可播放成功。
- 用等待 `END_FILE` 掩盖真实输入流问题。
- 忘记 playlist/key/segment header 一致性。
- 忘记 libmpv `stop/loadfile` 是异步行为。
- 在 MPV 失败时错误地自动切 Exo。

这份文档作为本项目 MPV 的 SDK/API 说明和开发守则。

## 官方文档核对记录

已用代理核对以下官方文件：

- `https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/client.h`
- `https://raw.githubusercontent.com/mpv-player/mpv/master/DOCS/man/input.rst`
- `https://raw.githubusercontent.com/mpv-player/mpv/master/DOCS/man/options.rst`

网络慢时使用：

```bash
export https_proxy=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
export all_proxy=socks5://127.0.0.1:7897
```

或者单条命令：

```bash
curl -L -x http://127.0.0.1:7897 https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/client.h
```

本次核对出的关键点：

- `mpv_create()` 后实例还未初始化，初始化前设置 options，`mpv_initialize()` 后再用 commands/properties 控制播放。
- `loadfile <url> [<flags> [<index> [<options>]]]` 的第四个参数才是 per-file options；从 mpv 0.38.0 起，如果要传第四个参数，第三个参数必须显式传 `-1`。
- 官方文档明确 `loadfile` 只是操作播放列表，会在旧文件真正停止、新文件真正开始加载前返回，所以不能把 `loadfile` 返回当成切换完成。
- `track-list` 提供 `count`、`N/id`、`N/type`、`N/title`、`N/lang`、`N/codec`、`N/selected`、`N/demux-w`、`N/demux-h`、`N/demux-fps` 等子属性，足够作为 Media3 `Tracks` 映射来源。
- `vid`、`aid`、`sid` 是轨道选择属性，运行期返回实际选中的轨道；不存在时可能返回 `no`。
- `audio-delay`、`sub-delay` 是音频/字幕延迟对应属性。
- `user-agent`、`http-header-fields`、`referrer`、`hls-bitrate` 是官方网络选项；`http-header-fields` 是 string list。
- 原生 `mpv_event_end_file` 有 `reason` 和 `error`，当前项目 JNI 只转发 event id，因此需要扩展 JNI 才能避免继续靠日志猜失败原因。

## 当前架构

调用链：

```text
PlayerManager
  -> PlayerEngine
    -> MpvPlayerEngine
      -> androidx.media3.mpvplayer.MpvPlayer
        -> is.xyz.mpv.MPVLib
          -> libplayer.so JNI
            -> libmpv.so / FFmpeg libs
```

关键文件：

- `app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayerConfig.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvHlsProxy.java`
- `app/src/main/java/is/xyz/mpv/MPVLib.java`

MPV 是基于 `libmpv` 集成，不是调用外部 MPV App。

## Native 资产和加载方式

MPV native 库放在 assets：

- `app/src/arm64_v8a/assets/mpv-libs/arm64-v8a/*.so`
- `app/src/armeabi_v7a/assets/mpv-libs/armeabi-v7a/*.so`

运行时由 `MPVLib.ensureLoaded(Context)` 复制到 app 私有目录，再按固定顺序 `System.load()`。

加载顺序：

```text
c++_shared
mvutil
mwresample
mwscale
mvcodec
mvformat
mvfilter
mvdevice
mpv
player
```

为什么不用 `jniLibs`：

- 项目已有 nextlib-media3ext，AAR 内含 `libavcodec.so`、`libavutil.so`、`libswresample.so`、`libswscale.so` 等。
- 为避免 Android linker 复用 nextlib 的 FFmpeg 库，MPV 的 FFmpeg 依赖改名后以 assets 加载。

改名关系：

- `libavcodec.so` -> `libmvcodec.so`
- `libavdevice.so` -> `libmvdevice.so`
- `libavfilter.so` -> `libmvfilter.so`
- `libavformat.so` -> `libmvformat.so`
- `libavutil.so` -> `libmvutil.so`
- `libswresample.so` -> `libmwresample.so`
- `libswscale.so` -> `libmwscale.so`

注意：

- 新增 ABI 时必须补齐整套 so，并确认 `Build.SUPPORTED_ABIS` 能选中。
- 替换 so 后必须重新验证依赖名，不能让 MPV 链到 nextlib 的 FFmpeg。
- `cacert.pem` 放在 `app/src/main/assets/cacert.pem`，启动时复制到 app files 目录给 mpv TLS 使用。

## libmpv API 基本规则

官方核心语义来自 `include/mpv/client.h`：

- `mpv_create()` 创建的是未初始化实例。
- 初始化前可以设置初始 options。
- `mpv_initialize()` 后再用 commands/properties 控制播放。
- 同步 API 可能阻塞，网络慢时尤其明显。
- 事件需要被消费，否则事件队列会拥塞。
- commands、options、properties 可能随 mpv 版本变化，必须防御式处理。

本项目 JNI 暴露在 `MPVLib.java`：

```java
create(Context appctx)
init()
destroy()
attachSurface(Surface surface)
detachSurface()
command(String[] cmd)
setOptionString(String name, String value)
getPropertyInt/String/Double/Boolean(...)
setPropertyInt/String/Double/Boolean(...)
observeProperty(String property, int format)
```

当前 JNI 没有暴露：

- `mpv_event_end_file.reason`
- `mpv_event_end_file.error`
- `MPV_FORMAT_NODE` / node list 到 Java 的结构化映射
- command result
- async command reply userdata

后续做轨道、章节、详细错误时，大概率要扩展 JNI。

## 初始化顺序

当前 `MpvPlayer.ensureInitialized()` 顺序：

1. `MPVLib.ensureLoaded(context)`
2. 复制 `cacert.pem`，写 `fonts.conf`
3. `MPVLib.create(context)`
4. `applyPreInitOptions()`
5. `MPVLib.init()`
6. `MPVLib.addObserver(this)`
7. `MPVLib.addLogObserver(this)`
8. `applyPostInitOptions()`
9. `observeProperties()`

规则：

- `config`、`config-dir`、`vo`、`gpu-context`、`hwdec`、`ao`、`tls-ca-file` 这类初始化敏感选项要在 `init()` 前设置。
- 播放中可变项优先用 `setProperty*()`，例如 `pause`、`speed`、`volume`、`loop-file`。
- 对不确定是否可运行期变更的 option，先查 mpv manual，再用实机日志验证。
- `destroy()` 前必须移除 observer/log observer，避免旧事件回调污染新实例。

## 当前固定 options

`MpvPlayer.applyPreInitOptions()` 当前设置：

- `config=yes`
- `config-dir=<app files dir>`
- `gpu-shader-cache-dir=<app cache dir>`
- `icc-cache-dir=<app cache dir>`
- `profile=fast`
- `vo=gpu`
- `gpu-context=android`
- `opengl-es=yes`
- `hwdec=mediacodec,mediacodec-copy` 或 `no`
- `hwdec-codecs=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1`
- `ao=audiotrack,opensles`
- `audio-set-media-role=yes`
- `tls-verify=yes/no`
- `tls-ca-file=<cacert.pem>`
- `input-default-bindings=yes`
- `cache=yes`
- `http-allow-redirect=yes`
- `hls-bitrate=max`
- `demuxer-max-bytes=<config>`
- `demuxer-max-back-bytes=<config>`
- `demuxer-readahead-secs=20`
- `volume-max=100`
- `msg-level=<config>`

`applyPostInitOptions()` 当前设置：

- `save-position-on-quit=no`
- `force-window=no`
- `idle=once`

改这些选项前必须回答：

1. 这个选项是初始化前还是运行期？
2. 是否影响 Surface 输出？
3. 是否影响切集/stop/loadfile 异步行为？
4. Exo 对应功能在哪里实现？
5. 是否需要同步修改 `MpvPlayerConfig` 或设置 UI？

## command 使用规则

`MPVLib.command(String[] cmd)` 对应 libmpv `mpv_command()`，参数必须预拆分。

### loadfile

官方语义：

```text
loadfile <url> [<flags> [<index> [<options>]]]
```

本项目 HLS 强制 lavf 的正确写法：

```java
MPVLib.command(new String[]{
        "loadfile",
        currentPlayableUri,
        "replace",
        "-1",
        "demuxer=lavf,demuxer-lavf-format=hls,demuxer-lavf-probesize=10485760,demuxer-lavf-analyzeduration=5"
});
```

错误写法：

```java
MPVLib.command(new String[]{
        "loadfile",
        currentPlayableUri,
        "replace",
        "demuxer=lavf"
});
```

错误原因：

- 第四个字符串会被当成 index。
- mpv 会报 `The loadfile option must be an integer`。

### stop

当前：

```java
MPVLib.command(new String[]{"stop"});
```

注意：

- `stop` 是异步影响播放状态，不代表旧 HLS demuxer/请求已经完全结束。
- 快速切媒体时不能只依赖 `stop` 后立刻 `loadfile`。
- 当前稳定策略是新媒体前 reset libmpv context。

### seek

当前：

```java
MPVLib.command(new String[]{"seek", seconds, "absolute+exact"});
```

注意：

- UI 用毫秒，MPV 用秒。
- 格式化必须使用 `Locale.US`，避免小数点受系统 locale 影响。

### sub-add

当前：

```java
MPVLib.command(new String[]{"sub-add", playableUri(uri), "auto"});
```

注意：

- 只添加字幕不等于 UI 可见轨道。
- 添加后需要刷新 `track-list` 并通知 Media3 tracks changed。

## properties 使用规则

当前已 observe：

- `time-pos`
- `time-pos/full`
- `duration`
- `duration/full`
- `demuxer-cache-duration`
- `pause`
- `paused-for-cache`
- `eof-reached`
- `idle-active`
- `width`
- `height`

当前运行期设置：

- `pause`
- `loop-file`
- `speed`
- `volume`
- `force-window`
- `android-surface-size`
- `vo`
- `user-agent`
- `referrer`
- `http-header-fields`
- `force-media-title`

后续建议新增：

- `track-list` 或 `track-list/count` 系列：轨道发现。
- `aid`、`sid`、`vid`：轨道选择。
- `audio-delay`、`sub-delay`：音频/字幕延迟。
- `video-codec`、`audio-codec`、`hwdec-current`：诊断。
- `container-fps`、`estimated-vf-fps`：帧率诊断。
- `decoder-frame-drop-count`、`frame-drop-count`：卡顿诊断。

注意：

- 具体 property 名称和类型必须查 mpv manual 当前版本。
- 对 list/map 类型，不要用字符串硬拆，优先扩展 JNI 支持 `MPV_FORMAT_NODE`。

## 事件映射

当前 Java 只收到 event id：

- `MPV_EVENT_START_FILE`
- `MPV_EVENT_FILE_LOADED`
- `MPV_EVENT_PLAYBACK_RESTART`
- `MPV_EVENT_VIDEO_RECONFIG`
- `MPV_EVENT_END_FILE`
- `MPV_EVENT_IDLE`
- `MPV_EVENT_SHUTDOWN`

Media3 状态映射原则：

- `START_FILE` -> `STATE_BUFFERING`
- `FILE_LOADED` -> 仍然 `STATE_BUFFERING`
- `PLAYBACK_RESTART` -> `STATE_READY`
- 正常 EOF -> `STATE_ENDED`
- 失败 -> `PlaybackException` + `STATE_IDLE`

关键规则：

- 不要把 `FILE_LOADED` 当成播放成功。
- `FILE_LOADED` 只说明文件/流已加载，可能随后因为没有音视频、解码失败、伪装 PNG 等立刻失败。
- 当前没有 native end_file reason，所以 Java 只能结合 logs/properties 兜底判断。
- 后续必须扩展 JNI，把 end_file reason/error 传上来。

## Surface 生命周期

当前支持：

- `SurfaceView`
- `TextureView`
- `SurfaceHolder`
- `Surface`

绑定流程：

- `handleSetVideoOutput()` 保存 video output。
- `setVideoOutput()` 建立 Surface/SurfaceHolder。
- `bindVideoOutput()` 调用 `MPVLib.attachSurface(surface)`。
- 设置 `force-window=yes`。
- 设置 `android-surface-size`。
- Surface 销毁时 `detachSurface()`。

注意：

- 首播成功日志里出现 `VO: [gpu] ... mediacodec` 和 `playback-restart`，说明 Surface 不是此前黑屏主因。
- 切媒体 reset context 后要重新绑定同一个 Surface。
- 不要在 Surface 无效时强 attach。

## Header 处理

Exo 路径：

- `ExoUtil.getMediaItem()` 把 `PlaySpec.headers` 放进 `RequestMetadata.extras`。
- `MediaSourceFactory.createMediaSource()` 调用 `ExoUtil.extractHeaders(mediaItem)`。
- `OkHttpDataSource.Factory.setDefaultRequestProperties(headers)` 让 playlist/key/segment 继承同一套 headers。

MPV 路径：

- `MpvPlayer.applyMediaOptions()` 从 `RequestMetadata.extras` 读取 header。
- 补齐 `User-Agent`、`Referer`、`Origin`、`Accept`。
- 设置 `user-agent`、`referrer`、`http-header-fields`。
- HLS proxy 用同一套 headers 请求 playlist/key/segment。

规则：

- `User-Agent` 和 `Referer` 用专门 property。
- 其他 header 进入 `http-header-fields`。
- `Range` 不应作为固定 header 写入 mpv property，Range 是每次请求语义。
- header 值进入 `http-header-fields` 时要处理逗号转义。
- 任何 header 修复都要对照 Exo 的 OkHttp 行为。

## HLS proxy 设计

当前启用条件：

- `MediaItem` mimeType 是 m3u8/hls，或 URL 包含 `m3u8`。
- URL scheme 是 http/https。
- 不是已经代理过的 `/mpv/index.m3u8` 或 `/mpv/item`。

流程：

1. 原始 HLS URL -> 本机 `http://127.0.0.1:<port>/mpv/index.m3u8?s=<session>`。
2. proxy 请求原 playlist。
3. 重写普通 segment 行。
4. 重写 `URI="..."`，覆盖 key、map、nested playlist 等。
5. MPV 请求本机 item。
6. proxy 请求真实 item。
7. 如果 item 是 nested playlist，则继续重写。
8. 如果 item 是 PNG 前缀 TS，则剥掉 PNG prefix 后返回 `video/MP2T`。

当前限制：

- Range 还未完整透传。
- Byte-range/fMP4/live playlist 未系统验证。
- 错误分类还不够细。
- 代理 session TTL 是兜底策略，不是完整请求取消机制。

改 proxy 前必须对照：

- Exo `MediaSourceFactory`
- Media3 HLS playlist/key/segment 行为
- 上一个踩坑文档里的 PNG 前缀 TS 案例

## content:// 和 fd://

当前：

- `playableUri()` 对 `content://` 调 `ContentResolver.openFileDescriptor(uri, "r")`。
- 返回 `fd://<fd>` 给 MPV。
- `ParcelFileDescriptor` 保存在 `contentFds`，stop/release 时关闭。

注意：

- fd 生命周期必须覆盖整个播放过程。
- 切媒体/stop/release 必须关闭旧 fd。
- 字幕 `content://` 也走同一个 `playableUri()`。

## Media3 Player 适配边界

当前 MPV 已实现：

- set media item
- prepare
- play/pause
- stop/release
- seek
- repeat one
- speed
- volume
- surface
- duration/position/buffered position 的基础映射

当前 MPV 未完整实现：

- Tracks
- TrackSelectionParameters
- text/audio offset commands
- MediaEdition
- `getVideoFormat()`
- video effects
- detailed analytics
- DRM
- Exo PreCache 等价能力

开发规则：

- 如果 UI 已通过 `PlayerManager` 调用某个能力，MPV 要么实现，要么明确返回不支持。
- 不要让 UI 以为 MPV 支持但实际 no-op。
- 能力不支持时要避免黑屏/超时，优先给明确提示或隐藏入口。

## 错误处理规则

当前 MPV 错误来源：

- libmpv event
- observed properties
- mpv log lines
- HLS proxy HTTP 错误
- Java 异常

当前已识别的失败信号：

- `no audio or video data played`
- `Invalid data found when processing input`
- `Video: png`
- loaded image path 和请求视频 URL 不一致

规则：

- `END_FILE` 不是必然失败。
- `FILE_LOADED` 不是必然成功。
- 黑屏优先查输入层和生命周期，不要直接猜 Surface。
- 连接超时可能是上层 `PlayerManager` timeout，不等于真实网络 timeout。
- 新增错误处理时必须让 `MpvPlayerEngine.getErrorMessage()` 返回用户能理解的文本。

## 与 Exo 对齐的方法

遇到问题固定顺序：

1. 复现并抓日志。
2. 确认 Exo 能否播放同一资源。
3. 看 Exo 代码对应层：
   - URL/header：`ExoUtil.getMediaItem()`、`MediaSourceFactory`
   - HLS/TS：Media3 HLS + extractor 行为
   - 轨道：`TrackUtil`
   - 缓存：`PreCache`
   - 错误：`ExoPlayerEngine.handleError()`、`ErrorMsgProvider`
   - 性能：`ExoUtil.buildPlayer()`
4. 查 mpv 官方 manual/client API。
5. 搜索 Android libmpv 开源项目。
6. 只改最小必要层。
7. 实机验证首播、切集、切视频、返回重进。
8. 把坑写回 `plans/安卓MPV播放器集成实现与踩坑记录.md`。
9. 提交并打 tag。

## 常用日志和验证

构建：

```bash
bash gradlew :app:assembleMobileArm64_v8aDebug --no-daemon
```

安装：

```bash
adb install -r app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk
```

建议关注日志 tag/关键词：

- `mpv`
- `mpv-proxy`
- `player-engine`
- `player`
- `state=BUFFERING`
- `event=start-file`
- `event=file-loaded`
- `event=playback-restart`
- `END_FILE`
- `Invalid data`
- `Video: png`
- `no audio or video`
- `surface attached`

最低测试矩阵：

- HTTP MP4 首播。
- HLS TS 首播。
- HLS fMP4 首播。
- 同视频切集。
- 切到另一个视频。
- 外挂字幕。
- 音轨切换。
- seek。
- 软解/硬解切换。
- 返回退出后重新进入。
- 失败时不自动切 Exo。

## 禁止事项

- 禁止 MPV 播放失败后自动切 Exo。
- 禁止把 Exo 当 fallback 掩盖 MPV bug。
- 禁止在没有查 Exo 成熟实现前盲调 mpv options。
- 禁止把 `FILE_LOADED` 当成可播放成功。
- 禁止用延长 timeout 掩盖输入流解析错误。
- 禁止在脏工作区不确认改动来源就提交。
- 禁止改 MPV native so 后不记录来源和校验信息。

## 每次 MPV 二次开发必须更新的文档

根据修改类型更新：

- 功能缺口或计划变化：`plans/安卓MPV相对Exo功能缺口TODO.md`
- API/调用方式/生命周期变化：`plans/安卓libmpv二次开发指南.md`
- 黑屏/报错/踩坑/开源参考结论：`plans/安卓MPV播放器集成实现与踩坑记录.md`
- native so 来源变化：`third_party/mpv-android-release-*.txt`

## 当前最需要补的 SDK/API 能力

优先扩展 JNI：

1. `MPV_EVENT_END_FILE` reason/error。
2. `MPV_FORMAT_NODE` 到 Java 的结构化读取。
3. command result 或异步 command reply。

优先扩展 Java 适配：

1. `track-list` -> Media3 `Tracks`。
2. `aid/sid/vid` -> 项目 `TrackUtil` 等价选择。
3. `audio-delay/sub-delay` -> PlayerManager offset 接口。
4. MPV diagnostic snapshot -> OSD。

这些能力补齐后，后续 MPV 问题才能更接近“工程调试”，而不是靠猜日志。
