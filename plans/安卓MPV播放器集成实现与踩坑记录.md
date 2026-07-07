# 安卓 MPV 播放器集成实现与踩坑记录

记录时间：2026-07-07  
分支：`feature/android-mpv-player`  
回滚标签：`mpv-player-integration-20260707-075715`

## 当前结论

当前 MPV 播放器是基于 `libmpv` 集成，不是调用外部 MPV App。底层入口是 `is.xyz.mpv.MPVLib`，Media3 适配层是 `androidx.media3.mpvplayer.MpvPlayer`，播放器引擎入口是 `com.fongmi.android.tv.player.engine.MpvPlayerEngine`。

MPV 作为独立播放器类型接入，不能自动切换到 Exo。播放失败只能报错，由用户手动切换播放器。这一点是产品约束，也是排查问题时必须保持的边界。

本次可播放的关键不是“MPV 比 Exo 弱”，而是项目里 Exo 已经有成熟的 HLS/TS 容错链路，而 libmpv/FFmpeg 对部分异常源更严格。MPV 需要补齐这些输入层处理。

## 主要实现

### Media3 适配层

新增/使用的核心类：

- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayerConfig.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvHlsProxy.java`
- `app/src/main/java/is/xyz/mpv/MPVLib.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java`

`MpvPlayer` 基于 `SimpleBasePlayer` 实现 Media3 `Player` 行为映射：

- `setMediaItem` / `prepare` -> `MPVLib.command(loadfile ...)`
- `play` / `pause` -> `pause` 属性
- `seekTo` -> `seek absolute+exact`
- `duration` / `position` -> `duration/full`、`time-pos/full`
- `track-list` -> Media3 `Tracks`
- Media3 `TrackSelectionParameters` -> MPV `vid`、`aid`、`sid`
- `setTextOffsetMs` -> MPV `sub-delay`
- `setAudioOffsetMs` -> MPV `audio-delay`
- Surface -> `attachSurface` / `detachSurface`
- 状态 -> `STATE_BUFFERING`、`STATE_READY`、`STATE_ENDED`、`STATE_IDLE`

2026-07-07 追加：

- `MpvPlayer` 已声明 `COMMAND_GET_TRACKS` 和 `COMMAND_SET_TRACK_SELECTION_PARAMETERS`。
- 轨道发现先尝试解析 MPV `track-list` 字符串 JSON；如果当前 JNI 没有返回 JSON，则回退读取 `track-list/count` 和 `track-list/N/*` 子属性。
- 每个 MPV 轨道映射为一个 Media3 `Tracks.Group`，`Format.id` 保存 MPV 轨道 id，避免按 UI 顺序猜测音轨/字幕轨。
- `MpvPlayerEngine` 复用 Exo 的 `TrackUtil`，让现有轨道弹窗、历史轨道恢复、关闭字幕逻辑继续走同一套上层契约。
- 用户手动选轨时，MPV 只设置对应的 `vid`、`aid`、`sid`；重置时恢复 `auto`，字幕禁用时设置 `sid=no`。
- `FILE_LOADED`、`PLAYBACK_RESTART`、`VIDEO_RECONFIG`、`AUDIO_RECONFIG` 和 `sub-add` 后都会刷新轨道列表。
- MPV 已声明 `COMMAND_GET_TEXT_OFFSET`、`COMMAND_SET_TEXT_OFFSET`、`COMMAND_GET_AUDIO_OFFSET`、`COMMAND_SET_AUDIO_OFFSET`。
- 字幕延迟从 UI 的毫秒值换算成 MPV 秒值写入 `sub-delay`；音频延迟同样写入 `audio-delay`。
- 新媒体打开时会重新下发当前字幕/音频延迟，避免切集后 UI 状态和 MPV 实际属性不一致。
- MPV 失败已增加 Java 层错误前缀和本地化文案映射：
  - `MPV_LOAD_FAILED`
  - `MPV_HLS_PLAYBACK_FAILED`
  - `MPV_UNEXPECTED_IMAGE`
  - `MPV_NO_AV_DATA`
  - `MPV_INVALID_MEDIA_DATA`
  - `MPV_DECODE_FAILED`
  - `MPV_VIDEO_OUTPUT_FAILED`
- 失败日志会输出结构化诊断：uri、HLS、file-loaded、playback-restart、视频尺寸、position、duration、tracks、path、file-format、video/audio codec、hwdec、vo。

### Header 处理

MPV 路径会从 `MediaItem.requestMetadata.extras` 读取原始请求头，并补齐：

- `User-Agent`
- `Referer`
- `Origin`
- `Accept: */*`

显式传入的 header 优先保留。缺失 `Referer` 时按媒体 URL origin 自动补齐，缺失 `Origin` 时按 `Referer` 或媒体 URL origin 补齐。

对应 Exo 实现：

- `MediaSourceFactory.createMediaSource()` 每次用 `ExoUtil.extractHeaders(mediaItem)` 更新 `OkHttpDataSource.Factory` 默认请求头。
- HLS playlist、key、segment 由同一个 DataSource 链路继承 header。

MPV 必须显式设置 `user-agent`、`referrer`、`http-header-fields`，否则 playlist 可以打开但分片/key 可能因 header 不一致失败。

### HLS 代理

新增 `MpvHlsProxy`，只给 MPV HLS/http 场景使用。

流程：

1. MPV 准备播放 HLS 时，把远端 m3u8 改成本机地址：`http://127.0.0.1:<port>/mpv/index.m3u8?s=<session>`。
2. 代理用 OkHttp 拉原 m3u8，继承 MPV 整理后的 header。
3. 代理重写 m3u8 内的 segment URL、nested playlist URL、`#EXT-X-KEY URI="..."` 为本机 `/mpv/item`。
4. MPV 只访问本机 m3u8 和本机分片端点。
5. 本机分片端点再请求真实分片，必要时清洗异常前缀后流式返回给 MPV。

