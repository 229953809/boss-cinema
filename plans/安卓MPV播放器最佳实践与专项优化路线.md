# 安卓 MPV 播放器最佳实践与专项优化路线

记录时间：2026-07-07  
分支：`feature/android-mpv-player`  
关联文档：

- `plans/安卓MPV播放器集成实现与踩坑记录.md`
- `plans/安卓MPV相对Exo功能缺口TODO.md`
- `plans/安卓libmpv二次开发指南.md`
- `plans/mpv播放器项目仓库参考.md`

## 文档目标

这份文档记录网上 MPV/libmpv Android 集成项目、issues、官方文档和本项目现状的综合判断，用来指导后续两类工作：

1. 先补齐当前 MPV 播放器已有功能缺失，尤其是基础播放、切集、轨道、字幕、错误、HLS 输入层和诊断。
2. 再做 MPV 特有能力专项集成，包括双字幕、字幕样式、shader/Anime4K/HDR、Lua 脚本、stats、截图、逐帧、章节等。

核心原则：

- MPV 是基于 `libmpv` 的内置播放器，不是调用外部 App。
- MPV 失败后不允许自动切 Exo，只能用户手动切换播放器。
- Exo 是成熟工程参照，尤其适合参考输入层、生命周期、错误处理和 UI 契约；但 Exo 的能力模型不一定是 MPV 的最佳实现。
- 遇到问题时先看 Exo 如何稳定处理同类场景，再查 mpv 官方文档、mpv-android、mpvRex、mPlayer、MediaKit、Streamyfin/Findroid/Jellyfin 相关讨论，最后更新踩坑文档。

## 研究来源

### 官方资料

- mpv `client.h`：确认 `mpv_create`、`mpv_initialize`、`mpv_wait_event`、`mpv_event_end_file.reason/error`、command/property/option 的边界。
- mpv `input.rst`：确认 `loadfile` 参数、`sub-add`、`script-message`、`script-binding`、`frame-step`、`screenshot-to-file`、`change-list` 等命令。
- mpv `options.rst`：确认 `track-list`、`aid`、`sid`、`secondary-sid`、`sub-delay`、`audio-delay`、`glsl-shaders`、`http-header-fields`、`referrer`、`hls-bitrate`、cache、hwdec 等选项/属性。

### 重点开源项目

- `mpv-android/mpv-android`：官方 Android libmpv 播放器，是 Surface 生命周期、初始化 options、轨道读取、双字幕、章节/playlist、stats/hwdec 观测的第一参考。
- `FongMi/mpv-android`：与官方 mpv-android 同源，适合确认 FongMi 生态里直接可复用的用法。
- `sfsakhawat999/mpvRex`：现代 Android libmpv 播放器，参考价值集中在智能轨道选择、node 映射、网络流 Range 代理、Anime4K/HDR shader、Lua 自定义按钮、截图逐帧、字幕样式。
- `busydoggo/mPlayer`：mpvRex 类似分支，参考价值集中在字幕面板、延迟、zoom/pan、shader UI、逐帧截图。
- `sbenmeddour/libmpv-android`：可参考独立 AAR/JNI 封装，尤其是 `END_FILE reason/error` 结构化事件和 Kotlin track model。
- `MakD/AFinity`：LibMPV + ExoPlayer 的 Jellyfin 客户端，可参考 `track-list` 到 Media3 `Tracks` 的映射、`aid/sid/vid` 选择和多引擎契约。
- `raulshma/jellyplay`：可参考多播放引擎边界、服务端媒体流索引和播放器轨道索引不一致时的匹配，以及 Jellyfin device profile。
- `lkcv/jellyfin-android-with-mpv`：官方 Jellyfin Android fork，可参考 MPV 实现 Media3 `Player` 接口并接入原 Exo UI 的最小改造路径；但它绕过标准 Media3 TrackSelection 的做法不能照搬。
- `kernoeb/telegramedia`：可参考本地 HTTP server 给 mpv 播放时的 `Range`、`206`、`Content-Range` 和渐进下载处理。
- `Laskco/mpvNova`：Android TV-first mpv-android fork，可参考 D-pad 体验、decoder 诊断、双字幕、字幕记忆和 `hwdec-current` 展示。
- `XBigTK13X/expo-libmpv`：React/Expo 场景不是主参考，但其 `LibmpvSession` 延迟应用 seek/音轨/字幕选择的思路可借鉴。
- `azxcvn/mpv-android-anime4k`：适合参考 `http-header-fields`、`hls-bitrate=max`、Anime4K shader 资产组织。
- `dcs-studio/mpv-build-android`、`FortunasXP/mpv-android-libdovi`：适合后续 native 构建、codec 包、Dolby Vision/HDR 专项研究。
- `media-kit/media-kit`：跨平台 libmpv 封装，参考价值在缓存、平台后端差异、Android TV 硬解问题讨论。
- `Streamyfin/Findroid/Jellyfin` 相关 PR/issues：参考价值在服务端媒体轨道与 MPV 真实 `track-list` 不一致时，必须按身份匹配而不是按位置匹配。

