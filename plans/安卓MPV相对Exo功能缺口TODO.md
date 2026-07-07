# 安卓 MPV 相对 Exo 功能缺口 TODO

记录时间：2026-07-07  
分支：`feature/android-mpv-player`  
关联文档：

- `plans/安卓MPV播放器集成实现与踩坑记录.md`
- `plans/安卓libmpv二次开发指南.md`

## 文档目标

这份文档只记录 MPV 播放器相对当前 Exo 播放链路还缺什么，以及后续补齐时的实施顺序。

当前 MPV 已完成的是“作为独立播放器可进入主播放流程并能播放部分 HLS/HTTP 资源”。但 Exo 不是只有播放内核，它还承担了网络输入、缓存、DRM、轨道、字幕、错误恢复、诊断、性能策略、LUT 等一整套工程能力。MPV 后续开发必须按这些能力逐项对齐。

产品边界不变：

- MPV 失败后不允许自动切换 Exo。
- Exo 只能作为成熟实现的参照，不是 MPV 的自动 fallback。
- 任何 MPV 问题优先补齐 MPV 输入层、生命周期或能力映射。

## 当前接续状态

下一次重新打开 Codex 时，优先阅读这份文档即可知道 MPV 继续工作方向。更细的踩坑和实现原因再读：

- `plans/安卓MPV播放器集成实现与踩坑记录.md`
- `plans/安卓libmpv二次开发指南.md`
- `plans/安卓MPV播放器最佳实践与专项优化路线.md`

截至 2026-07-07 当前批次已补齐：

- 轨道发现/选择：MPV `track-list` -> Media3 `Tracks`，`vid/aid/sid` 选择，字幕关闭 `sid=no`。
- 字幕/音频延迟：Media3 毫秒接口 -> MPV `sub-delay` / `audio-delay` 秒属性。
- 字幕样式：现有字幕面板可控制 MPV `sub-scale` / `sub-pos`，系统 caption 样式映射到 MPV libass 颜色/描边/阴影。
- 重复播放：MPV 支持 `REPEAT_MODE_ONE`。
- 章节/标题：MPV `chapter-list` / `chapter` 映射到现有 MediaEdition/标题 UI。
- 播放参数面板通用项：缓冲时间、缓冲容量、回退缓冲、音频直通、AAC 优先已映射到 MPV。
- HLS 输入层：MPV 本地代理支持 playlist/nested playlist/URI 重写、Range/206、fMP4 init map/key URI、VOD 分片缓存、预载、seek 后按位置预载。
- 拼接源：Exo 同款 `url|||duration***url|||duration` 已转换为 MPV inline EDL。
- 直播恢复：直播 HLS 代理识别非 VOD playlist 和最近 HTTP 错误后，最多两次在 MPV 内核内重新加载，不切 Exo、不自动软解。
- 诊断：OSD 和当前媒体报告可显示 MPV runtime diagnostics，包含 HLS proxy session、缓存容量、playlist 类型、请求数、最近状态。
- 视频硬解策略：hard 模式已关闭 `hwdec-software-fallback`，视频硬解失败不自动切软解；软解只能用户手动切。
- DRM：MPV 遇到 MediaDrm 资源明确报 `MPV_DRM_UNSUPPORTED`。
- LUT/Media3 VideoEffect：用户已决定暂缓，不在当前批次实现。

最近下一步优先级：

1. 实机验证本批次所有按钮：轨道、字幕、延迟、重复、章节、播放参数面板、OSD 诊断、拼接源、VOD HLS seek 后预载、直播 HLS 重载。
2. 补 native `END_FILE reason/error`：当前仓库没有 `libplayer.so` JNI/C 源码，Java 侧只能日志推断，需补 native 来源后继续。
3. MPV 专项 UI：双字幕、截图/缩略图、shader/profile。LUT 用户已决定单独排期。
4. 若实机发现 Exo 能播但 MPV 仍黑屏，先对照 Exo 的 MediaSource/HLS/PreCache，再查 mpv 文档和开源实现，最后回写踩坑文档。

## 优先级定义