这个代理不接管 Exo/IJK，不改全局 server 行为。

2026-07-07 追加：

- `/mpv/item` 已透传 MPV 发来的 HTTP `Range`，上游返回 `206` 时同步透传 `Content-Range`、`Accept-Ranges`、`ETag`、`Last-Modified`。
- 普通二进制分片在上游有 `Content-Length` 时使用 fixed-length response，便于 MPV/FFmpeg 正确处理 seek 和 byte-range。
- 可能触发 PNG 前缀 TS 清洗的响应继续使用 chunked response，避免清洗后实际输出长度和上游 `Content-Length` 不一致。
- nested playlist 不转发 Range，仍按完整 playlist 拉取并重写 URL。

## 踩坑记录

### 1. 不能用自动切换播放器掩盖 MPV 错误

问题：

早期出现 MPV 报错后自动切 Exo，用户看到的是“播放器自动切换”，不利于确认 MPV 自身问题。

处理：

MPV 错误保持在 MPV 引擎内上报，播放器切换只允许用户手动操作。

对应 Exo：

Exo 是成熟默认播放器，但不能作为 MPV 自动 fallback。对照 Exo 是为了补齐处理逻辑，不是播放失败就切回 Exo。

### 2. 早期 END_FILE 不是根因

问题：

曾尝试把早期 `END_FILE` 改成“不立即报错，继续等 FILE_LOADED 或 15 秒播放超时”。这个方向只能缓解事件时序误判，不能解决黑屏。

根因：

实际日志中 MPV 已经 `file-loaded`，但视频尺寸是 `0x0`，并出现：

- `Video: png, none`
- `Invalid data found when processing input`
- `no audio or video data played`

这说明不是单纯等待事件的问题，而是输入流被识别成图片/坏数据。

对应 Exo：

Exo 对 HLS/TS 的读取不是靠等待更多事件解决，而是在 extractor 层对 TS 同步字节做容错。

### 3. PNG 伪装 TS 分片

问题源表现：

某些 HLS 分片 URL 会 302 到阿里图片 CDN，响应头是 `Content-Type: image/png`，开头是一个 1x1 PNG。PNG `IEND` 之后紧跟 TS 数据：