GitHub 仓库筛选结论见 `plans/mpv播放器项目仓库参考.md`。该清单已按主参考、专题参考、不再作为参考三类删减；后续遇到 MPV 问题时先按功能缺口查对应项目，不只按 star 数判断。

### issue/帖子类结论

- mpv-android Surface 黑屏类 issue：多个 Surface/TextureView 反复 attach/detach 容易让 libmpv 输出消失；实际集成要保持同一时刻只有一个有效 Surface，并在销毁时显式 `vo=null`、`force-window=no`、`detachSurface`。
- mpv-android hwdec 类 issue：Android TV/Fire OS/部分 SoC 的 `mediacodec` 或 `mediacodec-copy` 存在设备差异，表现包括黑屏、绿线、绿帧、10-bit/AV1/H.264 Hi10p 异常；必须暴露硬解/软解诊断和手动切换，不能把所有黑屏归咎于网络。
- HLS headers 类 issue：libmpv 展开 playlist 后的 segment/key 请求必须继承 headers；若 headers 无法稳定继承，需要本地代理或把 token 放入 segment URL。
- subtitle/track 类 issue：外部系统或服务端轨道顺序经常与 MPV `sid/aid` 不一致，不能按列表位置映射；必须以 MPV `track-list` 为播放侧真相，结合 lang/title/external/external-filename/codec 做身份匹配。

## 总体判断

MPV 的核心能力强于 Exo，尤其在容器、字幕、滤镜、shader、Lua、双字幕、逐帧、截图、复杂格式上更强。但 Android App 集成时，MPV 的短板通常不是解码能力，而是工程接入层：

- libmpv 的异步生命周期没有 Media3/Exo 那么强的 Java 层隔离。
- Android Surface/VO 需要 App 自己维护。
- HLS playlist/key/segment headers、Range、异常分片清洗需要 App 自己兜底。
- JNI 当前只暴露了事件 id，没有暴露 `END_FILE` 的 reason/error。
- Media3 UI 期望的 Tracks、text offset、error code、metadata 需要自己映射。

因此后续路线必须是：

1. 先把 MPV 的输入层和生命周期做稳。
2. 再把 Media3/项目 UI 契约补齐。
3. 最后发挥 MPV 原生能力，而不是复制 Exo 的内部模型。

## Exo 参照边界

| 能力 | Exo 是否可作为主要参照 | MPV 最佳实践判断 |
| --- | --- | --- |
| 自动播放器 fallback | 否 | 禁止。MPV 失败只能报错，用户手动切换。 |
| HLS headers | 是 | 参考 Exo `OkHttpDataSource` 统一 headers；MPV 侧用 `user-agent`、`referrer`、`http-header-fields` 或本地代理保证 playlist/key/segment 一致。 |
| HLS parser/extractor 容错 | 是 | Exo 的 TS sync、byte range、fMP4/key 处理值得对照；MPV 侧应在代理层补齐，而不是只调 demuxer 参数。 |
| 切媒体生命周期 | 是 | Exo 每个 media item 有隔离 loader；MPV 当前 reset context 是合理保守策略。 |
| Track UI 契约 | 是 | UI 仍要看到轨道，但底层不照搬 `TrackSelectionOverride`，应映射到 `aid`、`vid`、`sid`。 |
| 字幕渲染 | 部分 | Exo `SubtitleView` 不适用；MPV 用 libass/OSD，样式用 `sub-*`/`secondary-sub-*` 属性。 |
| DRM | 部分 | Widevine/MediaDrm 是 Exo 强项；MPV 不应假装等价支持。ClearKey/AES-128 要按输入层逐项验证。 |
| Cache/PreCache | 部分 | Exo `SimpleCache` 不能直接套；MPV 可用 demuxer cache、本地 HLS proxy 分片缓存或独立预取。 |
| LUT/Video effects | 否 | MPV 应走 `glsl-shaders`/`vf`/`profile`，不是 Media3 video effects。 |
| 错误码 | 是 | UI 需要等价清晰错误；MPV 应从 `mpv_event_end_file`、log、属性映射到项目错误。 |