- P0：影响基础播放、切集、错误定位，或者会造成用户明显黑屏/超时/功能不可见。
- P1：影响主要观影体验、性能、兼容性，但不一定阻断所有播放。
- P2：增强能力、可配置能力、调试体验和长期演进能力。

## P0 待补齐

### [x] 1. MPV 轨道发现与选择（代码完成，待实机验证）

现状：

- `MpvPlayer` 已将 MPV `track-list` 映射为 Media3 `Tracks`。
- `MpvPlayerEngine.setTrack()` / `resetTrack()` 已复用 Exo 同款 `TrackUtil`。
- MPV 内部已处理 `TrackSelectionParameters`，把手动选择映射为 `vid`、`aid`、`sid`。
- 字幕禁用映射为 `sid=no`，重置映射为 `auto`。

Exo 对应实现：

- `ExoPlayerEngine.setTrack()` 调用 `TrackUtil.setTrackSelection(player, tracks)`。
- `TrackUtil` 读取 `player.getCurrentTracks()`，通过 `TrackSelectionOverride` 选择音轨、视频轨、字幕轨。
- UI 依赖 `PlayerManager.onTracksChanged()` 触发轨道弹窗和历史轨道恢复。

实施方向：

已实施：

- 先尝试解析 `track-list` 字符串 JSON。
- 若 JNI 不返回 JSON，则回退逐项读取 `track-list/count`、`track-list/N/type`、`track-list/N/id`、`track-list/N/lang`、`track-list/N/title`、`track-list/N/codec`、`track-list/N/selected` 等子属性。
- 一个 MPV 轨道映射为一个 Media3 `Tracks.Group`，`Format.id` 保存 MPV 轨道 id，保证后续选择不按 UI 顺序猜测。
- `handleSetTrackSelectionParameters()` 根据当前 override 设置 `vid`、`aid`、`sid`。
- `FILE_LOADED`、`PLAYBACK_RESTART`、`VIDEO_RECONFIG`、`AUDIO_RECONFIG` 和 `sub-add` 后都会刷新轨道。

后续验证：

- 音轨/字幕轨弹窗在 MPV 下可见。
- 选择音轨/字幕后立即生效。
- 历史轨道选择可恢复。
- 关闭字幕可生效。
- 外挂字幕 `sub-add` 后能进入字幕列表。

### [x] 2. 外挂字幕和字幕样式通用能力（代码完成，待实机验证）

现状：

- `MpvPlayer.addSubtitleConfigurations()` 已调用 `sub-add`。
- MPV 字幕轨已经通过 `track-list` 回填到 Media3 `Tracks`。
- `COMMAND_GET_TEXT_OFFSET` / `COMMAND_SET_TEXT_OFFSET` 已映射到 MPV `sub-delay`。
- `COMMAND_GET_AUDIO_OFFSET` / `COMMAND_SET_AUDIO_OFFSET` 已映射到 MPV `audio-delay`。
- 现有字幕样式面板已支持 MPV：字体大小映射 `sub-scale`，位置映射 `sub-pos`。
- 系统 caption style 已映射到 MPV libass：`sub-color`、`sub-border-color`、`sub-shadow-color`、`sub-back-color`、`sub-border-size`、`sub-shadow-offset`。
- Exo 的字幕样式由 `ExoUtil.setPlayerView()` 配置 `SubtitleView`，MPV 走 libass/osd，不共享这套 UI 字幕渲染。

Exo 对应实现：

- `ExoUtil.getMediaItem()` 把 `PlaySpec.subs` 转成 `MediaItem.SubtitleConfiguration`。
- Exo 自动纳入 text tracks。
- 字幕样式由 `PlayerView.getSubtitleView()` 控制。

实施方向：

- 保留 `sub-add` 和轨道刷新，确保外挂字幕加入后 UI 可选择。
- 已完成：MPV 字幕延迟映射到 `sub-delay`，音频延迟映射到 `audio-delay`。
- 已完成：`PlayerSetting` 字幕字体大小、位置通过 `PlayerEngine.setSubtitleStyle()` 同步给 MPV。
- 已完成：Android 系统 caption 的颜色、背景、描边/阴影基础样式映射到 MPV。
- 待专项 UI：第二字幕 `secondary-sid`、第二字幕样式、ASS override 更细项、字体选择。
- 对 ASS/SSA/SRT/VTT 分别测试。

