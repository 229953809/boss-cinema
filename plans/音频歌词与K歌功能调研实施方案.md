# 听歌/K歌功能参考调研：SPlayer 及相关音乐 API

调研日期：2026-06-29

本文记录 SPlayer 主线、SPlayer 相关二开、coco-downloader、HE-Music-Flutter 等项目中对 WebHTV 后续听歌/K歌功能有复用价值的实现。当前 WebHTV 是 Java Android 项目，后续落地应优先按现有 `LyricsRepository` / `LyricsProvider` 小步增强，不建议照搬 SPlayer 的 Electron/Vue/Kotlin 架构。

## 参考项目

| 项目 | 价值点 | 本地调研路径 |
| --- | --- | --- |
| `SPlayer-Dev/SPlayer` | 主线歌词策略、QQMusic QRC、网易云 YRC/TTML、AMLL 渲染、流媒体库 | `/private/tmp/SPlayer` |
| `SPlayer-for-Android` | Android 客户端包装方案、内嵌网易云增强 API、同名/ID 本地歌词识别 | `/private/tmp/SPlayer-for-Android` |
| `SPlayer-ROM-Compat` | Android 本地 API 门面、QQMusic 原生适配、unblock 多源 fallback | `/private/tmp/SPlayer-ROM-Compat` |
| `GMPlayer` | SPlayer 二开，歌词解析器拆分更细，桌面/迷你歌词 UI、音频可视化 | `/private/tmp/GMPlayer` |
| `KfunMusic` | SPlayer 二开，保留桌面歌词/任务栏歌词路由与 UI 结构 | `/private/tmp/KfunMusic` |
| `coco-downloader` | 多音乐源搜索、播放地址、歌词下载实现，尤其酷我/波点/酷狗/咪咕 | `/private/tmp/coco-downloader` |
| `HE-Music-Flutter` | 移动端歌词域模型、本地/内嵌/旁挂歌词优先级、逐字 token 解析 | `/private/tmp/HE-Music-Flutter` |

## SPlayer 主线可借鉴点

SPlayer 的歌词核心在 `src/core/player/LyricManager.ts`。它把歌词拆成两条数据线：

- `lrcData`：普通逐行歌词。
- `yrcData`：逐字歌词，优先用于 Apple Music-like / KTV 式高亮。

在线歌词优先级大致是：

1. QQMusic QRC：`qqMusicMatch(keyword)`，命中后优先填充 `yrcData`。
2. TTML：`songLyricTTML(id)`，来自 AMLL DB，适合逐字/多语种高质量歌词。
3. 网易云 `/lyric/new`：使用 `lrc`、`tlyric`、`romalrc`、`yrc`、`ytlrc`、`yromalrc`。

关键设计点：

- 支持歌词源优先级切换：`qm`、`ttml`、`official`、`auto`。
- 普通歌词、翻译、罗马音分开获取，再按时间或索引对齐。
- 对本地歌曲先读本地歌词，再按开关尝试 QQMusic 匹配逐字歌词。
- 请求序列号防止旧请求覆盖新歌曲歌词。
- 缓存原始歌词内容，而不是只缓存解析结果，方便以后解析器升级。
- 内置歌词排除规则，过滤广告、版权声明、无意义行。

对 WebHTV 的启发：

- 现有 `LyricsResult` 后续可以扩展 `words`、`translation`、`romanization`，但 UI 先保持普通行显示。
- 增加 Provider 优先级开关时，应保留自动模式：本地/缓存 -> Kuwo/QQMusic -> LRCLIB/NetEase。
- 歌词缓存建议按 `title + artist + duration` 做 key，并记录 provider、raw format、fetch time。

SPlayer 里非歌词能力也值得记录：

- 流媒体库：支持 Jellyfin、Navidrome、Emby、OpenSubsonic，可作为 WebHTV 后续“音乐库入口”的参考，不必第一阶段实现。
- 网易云扩展接口：`/song/dynamic/cover`、`/song/chorus`、`/song/wiki/summary`、`/sheet/list`、`/sheet/preview`，分别对应动态封面、副歌时间、百科、乐谱。
- 桌面/任务栏/迷你歌词：本质是同一份播放状态投射到不同容器，WebHTV 可类比为手机非全屏、手机全屏、TV 全屏三套 View。
- 音频可视化：适合作为音频播放页氛围层，但必须独立于歌词主链路，避免影响低端电视性能。
- Last.fm / Discord RPC / 系统媒体控制：对 Android TV 价值有限，但“播放状态对外同步”的设计可以参考。