## Android libmpv 集成最佳实践

### 1. 初始化与生命周期

最佳实践：

- `mpv_create()` 后、`mpv_initialize()` 前设置初始化敏感 options。
- `mpv_initialize()` 后再通过 properties/commands 控制播放。
- `loadfile` 只是入队/替换播放列表，不代表新媒体已经开始加载，更不代表能播放。
- `stop` 和 `loadfile` 都是异步语义，快速切集时不能假设旧 demuxer 已完全退出。
- 新媒体切换在当前阶段继续采用 reset libmpv context，优先保证稳定。
- 每次 `destroy()` 前移除 observer/log observer，避免旧实例事件污染新实例。

本项目现状：

- `MpvPlayer` 已经每个新 media reset context，这是正确方向。
- 后续不要为了切集速度提前复用 context。只有在 `END_FILE reason/error`、请求取消、HLS session 隔离、压力测试都补齐后，才考虑 context 复用优化。

### 2. Surface/VO

最佳实践：

- `surfaceCreated` 后 `attachSurface`，再设置 `force-window=yes`，必要时恢复 `vo=gpu`。
- `surfaceDestroyed` 时先 `vo=null`、`force-window=no`，再 `detachSurface`。
- 同一时刻只允许一个有效 Surface 交给 mpv。
- ViewPager/多 TextureView/快速切页面这类场景容易造成黑屏，必须让 UI 层避免多个 Surface 同时 attach。
- Surface 相关黑屏要看日志里是否出现 `VO: [gpu] ...`、`playback-restart`、视频尺寸。如果已经有 VO 和尺寸，优先排查输入层/解码层。

本项目现状：

- `MpvPlayer` 已实现 attach/detach 和 `vo=null`。
- 需要继续增加 Surface/VO 诊断：记录 `vo-configured`、`hwdec-current`、`video-params`、`current-tracks/video/image`。

### 3. 网络 headers 与 HLS

最佳实践：

- 普通 HTTP URL 可通过 `user-agent`、`referrer`、`http-header-fields` 传 headers。
- HLS 不能只保证 master playlist 有 headers，key、nested playlist、segment 必须继承同一套 headers。
- 如果源站依赖复杂 headers、cookie、token、Range、302 后域名策略，本地代理比单纯设置 mpv option 更可控。
- 本地代理必须支持：
  - nested playlist 重写。
  - `#EXT-X-KEY URI="..."` 重写。
  - `#EXT-X-MAP URI="..."` 重写。
  - `#EXT-X-BYTERANGE` 与 HTTP `Range`。
  - 上游 `206`、`Content-Range`、`Accept-Ranges`、`Content-Length` 透传。
  - gzip/deflate、302 后最终 URL、cookie、Referer/Origin。
  - key/playlist/segment 分类型日志。
- 对异常分片要在代理层做输入修复。当前 PNG 前缀 TS 清洗属于正确方向，原因是 Exo TS extractor 会寻找 sync byte，而 FFmpeg demuxer 会被 PNG signature 误导。

本项目现状：

- 已有 `MpvHlsProxy`、headers 补齐、PNG 前缀 TS 清洗、HTTP `Range` 转发、上游 `206` / `Content-Range` / `Accept-Ranges` 透传、二进制请求 `Accept-Encoding: identity`。
- 缺失 fMP4、`#EXT-X-MAP`、`#EXT-X-BYTERANGE`、AES key、live playlist 刷新系统验证，是下一步 P0。

### 4. 轨道发现与选择

最佳实践：