验收：

- MPV 可加载外部字幕。
- 字幕列表可见、可切换、可关闭。
- 字幕延迟调整可用。
- 字幕大小/位置调整可用。
- 系统 caption 基础颜色/描边/阴影可用。
- 常见字幕格式不崩溃、不误报连接超时。

### [x] 3. DRM 支持策略（当前明确为 MPV 不支持 MediaDrm）

现状：

- `ExoUtil.getMediaItem()` 会设置 `MediaItem.DrmConfiguration`。
- MPV 当前会检查 `MediaItem.localConfiguration.drmConfiguration`。
- 发现 DRM 后不进入 libmpv `loadfile`，直接上报 `MPV_DRM_UNSUPPORTED`。
- libmpv/FFmpeg 不等价于 Android MediaDrm，不能默认认为 Widevine/ClearKey 可以直接播放。

Exo 对应实现：

- `ExoUtil.buildDrmConfig()` 支持 license uri、headers、ClearKey multi-session 等。
- Exo 的 DRM 生命周期由 Media3 管理。

实施方向：

- 先确认项目真实 DRM 使用范围：ClearKey、Widevine、或者资源站自定义 key。
- 对 ClearKey/HLS AES-128，优先看 Exo 的 m3u8/key 处理链路，判断能否在 MPV HLS 代理层完成 key/header 处理。
- 对 Widevine，优先明确为 MPV 暂不支持，避免误导用户。
- 已完成：UI 层对 MPV DRM 资源给出明确错误，不显示成普通连接超时。

验收：

- DRM 资源在 MPV 下不会黑屏超时。
- 不支持时错误信息明确。
- 后续如支持 ClearKey/HLS AES-128，key/header 场景必须有实机验证。

### [x] 4. HLS 代理协议覆盖（代码完成，待实机专项验证）

现状：

- `MpvHlsProxy` 已支持 playlist 重写、nested playlist、`URI="..."` 重写、PNG 前缀 TS 清洗。
- 代理已转发客户端 `Range`，并透传上游 `206` / `Content-Range` / `Accept-Ranges`。
- playlist 和 nested playlist 上游非 2xx 不再伪装成 200 的 m3u8，会按上游状态返回并记录日志。
- 二进制 segment/key 请求已强制 `Accept-Encoding: identity`，避免透明 gzip 破坏 Range 和 Content-Length 语义。
- `URI="..."` 属性统一重写，覆盖 `#EXT-X-KEY` 和 `#EXT-X-MAP` 的 key/init segment。
- `#EXT-X-BYTERANGE` 保留原 tag，MPV 对代理 URL 发起 Range 时由代理透传并返回 206；缓存命中也支持 Range/Content-Range。
- HLS proxy 已记录 playlist 类型、请求数、最近 HTTP 状态，供 OSD/错误恢复使用。
- 待实机专项验证：fMP4、AES key、多级 master playlist、live playlist 刷新、gzip、cookie、302 后域名 header 策略。

Exo 对应实现：

- `MediaSourceFactory` 使用 `OkHttpDataSource`，playlist/key/segment 共用 headers。
- Media3 HLS/Extractor 处理 byte range、init segment、fMP4、TS sync、错误重试等细节。

实施方向：

- 逐项对照 Media3 HLS 能力，给 `MpvHlsProxy` 增加协议用例。
- 从 `IHTTPSession` 读取 `Range`，转发给真实分片请求，并把上游 206/Content-Range/Accept-Ranges 透传给 MPV。
- playlist/nested playlist 非 2xx 必须保留错误状态，不能把错误 HTML 改写成成功 m3u8。
- segment/key 请求尽量使用 identity encoding，避免本地代理返回长度和 Range 不一致。
- 已处理 `#EXT-X-BYTERANGE` 下同一 URI 多 range 场景：Range 透传，缓存命中时本地返回 206。
- 已处理 `#EXT-X-MAP URI="..."` 重写，保证 fMP4 init segment 走代理。
- key 请求必须继承 header，且错误时日志能区分 playlist/key/segment。
- 继续保留 PNG 前缀 TS 清洗，但把它作为输入修复的一种策略，不扩大到所有二进制。