- 前 8 字节是 PNG signature。
- 找到 `IEND AE 42 60 82` 后，后面出现 `0x47` TS sync byte。
- `strings` 可见 `FFmpeg`、`x264` 等视频编码痕迹。

MPV/FFmpeg 的表现：

FFmpeg demuxer 根据开头 PNG 签名把流识别为 PNG，随后报：

- `Could not find codec parameters for stream 0 (Video: png, none)`
- `Invalid data found when processing input`

处理：

`MpvHlsProxy.PngPrefixStrippingInputStream` 在分片首包内检测 PNG signature 和 IEND。若 IEND 后看起来是 TS，则剥掉 PNG 前缀，只把 TS 数据返回给 MPV，并把 mime 改成 `video/MP2T`。

对应 Exo：

项目使用的 Media3 `TsExtractor` 会查找 `0x47` TS sync byte，并丢弃同步字节前的数据。关键逻辑在 `TsExtractor.sniff()` / `findEndOfFirstTsPacketInBuffer()`：

- `TsUtil.tryToFindSyncBytePosition(...)`
- `TsUtil.findSyncBytePosition(...)`
- 丢弃 sync byte 之前的数据

所以 Exo 可以容忍“前面有垃圾头、后面才是 TS”的分片。MPV 没有这层容错时会被 PNG 签名带偏。

### 4. HLS 强制参数不是万能解法

尝试过的 MPV 参数：

- `demuxer=lavf`
- `demuxer-lavf-format=hls`
- `demuxer-lavf-probesize=10485760`
- `demuxer-lavf-analyzeduration=5`
- `http-allow-redirect=yes`
- `hls-bitrate=max`

这些参数可以帮助 MPV 正确按 HLS 打开 playlist，但不能解决分片开头是 PNG 的情况。因为 demuxer 已经在 segment 级别被错误签名误导。

对应 Exo：

Exo 的解决点不是“加大探测”，而是 TS extractor 读包时不断找 sync byte。MPV 也应在进入 FFmpeg 前把输入清洗干净。

### 5. 切换集/视频后黑屏、连接超时

现象：

第一集可以播放，切换下一集或另一个视频后黑屏，最后 UI 显示“连接超时”。

日志特征：

- 新 session 已创建：`mpv-proxy: enabled session=N ...`
- `MpvPlayer` 记录了 `load uri=http://127.0.0.1:<port>/mpv/index.m3u8?s=N`
- 但后续没有新的 `event=start-file`
- 也没有新 playlist 请求
- 只看到 `load retry attempt=...`

中间还出现过旧会话 item 被清空后，旧 HLS 请求打到 `/mpv/item?s=old&id=...` 返回 404，进一步干扰判断。

处理：

1. `MpvHlsProxy` 保留最近 session，旧会话自然过期，不在新视频开始时立即清空旧 item 表。
2. `MpvPlayer` 在新媒体 `setMediaItem` 时重置 libmpv context：
   - detach surface
   - remove observer/log observer
   - destroy
   - 下一次 `prepare` 重新 create/init
   - 重新绑定同一个 Surface
3. 保留短暂 `loadfile` 启动重试日志作为诊断，但主要依赖 context reset 解决复用 libmpv 时 stop/load 异步竞态。

对应 Exo：

Exo 每次 `setMediaItem(item, position)` + `prepare()` 会创建新的 MediaSource/MediaPeriod/Loader 生命周期。旧 HLS chunk load 会在 release/stop 时关闭，不会复用一个失控的旧 HLS demuxer 去吞掉新媒体命令。

MPV 的 `loadfile` 和 `stop` 是异步命令；如果在同一个 libmpv context 内快速切换，旧 HLS demuxer 可能还在处理分片，新 `loadfile` 不一定可靠进入 `START_FILE`。因此 MPV 切媒体要模拟 Exo 的隔离语义。

### 6. Surface 不是这次黑屏主因

多次日志显示：

- `surface attached ... vo=gpu`
- `VO: [gpu] 1280x720 mediacodec`
- `event=playback-restart ... size=1280x720`

这说明至少在首播成功路径，Surface 绑定和硬解输出是正常的。黑屏主要来自输入层和切换生命周期，而不是单纯 Surface 没绑定。