- 以 MPV `track-list` 为播放侧真相。
- 基础轨道不需要等待 JNI node/list，可先逐项读子属性：
  - `track-list/count`
  - `track-list/N/id`
  - `track-list/N/type`
  - `track-list/N/lang`
  - `track-list/N/title`
  - `track-list/N/codec`
  - `track-list/N/default`
  - `track-list/N/forced`
  - `track-list/N/external`
  - `track-list/N/image`
  - `track-list/N/selected`
- 轨道选择映射：
  - 视频：`vid`
  - 音频：`aid`
  - 主字幕：`sid`
  - 第二字幕：`secondary-sid`
  - 关闭轨道：设置字符串 `"no"`
- 不能按 UI 列表位置映射 `sid/aid`。服务端、文件容器、外挂字幕、隐藏嵌入字幕会造成顺序错位。
- 外挂字幕应在 `sub-add` 后重新读取 `track-list`，并按 `external`、`external-filename`、lang/title/codec 匹配。
- 自动选轨可以参考 mpvRex：优先用户历史选择，其次按偏好语言，再排除 commentary/description/ADH、forced/SDH/signs/songs 等非主轨。

本项目现状：

- `MpvPlayerEngine.setTrack()`、`resetTrack()`、`haveTrack()`、`getCurrentTracks()` 已复用 Exo 同款 `TrackUtil`。
- `MpvPlayer` 已将 MPV `track-list` / `track-list/count` 映射为 Media3 `Tracks`，选择时写入 `vid`、`aid`、`sid`。
- AAC 优先已做成“无手动 override 时优先选择 AAC 音轨”，不会覆盖用户手动选轨。
- 后续重点是实机验证轨道弹窗、历史轨道恢复、外挂字幕加入后的 track-list 刷新，以及第二字幕 `secondary-sid`。

### 5. 字幕与音频延迟

最佳实践：

- 字幕延迟：`sub-delay`，单位秒。
- 音频延迟：`audio-delay`，单位秒。
- 第二字幕延迟：`secondary-sub-delay`。
- 字幕样式不要走 Exo `SubtitleView`，应使用 MPV 属性：
  - `sub-scale`
  - `sub-pos`
  - `sub-ass-override`
  - `sub-scale-by-window`
  - `sub-use-margins`
  - `secondary-sub-scale`
  - `secondary-sub-pos`
  - `secondary-sub-ass-override`
- SRT/VTT/ASS/SSA/PGS/VOBSUB 的选择、渲染、样式能力不同，验收时必须分格式验证。

本项目现状：

- `sub-add` 已有，外挂字幕加入后会刷新轨道。
- Media3 text/audio offset 已映射到 MPV `sub-delay` / `audio-delay`。
- 现有字幕面板已可控制 MPV 原生字幕大小/位置，映射到 `sub-scale` / `sub-pos`。
- 后续重点是系统 caption style、ASS override、字体/描边/边距、第二字幕样式，以及 SRT/VTT/ASS/SSA/PGS/VOBSUB 分格式验收。

### 6. 错误事件与诊断

最佳实践：

- 不要只靠“15 秒连接超时”判断 MPV 失败。
- `MPV_EVENT_FILE_LOADED` 不等于播放成功；要结合 `PLAYBACK_RESTART`、视频尺寸、音视频 track、日志和 `END_FILE` reason/error。
- JNI 必须暴露 `mpv_event_end_file.reason` 和 `mpv_event_end_file.error`，否则无法区分正常 EOF、stop、network error、demuxer error、decode error。
- 诊断属性至少包括：
  - `hwdec-current`
  - `video-codec`
  - `audio-codec`
  - `container-fps`
  - `estimated-vf-fps`
  - `frame-drop-count`
  - `vo-drop-frame-count`
  - `demuxer-cache-duration`
  - `demuxer-cache-state`
  - `track-list`
  - `current-tracks/...`
- 用户错误文案要区分网络、格式、没有音视频、解码失败、Surface/VO 初始化失败、DRM 不支持。

本项目现状：

- 当前 JNI 只上报 event id。
- 当前 Java 通过 recent logs 推断部分失败，这是过渡方案，不能长期依赖。
- 仓库里没有 native 源码，只有 `libplayer.so` 资产。后续要么补入 JNI 源码和构建脚本，要么明确 native 库来源，否则 `END_FILE reason/error` 无法正规扩展。