验收：

- TS HLS、fMP4 HLS、master playlist、nested playlist 均可播放。
- byte range 资源不因代理返回全文件而异常。
- key 请求失败能定位到 key URL 和 HTTP 状态。

### [ ] 5. libmpv 失败原因上报（Java 层分类已补，native 源码阻塞）

现状：

- Java 只收到 `MPV_EVENT_END_FILE` 的 event id。
- 没有拿到 `mpv_event_end_file.reason` 和 `error`。
- 当前仓库只有 `MPVLib.java` 和打包好的 `libplayer.so`，没有 JNI/C 源码，Java 层无法直接读取 native event payload。
- 当前已用 recent mpv logs 和播放属性推断 `Video: png`、`Invalid data`、`no audio or video data played`。
- `MpvPlayer` 已补充结构化诊断日志，失败时输出 uri、HLS、file-loaded、playback-restart、video size、position、duration、tracks、path、file-format、codec、hwdec、vo 等信息。
- MPV 错误前缀已映射到本地化错误文案，避免所有问题都显示为连接超时。

Exo 对应实现：

- `PlaybackException.errorCode` 更细。
- `ErrorMsgProvider` 能根据错误码和 `PlaybackAnalyticsListener.Snapshot` 给出解码器/格式原因。

实施方向：

- 已完成：Java 层建立 MPV error -> Media3 `PlaybackException` 前缀和 UI 文案映射。
- 已完成：区分加载失败、HLS 输入失败、没有音视频、异常图片/容器、解码失败、视频输出失败。
- 阻塞项：补入 native JNI 源码或明确 native 库来源，把 `MPV_EVENT_END_FILE.reason/error` 传到 Java。
- 待完成：有 native reason/error 后，区分用户 stop、正常 EOF、network error、demuxer error、decode error，减少日志推断占比。

验收：

- 用户看到的 MPV 错误不再全是连接超时。
- 停止/切集不会误报失败。
- 格式错误、网络错误、解码错误可区分。
- native reason/error 接入后，日志推断只作为兜底。

### [x] 6. 切换媒体生命周期继续固化（压测清单已固化，待实机执行）

现状：

- 当前通过“每次新媒体 reset libmpv context”解决切换黑屏/超时。
- 这是稳定优先方案，但成本是切集时初始化较重。

Exo 对应实现：

- Exo 每次 `setMediaItem()` + `prepare()` 创建新的 MediaSource/Loader 生命周期。
- 旧 loader 会被 release/stop 隔离，不污染新媒体。

实施方向：

- 当前保持 reset context 策略，不要为了优化切换速度提前复用 context。
- 后续如果要复用 context，必须先补 native event reason、请求取消、session 生命周期和压力测试。
- 保留旧 HLS session TTL，避免新媒体开始时旧分片立刻 404 干扰。
- 已固化回归方法：每次 MPV 生命周期/输入层改动后必须按“首播、同视频切集、不同视频切换、快速连续切换、退出重进、错误资源”执行实机验证。
- 已固化日志判据：每个新媒体必须有新的 `context reset for new media`、`load uri=...`、`event=start-file`，随后出现 playlist/item 请求和 `event=file-loaded` / `event=playback-restart` 或明确 MPV 错误前缀。

验收：

- 同视频切集、不同视频切换、快速连续切换都不会黑屏超时。
- 日志里每次新媒体都有明确 `start-file`、playlist 请求、`file-loaded` 或明确错误。
- 失败样本必须记录 URL 类型、是否 HLS、是否走 proxy、最后一个 MPV 错误前缀、recent log、是否有旧 session item 404。

## P1 待补齐

### [x] 7. 缓存和预加载能力（代码完成，待实机验证）

现状：