## SPlayer-for-Android 可借鉴点

SPlayer-for-Android 不是简单 WebView 壳，它做了 Android 侧打包和 API 适配：

- `package.json` 依赖 `@neteasecloudmusicapienhanced/api`。
- `scripts/build-android-node.ts` 把网易云增强 API vendor 到 Android 包内。
- `API/mobile-server.ts` / `API/server.ts` 暴露 `/api/netease/*`。
- README/CHANGELOG 记录支持 `.lrc`、`.ttml`、同目录同名歌词、`歌曲ID.ttml`、`歌名.歌曲ID.lrc`。

对 WebHTV 的启发：

- 不建议在当前阶段引入 Node runtime 或完整内嵌 API 服务，包体和维护成本高。
- 但“本地歌词识别规则”值得落地：同目录同名 `.lrc` / `.ttml`，以及通过媒体标题匹配旁挂歌词。
- Android 端可先做轻量 HTTP Provider，后续如果需要复杂解密，再单独加 Java/Kotlin 小模块。

## SPlayer-ROM-Compat 可借鉴点

ROM-Compat 对 Android 更有参考价值。它在 Android 侧做了本地 API 门面：

- `AndroidLocalApiService.kt` 统一处理 `/api/netease/*`、`/api/qqmusic/*`、`/api/unblock/*`、`/api/control/*`。
- `AndroidQQMusicService.kt` 原生实现 QQMusic 搜索、歌词、match。
- `/api/unblock/netease` 使用镜像源取播放地址。
- `/api/unblock/kuwo`、`/api/unblock/bodian`、`/api/unblock/gequbao` 做播放地址 fallback。

QQMusic 关键实现：

- API：`https://u.y.qq.com/cgi-bin/musicu.fcg`。
- 搜索模块：`music.search.SearchCgiService` / `DoSearchForQQMusicLite`。
- 歌词模块：`music.musichallSong.PlayLyricInfo` / `GetPlayLyricInfo`。
- 请求字段：`qrc=1`、`trans=1`、`roma=1`、`crypt=1`。
- QRC 解密 key：`!@#)(*$%123ZXC!@!@#)(NHL`。
- 返回可拆成 `qrc`、`lrc`、`trans`、`roma`。

对 WebHTV 的启发：

- QQMusic 是下一阶段最值得补的逐字歌词源，中文曲库覆盖比 LRCLIB 更强。
- 可以先实现搜索 + QRC/LRC 解密 Provider，不需要先做播放地址 unblock。
- Android 本地 API 门面思路可用于隔离复杂源：上层继续调用 `LyricsProvider`，复杂请求封装在 provider 内部。

## coco-downloader 多源 API 梳理

coco-downloader 的 Provider 结构对 WebHTV 最容易复用，接口职责清晰：`search`、`getPlayInfo`、`getLyric`。

### Kuwo / Bodian

已被当前 WebHTV `KuwoClient` 部分采用。

- 搜索：`https://search.kuwo.cn/r.s` 或 `https://bd-api.kuwo.cn/api/search/music/list`。
- 歌词：`http://mlyric.kuwo.cn/mobi.s`。
- 歌词参数：`type=lyric&req=2&lrcx=1&rid={id}&songname=&artist=&corp=kuwo&fromchannel=bodian`。
- `q` 参数为上述 query 的 Base64。
- `content` 返回 Base64，需要解码；歌词内可能有 `<start,duration>` 逐字 token。

后续可做：

- 保留 `<start,duration>` token 到 `LyricWord`，不要只清理成行文本。
- 对搜索结果做标题/歌手/时长打分，避免同名歌曲误匹配。

### QQMusic

coco-downloader 同时有两套思路：

- 官方搜索：`http://u6.y.qq.com/cgi-bin/musicu.fcg`，`DoSearchForQQMusicDesktop`。
- 普通 LRC：`https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`。
- 播放地址 fallback：第三方 `xcvts`、`cyapi`、`vkeys`。

与 ROM-Compat 相比：

- coco-downloader 的普通 LRC 更简单，但逐字能力弱。
- ROM-Compat 的 `musicu.fcg + PlayLyricInfo + QRC decrypt` 更适合作为 WebHTV 逐字歌词方案。