对应 Exo：

Exo 的 Surface 生命周期由 `PlayerView` 和 ExoPlayer 渲染器管理。MPV 必须自己维护 `SurfaceHolder.Callback`、`attachSurface`、`detachSurface`，但这次问题不能简单归咎于渲染层。

### 7. HLS Range/byte-range 不能忽略

问题：

HLS 不一定都是“一个 URL 一个完整 TS 分片”。fMP4 HLS、`#EXT-X-BYTERANGE`、部分大文件切片和 seek 场景都会依赖 HTTP Range。如果本地代理忽略 MPV 发来的 `Range`，可能导致：

- MPV 请求某个 byte range，但代理返回完整文件。
- 上游返回 `206`，代理没有透传 `Content-Range`。
- seek 或 byte-range 分片被 FFmpeg 判断为输入不一致。
- 最终表现仍可能是黑屏、卡住或连接超时。

处理：

`MpvHlsProxy.serveItem()` 现在读取本地请求的 `Range`，转发给真实上游，并把上游 `206`、`Content-Range`、`Accept-Ranges` 等响应头透传给 MPV。普通二进制在长度可靠时使用 fixed-length response；PNG 伪装 TS 清洗路径继续 chunked，避免长度错误。

对应 Exo：

Exo 的 HLS/Extractor/DataSource 链路天然支持 byte range、init segment 和 seek 读。MPV 走本地代理时，代理必须承担这层 DataSource 责任，不能只重写 playlist URL。

### 8. MPV 轨道列表不能只依赖一种 JNI 返回格式

问题：

MPV 官方的 `track-list` 是结构化属性。不同 Android libmpv 封装可能选择不同暴露方式：有的把 node/list 转成 JSON 字符串，有的只适合读取 `track-list/count` 和 `track-list/N/*` 子属性。如果只按一种格式写，轨道弹窗可能一直为空，进一步导致历史音轨/字幕恢复失效。

处理：

`MpvPlayer.refreshTracks()` 采用双路径：

- 优先解析 `track-list` 字符串 JSON，兼容 AFinity 这类直接把 MPV track-list 映射成 Media3 `Tracks` 的实现。
- JSON 不可用时，回退逐项读取 `track-list/count`、`track-list/N/id`、`type`、`lang`、`title`、`codec`、`selected`、`default`、`forced`、`external-filename` 等子属性。

选择逻辑不按列表下标猜轨道，而是把 MPV 的真实轨道 id 存入 `Format.id`，用户手动选择后再映射到：

- 视频：`vid`
- 音频：`aid`
- 字幕：`sid`
- 关闭字幕：`sid=no`

对应 Exo：

Exo 的 `TrackUtil` 通过 `TrackSelectionOverride` 选中 `TrackGroup` 中的具体轨道，上层 UI 只认 Media3 `Tracks` 和 `PlayerHelper.describeFormat(format)`。MPV 这次保留同一套上层契约：UI、历史选择和关闭字幕仍走 `TrackUtil`，底层再把 Media3 selection 翻译成 MPV 属性。

后续注意：

- 外挂字幕 `sub-add` 后必须重新读取轨道列表，否则 UI 看不到新字幕。
- 旧媒体的 `TrackSelectionOverride` 不能直接套到新媒体，必须确认 override 对应的 `TrackGroup` 仍在当前 `Tracks` 内。
- 后续如果扩展第二字幕，要新增 `secondary-sid`，不要复用主字幕 `sid`。

### 9. 字幕/音频延迟单位不能照搬 Media3

问题：

项目 UI 和 Media3 扩展 command 使用毫秒：

- `Player.getTextOffsetMs()`
- `Player.setTextOffsetMs(long)`
- `Player.getAudioOffsetMs()`
- `Player.setAudioOffsetMs(long)`

MPV 属性使用秒：

- `sub-delay`
- `audio-delay`

如果直接把毫秒值写给 MPV，会把 `+100ms` 变成 `+100s`，表现为字幕或音频完全不同步。

处理：