- Exo 有 `PreCache`，会根据当前位置、seek、预加载设置提前缓存当前媒体后续片段。
- MPV 已映射 mpv 自身 demuxer/cache：
  - `cache=yes`
  - `cache-secs`
  - `cache-pause=yes`
  - `cache-pause-initial=no`
  - `cache-pause-wait`
  - `demuxer-max-bytes`
  - `demuxer-max-back-bytes`
  - `demuxer-readahead-secs`
  - `stream-buffer-size`
- MPV HLS 代理实现了独立轻量分片缓存，不复用 Exo `SimpleCache`，避免 Media3 cache key/metadata 与本地 HLS proxy 混用。
- HLS 缓存按 `url + headers` 生成 key，保存 MIME 元信息，支持 Range 命中、LRU 修剪和播放/预载容量限制。
- VOD HLS playlist 会记录 `#EXTINF` 时间轴，初始预载片头，seek 后按新位置预载后续分片。

Exo 对应实现：

- `PreCache` 使用 `PreCacheHelper`、`MediaSourceFactory.getCache()`、`OkHttpDataSource`。
- 支持 seek 后重新预缓存。

实施方向：

- 先判断 MPV 是否需要共用 Exo Cache，还是在 HLS proxy 层实现轻量分片缓存。
- 不启用 `demuxer-cache-wait=yes` 作为默认策略，避免开播前长时间黑屏等待。
- 已选择 HLS proxy 层轻量缓存；headers、Range、key、session 通过 cache key 和代理 session 隔离。
- 对 VOD HLS 预载，直播流不盲目缓存和预载。

验收：

- 开启预加载后 MPV 的缓冲表现可观测改善。
- 缓存错误不会影响播放。
- seek 后缓存从新位置开始。

### [ ] 8. 硬解/软解和解码 fallback（视频自动软解已禁用，手动软解保留）

现状：

- MPV 通过 `hwdec=mediacodec,mediacodec-copy` 或 `no` 控制硬解/软解。
- hard 模式已显式设置 `hwdec-software-fallback=no`，禁止 libmpv 在视频硬解失败后自动切软解。
- soft 模式仍由用户手动切换，设置 `hwdec=no`。
- Exo 有硬解、软解、FFmpeg renderer、decoder fallback、硬解失败重试等机制。
- MPV 视频失败时当前基本 fatal，不自动切 Exo，也不自动切软解。
- 音频走 MPV/FFmpeg 解码链路；直通只对 AC3/EAC3/DTS/TrueHD 等编码启用，AAC 优先是选轨偏好。

Exo 对应实现：

- `ExoUtil.buildRenderersFactory()` 控制系统 codec、FFmpeg audio/video renderer、fallback。
- `ExoPlayerEngine.handleError()` 对 decoder init/query/decoding failed 返回 `DECODE`。
- `PlayerManager.retryHardDecodeSwitch()` 有硬解切换重试流程。

实施方向：

- 已增加 MPV 当前 decoder/hwdec 诊断属性采集：`hwdec-current`、codec、VO、cache、丢帧等。
- 维持策略：视频硬解失败不自动软解，只允许用户手动切软解。
- 如未来要做受控软解重试，必须先做 UI/配置确认，不得默认开启。
- 4K/高帧率资源要记录帧率、解码器、drop frame、cache 状态。

验收：

- MPV 硬解失败能显示原因。
- 可手动切软解并继续用 MPV。
- 不出现 MPV 失败自动切 Exo。

### [x] 9. 章节/标题选择（MPV chapter-list 已映射）

现状：

- ExoPlayerEngine 暴露 `getCurrentMediaEditions()` 和 `selectEdition()`。
- MPV 已读取 `chapter-list` / `chapter-list/count`，映射为 Media3 `MediaEdition`。
- MPV `selectEdition()` 写入 `chapter` 属性跳转章节。
- 现有“标题”按钮可以在 MPV 有章节时显示和选择。

Exo 对应实现：

- `PlayerManager.setTitle()` 优先 `engine.selectEdition(edition)`，失败才改 URL fragment 并重新播放。

实施方向：

- 已实施：用 MPV chapters 兼容现有 MediaEdition UI。
- 最佳实践判断：章节和多版本 Edition 语义不同。当前实现优先复用现有 UI，长期如果资源同时存在多版本和章节，应新增 Chapter 模型或 UI 分组。
- 待实机验证：MKV/MP4 内嵌章节、无标题章节、播放中章节自动更新选中态。