### Kugou

关键接口：

- 搜索：`https://songsearch.kugou.com/song_search_v2`。
- 歌词搜索：`http://lyrics.kugou.com/search?keyword={keyword}&duration={duration}&hash={hash}`。
- 歌词下载：`http://lyrics.kugou.com/download?ver=1&client=pc&id={id}&accesskey={accesskey}&fmt=lrc&charset=utf8`。
- 歌词内容 Base64 编码。

价值：

- 适合作为普通 LRC fallback。
- 依赖 hash/duration，只有标题时匹配质量一般。

### Migu

关键接口：

- 搜索：`https://c.musicapp.migu.cn/v1.0/content/search_all.do`。
- 播放地址：`https://c.musicapp.migu.cn/MIGUM3.0/strategy/listen-url/v2.4`。
- fallback：`https://app.pd.nf.migu.cn/MIGUM3.0/v1.0/content/sub/listenSong.do`。

价值：

- 播放源和曲库可参考，但歌词能力不是第一优先。
- 请求头较重，稳定性要单独验证。

### Gequbao

关键接口：

- 搜索页：`https://www.gequbao.com/s/{keyword}`。
- 歌曲页：`https://www.gequbao.com/music/{id}`。
- 播放地址：`https://www.gequbao.com/api/play-url`。

价值：

- 更偏播放地址 fallback，不建议作为第一阶段歌词源。
- HTML 抓取脆弱，适合实验，不适合默认启用。

### 其他源

coco-downloader 还包含 Bugu、Gequhai、QQMP3、Livepoo、Jianbin 等。它们多为第三方聚合或播放地址源，稳定性和合规风险高。WebHTV 后续可以把它们作为“可选实验 provider”，不要默认启用。

## GMPlayer / KfunMusic 可借鉴点

GMPlayer 是 SPlayer 二开，歌词处理拆分得更细：

- `src/utils/LyricsProcessor/lyricParser.ts` 同时处理 `lrc`、`tlyric`、`romalrc`、`yrc`、`ytlrc`、`yromalrc`。
- 输出既有普通结构，也有 AMLL 兼容结构。
- 支持 `lyricsPosition`、`lyricsBlock`、`lyricsFontSize`、`lyricLineHeight`、`lyricTimeOffset`。
- MiniPlayer/DesktopLyrics/TaskbarLyrics 都复用歌词数据。

KfunMusic 保留了桌面歌词和任务栏歌词的页面结构，说明 SPlayer 二开普遍把歌词作为独立显示层，而不是绑死在播放页。

对 WebHTV 的启发：

- TV 全屏、手机非全屏、音频播放页应共享同一份歌词状态，只换 View。
- 应增加“歌词时间偏移”设置，K歌场景很需要。
- 可做一个轻量音频可视化，不影响歌词功能主链路。

## HE-Music-Flutter 可借鉴点

HE-Music-Flutter 的歌词域模型适合移动端：

- `parse_lrc.dart` 支持 `[offset:]`。
- 支持 `<start,duration>字` token，能判断是否逐字歌词。
- 支持 `translation`、`romanization` 分离。
- `download_lyric_resolver.dart` 会保存 `.lrc` 旁挂文件，保留逐字格式。

对 WebHTV 的启发：

- 解析器应先统一成 `LyricDocument` / `LyricLine` / `LyricWord` 模型。
- 行级显示可以忽略 word token，但数据层不要丢。
- 本地缓存保存 raw lyric，导出/旁挂也保留 raw lyric。

## 后续 Provider 优先级建议

当前已实现：本地详情/inline LRC、LRCLIB、Kuwo。

建议下一阶段：

1. 完善 Kuwo 逐字解析：保留 `<start,duration>` token，UI 仍可先按行显示。
2. 增加 QQMusic Provider：搜索、match、`PlayLyricInfo`、QRC 解密、翻译/罗马音。
3. 增加歌词偏移设置：手机/TV 共用，单位毫秒。
4. 增加本地旁挂歌词：同目录同名 `.lrc` / `.ttml`，优先于网络。
5. 再评估 NetEase `/lyric/new`：如果没有登录依赖或可稳定调用，再加入中文 fallback。
6. 后续再考虑 Kugou/Migu/Gequbao 等实验源，默认关闭。