### 7. 硬解/软解与性能

最佳实践：

- 默认可用 `hwdec=mediacodec,mediacodec-copy`，但必须允许用户手动切软解 `hwdec=no`。
- Android TV/盒子 SoC 差异很大，硬解异常不能用自动切 Exo 掩盖。
- 需要显示/记录 `hwdec-current`，并将当前 decoder、codec、分辨率、帧率、丢帧、cache 状态纳入调试日志。
- Vulkan/gpu-next/HDR 相关能力要谨慎。mpv-android Vulkan PR 仍提示 Android Vulkan 依赖和 HW+ 限制，不能作为近期默认方案。

本项目现状：

- 已通过 engine decode 控制 `hwdec`。
- hard 模式已显式设置 `hwdec-software-fallback=no`，视频硬解失败不允许 MPV 内部自动软解。
- soft 模式保留用户手动切换，设置 `hwdec=no`。
- OSD/当前媒体报告已显示基础 MPV 运行态诊断：codec、`hwdec-current`、VO、cache、丢帧、章节。
- 后续如果要做受控软解重试，必须先做 UI/配置确认，不能默认开启。

## MPV 特有能力专项

这些能力不是 Exo parity，而是 MPV 应该发挥的优势。实施顺序应排在基础播放稳定之后。

### 1. 双字幕

能力：

- 主字幕 `sid`。
- 第二字幕 `secondary-sid`。
- 第二字幕延迟 `secondary-sub-delay`。
- 第二字幕位置/缩放/ASS override 可独立配置。

价值：

- 外语学习、动漫、双语字幕是 MPV 强项。

实施建议：

- 先完成基础字幕轨道列表和主字幕选择。
- UI 中增加“主字幕/第二字幕”切换。
- 禁止主字幕和第二字幕选同一轨，关闭项映射 `"no"`。

### 2. 字幕样式

能力：

- `sub-scale`、`sub-pos`、`sub-ass-override`、`sub-scale-by-window`、`sub-use-margins`。
- ASS 字幕保留原样与强制覆盖要可选。

实施建议：

- 先接入字体大小、位置、ASS override。
- 再接入第二字幕样式。
- 明确告知开发者：这套不走 Exo `SubtitleView`。

### 3. Shader/Anime4K/HDR

能力：

- `glsl-shaders` 可加载 shader chain。
- `change-list glsl-shaders append/remove` 可动态增删。
- Anime4K 可按 FAST/BALANCED/HIGH 和 mode A/B/C/A+/B+/C+ 组织。
- HDR tone mapping 可参考 hdr-toys shader chain。

实施建议：

- 第一阶段只做 shader 资产复制和手动开关。
- 第二阶段做预设组合和设备性能提示。
- 第三阶段做按视频元数据自动 profile，但必须可关闭。
- 不要把 Media3 LUT/video effects 直接套到 MPV。

### 4. Lua 脚本与自定义按钮

能力：

- `load-script <file.lua>`。
- `script-message <name>`。
- `script-binding stats/display-*`。

风险：

- 脚本执行能力强，应默认关闭或只允许内置白名单脚本。
- 用户自定义 Lua 要有明显的安全边界。

实施建议：

- 先接 stats 内置脚本切换。
- 再考虑白名单内置脚本。
- 最后才开放用户自定义按钮/Lua。

### 5. Stats/诊断 Overlay

能力：

- `script-binding stats/display-stats-toggle`。
- `script-binding stats/display-page-N`。

价值：

- 实机排查黑屏、卡顿、硬解、cache、丢帧时非常关键。

实施建议：

- 优先作为开发/高级设置开关。
- 和项目 `SpiderDebug` 日志互补，不替代结构化错误上报。

### 6. 逐帧、截图、章节、播放列表

能力：

- `frame-step`、`frame-back-step`。
- `screenshot-to-file <path> subtitles|video`。
- `chapter-list/count`、`chapter-list/N/title`、`chapter-list/N/time`。
- `playlist-pos`、`playlist-count`。

实施建议：

- 逐帧/截图适合高级控制面板。
- 章节列表可映射到现有选集/进度 UI 的增强入口。
- 播放列表能力要谨慎，当前项目主播放列表由业务层控制，不应让 mpv playlist 抢控制权。