验收：

- 支持的资源可以切换章节。
- 不支持时 UI 不误导。

### [x] 10. 拼接源支持（代码完成，待实机验证）

现状：

- Exo `MediaSourceFactory.isConcatenatingUrl()` 支持 `url|||duration***url|||duration` 拼接。
- MPV 已检测 Exo 同款 `url|||duration***url|||duration`，转换为 MPV inline EDL。
- EDL 使用 `%<byte_length>%<url>` 传递文件名，避免 URL 中逗号、分号等字符破坏 EDL 语法。

Exo 对应实现：

- `createConcatenatingMediaSource()` 使用 `ConcatenatingMediaSource2`。

实施方向：

- 已实施：用 MPV EDL 对齐 Exo `ConcatenatingMediaSource2` 的基础拼接播放。
- duration 按 Exo 的 durationUs 转换为 EDL `length` 秒。
- 待实机验证：拼接源 seek、进度、跨段播放。

验收：

- 拼接源不会被 MPV 当成坏 URL 播放。
- 支持后 seek 和进度显示正确。

### [x] 11. 直播错误恢复（代码完成，待实机验证）

现状：

- Exo 对 `ERROR_CODE_BEHIND_LIVE_WINDOW` 会 `seekToDefaultPosition()` 并恢复。
- MPV 已通过 HLS proxy 记录 playlist 是否 VOD、最近 HTTP 状态和请求数。
- 非 VOD HLS 且最近发生 HTTP 错误、加载失败或 HLS 无音视频时，`MpvPlayerEngine` 最多两次在 MPV 内核内重新加载当前源。

Exo 对应实现：

- `ExoPlayerEngine.handleError()` 对 behind live window 返回 `RECOVERED`。

实施方向：

- 已通过 MPV 日志和 HLS proxy 状态识别直播窗口过期、404/410 segment、playlist 滞后。
- 已实现同内核重新加载当前 HLS，等价于回到 live edge。
- VOD playlist 一旦看到 `#EXT-X-ENDLIST`，不会触发直播恢复；VOD 404 仍明确报错。

验收：

- 直播长时间播放或网络抖动后能恢复。
- VOD 404 仍明确报错。

### [x] 12. 诊断面板数据（基础运行态已补）

现状：

- Exo 有 `PlaybackAnalyticsListener`，OSD 可以显示格式、decoder、错误、buffer 等信息。
- MPV 已补 `getVideoFormat()`，从当前视频轨道提取 Format，兜底取第一条视频轨。
- MPV 已补 `getRuntimeDiagnostics()`，OSD 和当前媒体报告能显示格式、codec、硬解、VO、cache、丢帧、章节。
- MPV HLS proxy 状态已进入诊断：session、item 数、缓存容量、预载任务、playlist live/vod、playlist/item 请求数、最近 HTTP 状态。

Exo 对应实现：

- `PlaybackAnalyticsListener.Snapshot`
- `PlayerOsdController.getDiagnostics()`
- `CodecCapabilityInspector`

实施方向：

- 已完成：MPV 增加基础运行态诊断：demuxer/file format、video/audio codec、hwdec、VO、cache duration、drop frame、chapter count。
- 已完成：`MpvPlayerEngine.getVideoFormat()` 返回可用视频 Format。
- 已增强：proxy session、HLS playlist/key/segment 状态。
- 待增强：native END_FILE reason/error、current-tracks 更细属性。

验收：

- 黑屏时 OSD/日志能看出卡在 playlist、segment、demuxer、decoder 还是 surface。
- 用户反馈问题时能凭日志定位方向。

## P2 待补齐

### [ ] 13. MPV shader / LUT / 视频效果路线

现状：

- Exo 支持 Media3 `setVideoEffects()`，项目 LUT 管线依赖它。
- MPV 不支持 Media3 video effects，当前 `supportsVideoEffects()` 为 false。

Exo 对应实现：

- `ExoPlayerEngine.supportsVideoEffects()` 返回 true。
- `PlayerManager` 的 LUT 预热、应用、失败恢复都围绕 Exo effects。