建议默认优先级：

`本地/旁挂/缓存 -> Kuwo -> QQMusic -> LRCLIB -> NetEase -> Kugou fallback`

说明：

- 中文用户场景下，LRCLIB 覆盖不如 Kuwo/QQMusic。
- QQMusic 逐字歌词质量高，但实现复杂，应独立开关。
- NetEase `/lyric/new` 能提供 YRC/翻译/罗马音，但接口稳定性和依赖要实测。

## K歌能力路线

评分谱、无谱娱乐评分、UltraStar / MIDI 导入和重型制谱方案取舍，详见 `plans/KTV家庭K歌功能需求实现方案.md`。本文件只记录歌词源和逐字显示能力，Kuwo / QQMusic / NetEase / Kugou / Migu / TTML 等歌词源不能直接等同于 K歌音符轨。

### 第一阶段：歌词可用

- LRC 行级同步。
- 自动匹配歌词。
- 手动搜索/切换歌词源。
- 本地缓存。

### 第二阶段：逐字/KTV 显示

- 数据层支持 `LyricWord`。
- Kuwo `<start,duration>`、QQMusic QRC、NetEase YRC、TTML 转统一模型。
- 全屏音频模式显示当前行 + 下一行。
- 增加歌词偏移、字体大小、显示行数。

### 第三阶段：伴奏/节拍

当前不实施。

已复核 SPlayer、SPlayer Android 二开、HE-Music、lx-music-mobile、coco-downloader 等参考项目，未发现可直接稳定获取“伴奏音轨 / 节拍器 / BPM”的公共 API。命中内容主要是本地媒体处理、播放源、榜单名或 heartbeat 之类非音乐节拍语义。

按产品边界，若能力依赖用户本地伴奏文件、手动输入 BPM、或客户端实时音频分析，本阶段不做，避免引入低稳定性和高维护成本功能。

### 第四阶段：评分

- 蓝牙麦克风在 Android 上通常只作为系统输入，实时采样和播放器音频混合会受设备/系统限制。
- 评分可做娱乐级：音高检测 + 时间对齐 + 简单稳定度，不承诺专业 K歌评分。
- 真正可靠评分需要伴奏、人声输入、降噪、延迟校准，复杂度明显高于歌词显示。

## WebHTV 落地约束

- 当前项目是 Java Android，优先继续用 Java `LyricsProvider`，不要为了歌词引入 Kotlin/Retrofit/Compose 大改。
- Provider 要能失败降级，不能阻塞播放。
- 网络源默认要有超时、User-Agent、缓存和开关。
- 对 TV 端要避免过重绘制，逐字高亮应先在手机验证，再扩展到 TV。
- 涉及第三方音乐源的播放地址解析风险较高，当前任务聚焦歌词，不把“解锁播放”作为默认功能。

## 2026-06-30 深搜补充

本轮重点继续搜索 Android、TTML、QRC/YRC/KRC、逐字歌词渲染、K歌评分相关开源实现。结论是：歌词源和解析仍有可补空间；伴奏、BPM、节拍器没有稳定公共 API；评分只能作为后续实验功能，不应进入当前默认链路。

### 可直接借鉴的方向

| 项目 | 价值 | 落地建议 |
| --- | --- | --- |
| `Zeehan2005/AMLL-DroidMate` | Android 端多源歌词、候选排序、AMLL WebView 渲染、features 探测 | 借鉴候选排序：缓存优先、置信度、逐字/翻译/罗马音特性、源偏好 |
| `N-Zik-Group/Better-Lyrics` | Android/Kotlin TTML 解析，处理 `ttp:begin`、无行级 begin、`lyricOffset` | 不复制 GPL 代码，只吸收解析兼容思路；已在 `TtmlClient` 增强 |
| `dokar3/amlv` | Apache-2.0，Compose 版 Apple Music-like lyrics view | 只作为歌词动效和行切换参考；当前项目是 Java View，不引入 Compose |
| `better-lyrics/api` | Go 实现的 TTML API，接口有 `/getLyrics`、`/ttml/getLyrics`、`/kugou/getLyrics` | 实测未缓存查询需要 `X-API-Key`，不适合作默认无配置源 |
| `xmcp/QRCD`、`apoint123/qrc-decoder` | QQMusic QRC 抓取/解码参考 | 仅用于校验现有 QQMusic QRC 解码逻辑，不直接复制 |
| `TheX24/Spicy-Player` | Android 离线播放器，复刻 Spicy Lyrics 逐字视觉 | UI 动效参考，非歌词源参考 |
| `AirLyrics/AirLyrics` | Android 悬浮歌词，多 App 同步 LRC | 可参考悬浮层/非全屏歌词显示边界，不影响播放器控制层 |