### 7. 画面缩放和平移

能力：

- `video-zoom`
- `video-pan-x`
- `video-pan-y`
- `video-params/aspect`
- `video-params/rotate`

实施建议：

- 先接入手势缩放/平移或高级菜单。
- 保存状态时注意只保存用户显式调整，不要把每个视频自动状态都写入历史。

## 当前功能缺失补齐优先级

### P0：立即补齐

1. 轨道发现与选择。
   - 用 `track-list/count` 和子属性读轨道。
   - 映射到项目 UI 所需的 `Tracks`/`Track`。
   - `setTrack()` 设置 `aid`、`sid`、`vid`。
   - 关闭字幕/音轨用 `"no"`。
   - 外挂字幕 `sub-add` 后刷新轨道。

2. 字幕/音频延迟。
   - `Player` commands 增加 text/audio offset 能力。
   - `sub-delay`、`audio-delay` 使用秒，UI 使用毫秒时做单位转换。

3. MPV 结构化诊断。
   - 观察/读取 `hwdec-current`、codec、drop frames、cache、current tracks。
   - 日志中区分没有 START_FILE、没有 playlist 请求、FILE_LOADED 但 0x0、VO 初始化失败、解码失败。

4. HLS proxy Range/fMP4/key 覆盖。
   - 转发客户端 Range。
   - 透传 206/Content-Range/Accept-Ranges。
   - 验证 `#EXT-X-BYTERANGE`、`#EXT-X-MAP`、AES key、多级 playlist、live refresh。
   - key/segment 失败日志必须可定位。

5. `END_FILE` reason/error 上报。
   - 补 JNI 源码或确认 native 库可维护来源。
   - Java 映射到明确 `PlaybackException`。
   - stop/cancel/正常 EOF 不得误报连接超时。

6. DRM 策略。
   - Widevine 先明确不支持或只能 Exo 手动播放。
   - ClearKey/AES-128 按 HLS/key/header 路线验证。
   - MPV 不支持时给明确错误，不显示普通连接超时。

### P1：基础体验增强

1. 字幕样式基础项：大小、位置、ASS override。
2. 第二字幕：`secondary-sid` 和第二字幕样式。
3. 手动软硬解切换和当前解码器展示。
4. HLS 轻量分片缓存或 proxy 级预取，优先 VOD，直播谨慎。
5. 章节列表与媒体标题/metadata。
6. 画面 zoom/pan/aspect。

### P2：MPV 专项优化

1. Anime4K shader 预设。
2. HDR tone mapping shader 预设。
3. stats overlay。
4. 逐帧和截图。
5. Lua 脚本和自定义按钮，默认关闭。
6. profile/高级 mpv.conf 管理。
7. 设备级 hwdec 黑名单/白名单和兼容性数据库。

## 遇到问题时的方法论

### 排查顺序

1. 先看本项目 Exo 成熟实现。
   - headers 怎么传。
   - playlist/key/segment 是否同源 DataSource。
   - 切媒体时旧 loader 如何隔离。
   - 错误如何映射到 UI。

2. 再看 mpv 官方文档。
   - 该能力是 option、property 还是 command。
   - 是否必须 init 前设置。
   - 运行期设置是否可靠。

3. 再看成熟 MPV 项目。
   - 优先 `mpv-android`。
   - 其次 `mpvRex`、`mPlayer`。
   - 再看 MediaKit/Streamyfin/Findroid/Jellyfin issues。

4. 最后改本项目。
   - 先做最小可验证改动。
   - 加日志和可观测性。
   - 真机安装验证。
   - 更新踩坑文档。
   - 提交并打 tag。

### 黑屏分流表