实施方向：

- 不要硬把 Media3 `Effect` 塞进 MPV。
- MPV 应走自己的 shader/filter 路线：`glsl-shader`、`vf`、`profile`、mpv config。
- 先设计 MPV 专用画质面板，和 Exo LUT 管线分开。

验收：

- MPV 不会误触发 Exo LUT 管线。
- 未来 shader 开关不影响基本播放。

### [x] 14. 音频输出和直通基础映射（代码完成，系统事件待实机）

现状：

- MPV 设置了 `ao=audiotrack,opensles`、`audio-set-media-role=yes`。
- 播放参数面板的“音频直通”已映射到 `audio-spdif=ac3,eac3,dts,dts-hd,truehd` 或 `no`。
- 播放参数面板的“AAC 优先”已映射为 MPV 音轨选择偏好：无手动 override 时优先选择 AAC/mp4a 音轨。
- Exo 有 `AudioAttributes`、`handleAudioBecomingNoisy`、音频直通相关 `DefaultAudioSink` 设置。

Exo 对应实现：

- `player.setAudioAttributes(AudioAttributes.DEFAULT, true)`
- `player.setHandleAudioBecomingNoisy(true)`
- `ExoUtil.buildAudioSink()` 根据 `PlayerSetting.isAudioPassThrough()` 设置输出。

实施方向：

- 已梳理并采用 mpv Android 推荐链路：`audiotrack` 优先，`opensles` 兜底，直通只启用 AC3/EAC3/DTS/TrueHD 等，不对 AAC 做直通。
- 对耳机拔出、蓝牙切换、系统音频焦点做实机测试。
- OSD 记录音频 codec、音轨信息和直通开关；更细的 Android audio focus/noisy 行为待实机确认。

验收：

- 常见音频格式可正常输出。
- 音频延迟可调整。
- 系统音频事件不导致卡死。

### [ ] 15. 用户可配置 mpv options/profile

现状：

- `MpvPlayerConfig` 支持 `extraOptions`，但没有 UI 配置。
- 固定 options 分散在 `MpvPlayer.applyPreInitOptions()`。
- 播放参数面板已有的通用设置已接入 MPV：缓冲时间、缓冲容量、回退缓冲、播放缓存、预载、预载线程/容量/时间、音频直通、AAC 优先、手动软硬解。
- 还没有面向用户暴露任意 MPV profile/options，这是 MPV 专项高级 UI，不属于 Exo 通用按钮 parity。

Exo 对应实现：

- Exo 有播放性能设置、缓冲设置、解码偏好等 UI。

实施方向：

- 建立 MPV option 白名单，不允许用户任意破坏 `vo`、`gpu-context`、`idle`、`config-dir` 等关键项。
- 可先支持调试级别、`hls-bitrate`、profile、shader 目录。
- 所有可配置项都要写入 libmpv 开发文档。

验收：

- 用户调整 MPV 参数后能回退默认值。
- 错误参数不会导致播放器不可恢复。

### [ ] 16. 截图、缩略图、章节、脚本生态

现状：

- `MPVLib` 暴露了 `grabThumbnail(int dimension)`，但上层未接入。
- MPV 章节已接入现有“标题”按钮。
- MPV 截图/缩略图没有现有播放器通用按钮或上层业务调用点，仍需单独设计 UI/调用入口。
- MPV 脚本能力未接。

实施方向：

- 章节已完成；截图/缩略图需要先确定入口和保存/展示策略。
- 脚本能力要谨慎，涉及文件权限、网络、性能和用户配置。
- Android TV 端避免把复杂脚本 UI 混入基础播放链路。

验收：

- 截图/缩略图不会阻塞播放线程。
- 脚本能力默认关闭，有明确隔离。

## 实施方法论

### 1. 先对照 Exo 成熟实现

每个 MPV 问题先找 Exo 对应路径：