### 本轮已吸收的最佳实践

- 手机非全屏歌词搜索结果不再居中遮挡播放器，而是贴近底部、限制宽高、取消背景变暗；全屏和电视端不改。
- 手动搜索结果是并发 progressive 更新，后续结果到达时需要重新约束弹窗高度，避免列表变长后盖住播放器。
- TTML 解析兼容 `ttp:begin`/`ttp:end`、命名空间属性、缺失行级 `begin` 时取首个 span 时间、`lyricOffset`、`s/ms/m/h` 时间单位。
- TTML 解析变更后需要提升歌词缓存版本，避免旧转换结果继续命中。

### 暂不建议默认加入的内容

- `better-lyrics/api`：文档写着 cached requests free，但真实未缓存请求会返回 `API key required`。除非后续用户提供 key 或我们做可选配置，否则不接入默认自动源。
- 伴奏源：未发现稳定 API 能直接给出伴奏音轨。搜索命中多为本地 AI 人声分离、用户自备伴奏、播放地址解锁，不符合当前产品边界。
- BPM/节拍器：未发现稳定音乐源 API。若依赖客户端音频分析或手动 BPM，不做。
- 评分：`TarsosDSP`、PitchDetect、flutter-fft 等可做音高检测，但需要麦克风权限、延迟校准、降噪、音频输入设备兼容，适合后续实验开关，不适合当前默认功能。

### 当前实施状态

- 已完成：LRC/TTML/QRC/YRC/KRC/MRC 等多源接入，自动并发搜索，搜索结果 progressive 更新，手动搜索/切换候选，歌词缓存与用户选择记忆。
- 已完成：逐字歌词统一为 `<start,duration>` token 渲染，行切换和逐字闪烁问题已修正，支持时间偏移、字号、显示行数。
- 已完成：手机非全屏歌词搜索结果避开播放器，TV/全屏搜索界面保持独立；音频/有声书后台不进入 PiP。
- 已完成：桌面歌词开关、悬浮窗权限引导、可拖动位置记忆、长按设置项重置位置。
- 已完成：本地旁挂歌词增强，同目录支持源文件同名、标题、`歌手 - 歌名`、`歌名 - 歌手`、`歌名.文件名` 等 `.lrc` / `.ttml` 命名，大小写扩展名兼容。

### 评分谱源网页可用性核对

以下只讨论 UltraStar / MIDI 这类“音符轨 / 评分谱”，不把 Kuwo、QQMusic、NetEase、Kugou、Migu、TTML 等逐字歌词源算作评分谱源。

| 来源 | 2026-06-30 实测结果 | 可集成性 |
| --- | --- | --- |
| USDB `usdb.animux.de` | 首页、详情页 `/?link=detail&id=31037`、可视化页 `view.php?id=31037&database1=deluxe_songs` 可匿名访问；`POST /?link=list` 搜索和 `/?link=gettxt&id=31037` 下载会返回未登录；详情页含 BPM/GAP/语言等信息；可视化页 HTML 内含 `giveinfo0(type,start,length,pitch,note,text)`，能拿到完整音符数据 | 不能说不可用。适合做可选 `KaraokeTrackProvider`：用户 Cookie 下载完整 `.txt`；或用户提供 USDB ID/详情页后，用详情页 + `view.php` 重建 UltraStar 音符轨 |
| UltraStar-ES `ultrastar-es.org` | 首页和 `GET /en/canciones?busqueda=Tool` 搜索页可匿名访问；搜索结果含 artist/title/year/language、YouTube id、`/en/canciones/descargar/txt/{token}`；直接请求 txt 下载会 302 到论坛登录页 | 匿名搜索可用，下载需要登录态。适合做可选 `KaraokeTrackProvider`：匿名展示候选，用户自备 Cookie 后下载 `.txt`，或引导用户手动下载后导入 |
| GitHub `razzertronic/usdx-songs` | `git/trees/master?recursive=1` 匿名可访问，包含大量 UltraStar `.txt`；raw 链接可直接下载文本 | 已接入默认评分谱搜索。许可证 Unlicense，不打包资源，只在用户选择时下载 `.txt` |
| GitHub `Vasil-Pahomov/UltraStarSongs` | `git/trees/master?recursive=1` 匿名可访问，包含 UltraStar `.txt` 和音频资源；raw 链接可直接下载文本 | 已接入默认评分谱搜索。仓库 GPL-3.0 且含音频，App 只展示/下载用户选择的 `.txt`，不内置资源 |
| USDB-fetcher / usdb_downloader | 代码路径主要处理用户已有 UltraStar `.txt` 后下载/整理音视频，不负责在线拿谱 | 不作为在线谱源，只作为“用户已有 `.txt` 后整理资源”的参考 |
| USDX / Performous / Vocaluxe / ultrastar-score | 解析本地 UltraStar `.txt` 并评分，不提供在线谱源 | 继续参考评分模型，不作为谱源 Provider |