`MpvPlayer` 内部保存毫秒状态，对外继续遵守 Media3/FongMi 扩展 API；写入 libmpv 时统一除以 `1000.0`。新媒体 `openCurrent()` 时重新应用当前延迟，避免切集后 MPV context 重建导致属性回默认值。

对应 Exo：

Exo/FongMi Media3 扩展直接支持 offset command，上层 `OffsetDialog` 只检查 command 是否可用，不区分播放器类型。MPV 必须声明同样的 command，才能让原有 offset UI 对 MPV 生效。

### 10. 没有 native reason/error 时，Java 错误分类只能是过渡层

问题：

当前仓库没有 `libplayer.so` 对应的 JNI/native 源码，Java 只能收到 `MPV_EVENT_END_FILE` 的 event id，拿不到 `mpv_event_end_file.reason` 和 `mpv_event_end_file.error`。这会导致 MPV 失败时很难正规区分：

- 正常 EOF
- 用户 stop
- 网络错误
- demuxer/container 错误
- decode 错误
- VO/AO 初始化失败

处理：

本次先在 Java 层补齐过渡分类：

- 加载超时/未进入 `START_FILE`：`MPV_LOAD_FAILED`
- HLS 输入层失败：`MPV_HLS_PLAYBACK_FAILED`
- 加载到图片而不是视频：`MPV_UNEXPECTED_IMAGE`
- 没有音视频数据：`MPV_NO_AV_DATA`
- 异常媒体数据：`MPV_INVALID_MEDIA_DATA`
- 已加载但没有可播放输出：`MPV_DECODE_FAILED`
- Surface/VO 输出失败：`MPV_VIDEO_OUTPUT_FAILED`

`MpvPlayerEngine.getErrorMessage()` 将这些前缀映射成本地化文案，避免用户界面只显示“连接超时”。`MpvPlayer.fail()` 会额外记录结构化 diagnostics，便于 adb 日志定位。

对应 Exo：

Exo 的 `PlaybackException.errorCode` 和 `PlaybackAnalyticsListener` 能提供 renderer、format、decoder 等上下文。MPV 要达到同等可靠度，后续必须把 native `END_FILE reason/error` 暴露到 Java；日志推断只能作为兜底，不应长期作为唯一错误来源。

## 参考过的开源项目

- `mpv-android-anime4k`：header 补齐、`http-header-fields`、`http-allow-redirect`、`hls-bitrate=max` 等 MPV option 有参考价值。
- `mpvRex` / `mPlayer`：网络流自动补 referer/origin、MPV option 组织、本地代理思路有参考价值。
- `OmniPlay`：运行期 `setPropertyString` 和 libmpv 生命周期处理可参考。
- `William-Player`：主要是 Electron/Web MPV，Android 原生价值有限。

结论：这些项目能提供 MPV option 和 libmpv 使用经验，但不能直接解决本项目的 Media3 适配、HLS 输入清洗、以及播放器切换生命周期问题。

## 后续修改检查清单

以后每次动 MPV 播放链路，必须先对照 Exo 的成熟实现：

1. Header 是否和 Exo `OkHttpDataSource` 行为一致。
2. Playlist、key、segment 是否继承同一套请求头。
3. HLS/TS 异常输入是否有 extractor 等价处理，而不是只调 MPV 参数。
4. 切换媒体时旧请求是否被隔离或取消，不允许污染新媒体。
5. 失败后是否仍保持“只手动切换播放器”，不能自动 fallback Exo。
6. 每次代码修改后打一个 Git tag，便于回滚。
7. 测试至少覆盖：
   - 首次播放 HLS
   - 同视频切集
   - 切到另一个 HLS 视频
   - 返回退出再进入
   - MPV 错误时不会自动切 Exo

## 回滚建议

优先用 tag 回滚：

```bash
git switch feature/android-mpv-player
git reset --hard mpv-player-integration-20260707-075715
```

如果只是临时对比，不要 reset：

```bash
git switch --detach mpv-player-integration-20260707-075715
```

注意：tag 必须打在 commit 上。以后不要只在脏工作区打 tag，否则无法覆盖实际修改内容。