- 播放启动：`ExoPlayerEngine.start()`、`ExoUtil.getMediaItem()`
- 网络输入：`MediaSourceFactory`
- 轨道选择：`TrackUtil`
- 缓存：`PreCache`
- 错误处理：`ExoPlayerEngine.handleError()`、`ErrorMsgProvider`
- 性能策略：`ExoUtil.buildPlayer()`、`PlaybackPerformanceSetting`
- 诊断：`PlaybackAnalyticsListener`、`PlayerOsdController`

不要直接猜 MPV 参数。先确认 Exo 为什么能播、在哪一层容错，然后决定 MPV 是用 option、proxy、JNI、还是上层生命周期来补齐。

### 2. 再查 mpv 官方文档和开源实现

固定参考：

- mpv manual options：`https://mpv.io/manual/master/#options`
- mpv input commands：`https://mpv.io/manual/master/#list-of-input-commands`
- mpv properties：`https://mpv.io/manual/master/#properties`
- libmpv client API：`https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h`
- mpv examples：`https://github.com/mpv-player/mpv-examples/tree/master/libmpv`

本地网络访问 GitHub 慢时先走代理：

```bash
export https_proxy=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
export all_proxy=socks5://127.0.0.1:7897
```

或者单条命令使用 `curl -x http://127.0.0.1:7897 ...`。

开源实现搜索关键词：

- `android libmpv track-list aid sid`
- `android mpv sub-add subtitle delay`
- `mpv android hls headers http-header-fields`
- `mpv android attachSurface lifecycle`
- `mpv android mediacodec hwdec`
- `mpv android local proxy hls`

已知可参考项目：

- `mpv-android`
- `FongMi/mpv-android`
- `mpv-android-anime4k`
- `OmniPlay`
- `MediaWarp`
- `mPlayer`
- `William-Player`

参考开源代码时只借鉴成熟做法，不能无差别复制。每个做法都要落到本项目的 Media3 Player 接口、PlayerManager 生命周期和 TV UI 约束里。

### 3. 问题必须回写踩坑文档

凡是出现以下情况，都要补充到 `plans/安卓MPV播放器集成实现与踩坑记录.md`：

- 黑屏、连接超时、切集失败。
- Exo 能播但 MPV 不能播。
- MPV option 看似正确但实际无效。
- JNI/libmpv API 使用顺序错误。
- HLS proxy 新增了特殊兼容逻辑。
- 某个开源项目的做法被验证有效或无效。

记录格式必须包含：

- 现象。
- 日志特征。
- 根因。
- Exo 对应做法。
- MPV 处理方案。
- 验证资源类型和测试结果。

### 4. 每次修改后的固定流程

1. 修改前确认工作区：`git status --short --untracked-files=all`。
2. 修改时保持范围小，不夹带无关重构。
3. 涉及播放逻辑时至少构建：`bash gradlew :app:assembleMobileArm64_v8aDebug --no-daemon`。
4. 需要实机验证时安装并监听：`adb install -r ...`，`adb logcat`。
5. 更新相关 plans 文档。
6. 提交。
7. 打 tag，tag 必须指向 commit。

tag 命名建议：

```bash
mpv-gap-doc-YYYYMMDD-HHMMSS
mpv-fix-功能名-YYYYMMDD-HHMMSS
```

## 最近下一步建议

优先顺序：

1. 本批次实机回归：轨道、字幕样式、字幕/音频延迟、重复播放、章节、播放参数面板、OSD 诊断、拼接源、HLS fMP4/AES/BYTERANGE、VOD seek 后预载、直播重载。
2. P0-5：补 native END_FILE reason/error。当前缺 `libplayer.so` JNI/C 源码，Java 侧受阻，只能先靠日志/属性推断。
3. 遇到 Exo 能播但 MPV 不能播的新样本：先看 Exo `MediaSourceFactory`/`PreCache`/错误处理，再查 mpv 文档和开源实现，最后回写踩坑文档。
4. MPV 专项 UI：双字幕、截图/缩略图、profile/options、shader。LUT/shader 用户已决定单独排期，不混入当前通用按钮补齐。

理由：

当前播放器通用按钮和输入层代码已补，下一步风险主要在真实资源兼容性；native reason/error 不补源码无法彻底闭环；MPV 专项能力需要新 UI/存储设计，不应伪装成 Exo 通用能力。