结论：目前已接入多个可用评分谱入口：本地旁挂、UltraStar/MIDI 文件导入、URL 导入、GitHub 公开 UltraStar 谱库搜索、UltraStar-ES 匿名搜索、USDB ID/view 重建。USDB 登录搜索和更多爬虫谱源继续后置，应独立于 `LyricsRepository`，做成用户手动触发、可关闭、必要时用户自备 Cookie 的 `KaraokeTrackProvider`。

### 音符轨生成/提取项目补充核验

用户补充的 AI 回复有参考价值，但需要过滤：它把 K歌评分、音频转 MIDI、唱歌声音检测、深度学习旋律提取、泛音频特征提取混在一起，不能直接等同于“可集成评分谱源”。详细过滤表见 `plans/KTV家庭K歌功能需求实现方案.md`。

| 类型 | 代表项目 | 结论 |
| --- | --- | --- |
| 端侧实时音高检测 | `cwilso/PitchDetect`、`peterkhayes/pitchfinder`、`aubio/aubio` | 可借鉴 YIN / autocorrelation / onset 思路；Android 端第一版仍优先 `AudioRecord + 自实现 YIN/MPM`，必要时再评估 JNI |
| K歌评分/交互参考 | `Asvarox/allkaraoke`、USDX、Vocaluxe、Performous | 有价值，证明 UltraStar 音符轨 + 实时 pitch + 半音容差适合家庭娱乐评分 |
| 离线制谱/音频转 MIDI | `spotify/basic-pitch`、`librosa`、`Essentia`、`audioFlux`、`pretty_midi`、AutoTranscriber | 可作为外部制谱/自托管工具候选，不进入 App 播放时默认链路 |
| 平台不匹配或仅作体验参考 | `vadymmarkov/Beethoven`、AudioKit、Web Audio pitch demos | 可看调音器和实时反馈体验，但不能直接复用到 Android 主项目 |
| 不进入默认路线 | `TuneNN`、`melodyExtraction_JDC`、SingingVoiceDetection、深伪检测、情绪/类型识别、Demucs/Whisper/CREPE/PyTorch 链路 | 过重或方向偏离，不适合手机/TV 端默认 K歌功能 |

最终判断：这批项目能补强“如何检测麦克风音高”和“未来外部制谱工具怎么做”，但没有提供稳定可直接集成的主流商业评分谱 API。App 主线仍应是本地/导入 UltraStar `.txt`、GitHub 公开 UltraStar 谱库、UltraStar-ES/USDB 可选爬虫谱源、MIDI/KAR 导入，以及无谱娱乐评分。

### 剩余结论

1. 保存当前网络歌词为本地旁挂：可做，但涉及写入媒体目录/SAF 权限与文件覆盖确认，建议后续作为明确用户操作入口单独实现。
2. 伴奏源：未发现稳定 API 能直接给出伴奏音轨。搜索命中多为本地 AI 人声分离、用户自备伴奏、播放地址解锁，不进入默认路线。
3. BPM/节拍器：未发现稳定音乐源 API。若依赖客户端音频分析或手动 BPM，不进入默认路线。
4. 评分：已实现实验性娱乐评分、有谱音准评分和轻量节奏评分谱生成；后续重点是 UI 打磨、输入设备兼容、谱源 Provider 抽象和更多真实谱源。