| 现象 | 优先判断 | 排查点 |
| --- | --- | --- |
| 没有 `event=start-file` | loadfile/context 竞态 | reset context、命令是否发出、旧 demuxer 是否卡住 |
| 有 START_FILE 但没 playlist 请求 | HLS proxy/URL/headers | 本地代理是否启动、session 是否过期、URL 是否正确 |
| playlist 有请求，segment/key 失败 | 输入层 | headers、302、Range、key、MIME、gzip、cookie |
| FILE_LOADED 但尺寸 0x0 | demux/输入异常 | `Video: png`、no audio/video、格式识别、异常前缀 |
| 有 VO 和尺寸但黑屏 | Surface/硬解 | `VO: [gpu]`、`hwdec-current`、设备 codec、绿帧/黑帧 |
| 切集后黑屏 | 生命周期 | stop/loadfile 异步、旧 session、surface attach 顺序 |
| 最终连接超时 | 错误映射不足 | 查 `END_FILE reason/error`、recent logs，不能只显示超时 |

### 每次修改必须记录

- 改了哪些 MPV option/property/command。
- 参考了哪个 Exo 实现或 MPV 项目实现。
- 真机验证的视频类型和结果。
- 新增或复现的坑点，写入 `安卓MPV播放器集成实现与踩坑记录.md`。
- 每次代码或文档修改后打 Git tag，便于回滚。

## 验收矩阵

### 基础播放

- MPV 首次播放 HTTP MP4。
- MPV 首次播放普通 TS HLS。
- MPV 首次播放 fMP4 HLS。
- 同视频切集。
- 切到另一个视频。
- 快速连续切集。
- 返回退出再进入。
- MPV 错误后不自动切 Exo。

### 输入层

- headers 包含 User-Agent/Referer/Origin/Cookie。
- master playlist -> variant playlist -> segment 全链路 headers 一致。
- `#EXT-X-KEY` key 请求继承 headers。
- `#EXT-X-MAP` init segment 走代理。
- HTTP Range 请求返回 206。
- `#EXT-X-BYTERANGE` 可 seek。
- 302 到不同域名后仍可播放。
- PNG 前缀 TS 清洗仍有效。

### 轨道与字幕

- 内嵌多音轨可见、可切换。
- 内嵌字幕可见、可切换、可关闭。
- 外挂字幕加载后可见、可选择。
- ASS/SRT/VTT 分别验证。
- `sub-delay` 可调。
- `audio-delay` 可调。
- 主字幕和第二字幕不互相覆盖。

### 解码与诊断

- 硬解播放并显示 `hwdec-current`。
- 手动软解仍留在 MPV。
- 4K/高码率资源记录帧率、丢帧、cache。
- 错误能区分网络、格式、无音视频、解码失败、DRM 不支持。

### MPV 专项

- stats overlay 能打开/关闭。
- shader preset 打开/关闭后画面正常。
- 字幕样式调整立即生效。
- 逐帧前进/后退生效。
- 截图包含/不包含字幕两种模式都可用。

## 近期推荐实施顺序

第一批只做基础缺口，不做大 UI：

1. 在 `MpvPlayer` 内建立 MPV track model，读取 `track-list` 子属性。
2. `MpvPlayerEngine` 实现 `getCurrentTracks()`、`haveTrack()`、`setTrack()`、`resetTrack()`。
3. `MpvPlayer` 支持 `sub-delay`、`audio-delay`，补 Media3 command 映射。
4. `MpvHlsProxy` 补 Range/206/Content-Range。
5. 增加 MPV 诊断日志和 debug dump。

第二批做可见体验：

1. 字幕样式基础 UI。
2. 第二字幕。
3. 解码器诊断和手动软硬解切换增强。
4. HLS fMP4/key/live 测试样例沉淀。

第三批做 MPV 优势能力：

1. stats overlay。
2. Anime4K/HDR shader 预设。
3. 逐帧/截图。
4. Lua 自定义按钮和高级 profile。

## 网络搜索命令提示

网络慢时使用代理：

```bash
export https_proxy=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
export all_proxy=socks5://127.0.0.1:7897
```

或单条命令：

```bash
curl -L -x http://127.0.0.1:7897 'https://api.github.com/search/issues?q=repo:mpv-android/mpv-android%20surface%20black%20screen'
```

推荐搜索关键字：

- `repo:mpv-android/mpv-android surface black screen`
- `repo:mpv-android/mpv-android hwdec mediacodec`
- `libmpv android hls headers`
- `libmpv android track-list sid subtitle`
- `mpv android glsl-shaders Anime4K`
- `mpv android secondary-sid`
- `media-kit android hwdec mediacodec-copy`
- `streamyfin mpv subtitle track-list sid`
