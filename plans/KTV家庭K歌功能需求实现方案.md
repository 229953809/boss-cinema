# KTV家庭K歌功能需求实现方案

调研日期：2026-06-30

本文面向 WebHTV 后续“家庭 K歌 / KTV 模式”功能，整合 UltraStar / SingStar 类开源项目、Android 实时音高检测项目、现有歌词功能调研，以及传统信号处理方案。目标是在手机端和电视端实现轻量、可维护、娱乐级的 K歌体验，不引入大模型或重型端侧推理链路。

## 结论摘要

- 可以做，但第一版不应承诺专业 K歌评分。
- 产品上分两条路线：没有音符轨走“自由唱/娱乐跟唱分”，有音符轨走“音准评分”。
- K歌音准评分的核心不是逐字歌词，而是“播放进度 + 参考音符轨 + 麦克风实时音高”。
- 现有 Kuwo / QQMusic / NetEase / Kugou / Migu / TTML 等逐字歌词可用于卡拉OK显示、节奏窗口和无谱娱乐评分，但不能直接用于音准评分，因为歌词没有旋律目标音高。
- 最成熟的轻量音准方案来自 UltraStar / SingStar 生态：使用 UltraStar `.txt` 或 MIDI / KAR 里的主旋律轨作为参考音符轨，用 YIN / MPM / DyWa 等传统音高检测算法分析麦克风输入，再按半音容差算命中率。
- 没有参考音符轨时，不等待自动扒谱，不做伪音准评分；直接显示大号歌词、逐字高亮、麦克风音量/音高反馈，并给出“娱乐跟唱分”。
- 电视端最现实的家庭方案是“电视播放 + 手机当麦克风/控制器”，避免依赖电视盒子或电视系统的麦克风能力。
- 网上能找到的逆向音乐 API 绝大多数只能拿歌词、逐字时间、播放地址或伴奏地址，稳定拿“音符轨/评分谱”的公开接口很少；可直接集成优先级应是本地/导入 UltraStar 或 MIDI，其次才是轻量端侧粗略分析。
- AI 回复中提到的 librosa、Essentia、aubio、madmom、Melodia、NMF，以及 UltraSinger / UltrastarCreatorTool / AutoTranscriber 这类工具有参考价值，但更适合离线制谱或研究；涉及 Demucs、Whisper、CREPE、PyTorch 等重链路的方案在 App 内抛弃。

## 目标与非目标

### 目标

1. 在音频播放场景增加 K歌模式。
2. 复用现有歌词能力，提供大屏 KTV 式歌词显示。
3. 支持麦克风采集，显示实时音高、音量和演唱状态。
4. 无参考音符轨时，提供不误导用户的娱乐跟唱分。
5. 在存在参考音符轨时，提供娱乐级音准评分。
6. 手机端和 TV 端共用核心逻辑，只分离 UI 和输入方式。
7. 后续可扩展手机作为 TV 麦克风，支持家庭多人娱乐。

### 非目标

1. 第一版不做大模型实时伴奏分离、人声分离、自动扒谱。
2. 第一版不承诺专业级评分准确性。
3. 第一版不做复杂复调转 MIDI。
4. 第一版不依赖用户手动输入 BPM 或本地伴奏文件来启用核心功能。
5. 第一版不把 Python 音频分析库直接嵌入 Android 运行时。

## 当前代码基线

当前 `feature/audio-lyrics-karaoke-20260629` 分支已经具备第一版 K歌基础能力：

- 播放界面有 K歌入口和播放设置开关。
- 已接入麦克风权限、`AudioRecord` 采集、YIN 音高检测、实时音量可视化。
- 已有 UltraStar `.txt` parser、`KaraokeTrack`、`KaraokeScorer`、半音容差评分、忽略八度、麦克风延迟配置。
- 本地音频旁挂音符轨已支持同目录候选：`歌曲名.ultrastar.txt`、`歌曲名.karaoke.txt`、`歌曲名.txt`，并兼容媒体标题作为候选名。
- 当前不足：无音符轨“娱乐跟唱分”已有基础实现，但结束页/退出总结还未做；在线/导入音符轨来源还未补齐；MIDI / KAR 主旋律轨导入还未实施。

## 参考项目与可借鉴点

| 项目 | 许可证 | 价值点 | 对 WebHTV 的启发 |
| --- | --- | --- | --- |
| `UltraStar-Deluxe/USDX` | GPL-2.0 | 老牌开源 K歌游戏，音符轨、音高检测、评分逻辑成熟 | 借鉴“按 beat / note 评分、半音容差、八度折叠”的算法思路 |
| `UltraStar-Deluxe/Play` | MIT | Unity 实现，支持桌面和移动端，有 companion app 思路 | 借鉴“手机作为麦克风/遥控器”的 TV 家庭 K歌方案 |
| `Vocaluxe/Vocaluxe` | GPL-3.0 | SingStar 风格 K歌游戏，DyWa pitch tracker、麦克风校准 | 借鉴音高检测、延迟校准、评分 UI |
| `Asvarox/allkaraoke` | 未明确 | 浏览器 K歌游戏，YIN 音高检测，现代评分代码 | 借鉴轻量评分公式、逐 beat 命中率、音高距离计算 |
| `rakuri255/UltraSinger` | MIT | 从音乐生成 UltraStar / MIDI / notes 的离线工具 | 仅作为后续离线制谱参考，不放进端侧第一版 |
| `paradigmn/ultrastar_pitch` | MIT | UltraStar 音高检测/制谱辅助 | 可作为离线音符轨生成参考 |
| `MrDix/ultrastar-score` | GPL-3.0 | 使用 Vocaluxe 音高检测和 USDX 评分逻辑做文件评分 | 验证 UltraStar 评分模型可独立实现 |
| `GuyueHermit/AutoTranscriber` | 未声明 | Python 自动扒谱，CQT、多音高估计、音符追踪、MIDI 输出 | 只适合作为离线制谱思路参考，不进入手机/TV端侧主链路 |
| `akitaonrails/frank_karaoke` | 未声明，个人/教育用途 | Flutter Android，YouTube 包装，无预制谱实时评分，YIN、bandpass、mic 校准、pitch oracle 缓存 | 对“无音符轨娱乐评分”和麦克风串音过滤很有参考价值，不能直接拷代码 |
| `rzru/nightingale` | GPL-3.0-or-later | Tauri/Rust/React，UVR/Demucs、WhisperX、UltraStar 导入、评分、家庭自托管 Web 模式 | 架构和 USDX 支持可参考；分析链路很重，不进入 App 默认链路 |
| Android TarsosDSP 示例项目 | 多数未明确 | `AudioRecord + PitchDetector` 实时音高检测 | Android 端落地参考 |
| `dgruss/SmartMicrophone` | 未明确 | 手机浏览器当 UltraStar 麦克风/遥控器 | TV 端“手机当麦克风”方案参考 |

### 第二份 AI 回复评估

这份回复整体有参考价值，但需要按 WebHTV 的移动/TV 约束重新分层：

- 值得采纳：先做无谱娱乐评分，再做本地/导入音符轨评分，最后才考虑外部制谱工具。这和当前产品节奏一致。
- 值得重点参考：`frank_karaoke` 的无谱评分、YIN pitch、200-3500 Hz bandpass、麦克风热身/校准、播放暂停/seek 后暂停评分、speaker bleed 过滤、pitch oracle 缓存。
- 可参考但不默认集成：`nightingale` 的本地媒体库、USDX 导入、自托管 Web 模式、分析结果缓存、麦克风延迟测试。
- 不采纳为 App 默认链路：UltraSinger / Nightingale 的 Demucs、UVR、WhisperX、PyTorch、模型下载。Nightingale README 明确一首歌 GPU 分析通常 2-5 分钟，CPU 10-20 分钟，这不符合手机/TV 播放时即时 K歌。
- 对 `SwiftF0`、ONNX/TFLite 量化模型的判断：可以作为未来实验，但仍属于模型链路，不放进当前轻量实现。
- 对 `Oboe` 的判断：如果后续 `AudioRecord` 延迟和稳定性不够，可评估 NDK/Oboe；当前 Java 项目先用 `AudioRecord` 更直接。

因此，第二份回复应作为“路线校准”和“无谱评分参考”，不是要求把 AI 制谱链路塞进 App。

## 音符轨获取方式与逆向 API 调研

### 可直接进入产品链路的来源

| 来源 | 能获得什么 | 获取方式 | 集成优先级 | 结论 |
| --- | --- | --- | --- | --- |
| 本地旁挂 UltraStar `.txt` | 歌词、beat、目标音高、音符类型 | 与音频同目录同名；当前已支持 `.ultrastar.txt`、`.karaoke.txt`、`.usdx.txt`、`.txt` | 最高 | 已实现，播放时自动识别 |
| 用户导入 UltraStar `.txt` | 完整评分谱 | Android 文件选择器导入，按当前播放项缓存绑定 | 高 | 已实现，长按播放页 K歌按钮管理 |
| URL 导入 UltraStar `.txt` | 完整评分谱 | 用户粘贴直接返回 `.txt` 文本的链接，下载后缓存绑定 | 高 | 已实现，适合用户从社区站下载/自托管文本 |
| UltraStar 社区库 | UltraStar `.txt` 或可重建的音符数据 | USDB、UltraStar-ES 等社区网站或用户下载文件 | 中 | 不默认静默抓取，但不排除爬虫谱源；适合做用户手动触发、用户自备登录态的可选 Provider |
| MIDI / KAR | 可能包含主旋律轨、歌词事件 | 本地文件选择，用户指定或自动猜测 vocal track | 中 | 可作为第二类评分谱，但轨道选择和歌词对齐要做 UI |
| Rock Band / SingStar / Clone Hero 社区谱 | MIDI/自定义谱面 | 用户本地导入 | 低 | 版权和格式复杂，只作为高级本地导入 |

调研结论：

- USDB / UltraStar-ES 这类 UltraStar 曲库的价值在于提供 `.txt` 评分谱或可重建的音符数据；公开稳定 JSON API 不明确，但网页源仍可用，不能直接排除。
- 这类源不适合默认静默爬取，但适合做“评分谱搜索/导入”的可选链路：用户主动搜索、选择结果，必要时用户自备账号 Cookie。
- `ultrastar-score`、USDX、Performous、Vocaluxe 证明 UltraStar `.txt + 麦克风音高` 的评分链路成熟，但它们不是音符轨在线来源。
- 因此 App 内的首选策略不是自动抓库，而是把 UltraStar `.txt` 作为标准导入格式：本地旁挂优先，后续补文件选择和 URL 导入。

### 音符轨相关开源项目 / 网页 API 专项清单

| 项目或站点 | 实际使用的入口 | 能拿到音符轨吗 | 是否集成 | 结论 |
| --- | --- | --- | --- | --- |
| USDB `usdb.animux.de` | 网页 `POST /?link=list` 搜索；详情 `/?link=detail&id={id}`；下载 `/?link=gettxt&id={id}`；编辑页 `/?link=editsongs&id=...`；可视化页 `view.php?id={id}&database1=deluxe_songs` | 是。登录后编辑页/下载页可拿完整 `.txt`；匿名 `view.php` 含 `giveinfo0(type,start,length,pitch,note,text)`，可结合详情页 BPM/GAP 重建音符轨 | 已集成可选 Provider | 关键词搜索在用户已有 Cookie 时可用；无登录态时支持 ID/详情页导入和 RSS fallback；导入通过详情页 + view 重建，不强依赖完整下载权限 |
| `martiinii/UltraStar-CLI` | 代码确认：`src/api/usdb/search.ts` 用 `POST /?link=list`；`lyrics.ts` 用 `GET /?link=editsongs&id=...` 解析 `<textarea>`；`youtube.ts` 用详情页评论解析 YouTube | 是，USDB textarea | 作为实现参考 | 证明 USDB 可抓取，但依赖登录和 HTML 结构，不适合默认静默抓站 |
| `Salomon-MH/USDB-fetcher` | 读取用户本地 USDB `.txt`，解析 `#VIDEO:v=...` / `#VIDEO:a=...` 后用 `yt-dlp` 下载音视频 | 不负责在线获取 `.txt`，只处理已有谱 | 不集成 | 支持“用户先拿到 UltraStar 文件，再导入 App”的路线 |
| `ngoc-quoc-huynh/usdb_downloader` | 读取 input 目录已有 `.txt`，保留 headers/lyrics，下载 YouTube 音视频并重写 `#MP3/#VIDEO/#COVER` | 不负责在线获取 `.txt` | 不集成 | 和上面一致，是本地谱面整理工具，不是在线音符轨 API |
| UltraStar-ES `ultrastar-es.org` | `GET /en/canciones?busqueda={keyword}` 匿名搜索；结果 HTML 内含 artist/title/year/language、YouTube id、`/en/canciones/descargar/txt/{token}`、torrent 链接 | 是，但 txt 下载需要登录态 | 已集成可选 Provider | 匿名搜索已接入；下载链接保留登录态提示，用户有 Cookie 时可尝试直接导入，否则手动下载后导入 |
| `razzertronic/usdx-songs` | GitHub tree API `repos/razzertronic/usdx-songs/git/trees/master?recursive=1`，匹配 `.txt` 后用 raw.githubusercontent.com 下载 | 是，公开 UltraStar `.txt` | 已集成默认搜索 | 2026-06-30 实测可用，仓库许可证 Unlicense；适合默认搜索结果，不打包内容，只按用户选择下载文本 |
| `Vasil-Pahomov/UltraStarSongs` | GitHub tree API `repos/Vasil-Pahomov/UltraStarSongs/git/trees/master?recursive=1`，匹配 `.txt` 后用 raw.githubusercontent.com 下载 | 是，公开 UltraStar `.txt` | 已集成默认搜索 | 2026-06-30 实测可用，仓库 GPL-3.0 且含音频文件；App 只展示/下载用户选择的 `.txt`，不内置资源 |
| 用户自定义 GitHub 谱源 | 播放页“评分谱”菜单配置，每行 `owner/repo@branch` 或 GitHub 仓库 URL；内部使用 GitHub tree API + raw 下载 | 是，取决于仓库是否包含 UltraStar `.txt` | 已集成 | 用于后续快速接入新发现的公开谱库，不需要改代码发版 |
| USDX / Performous / Vocaluxe | 本地扫描 UltraStar 歌曲目录，解析 `.txt`，运行时按 beat/pitch 评分 | 是，但只是本地格式和评分引擎 | 已参考评分模型 | 它们不是在线来源；可继续参考 note bar、line bonus、golden note 等 UI/评分细节 |
| `ultrastar-score` | 输入 UltraStar `.txt` + 人声音频，解析 note line 并按 Vocaluxe/USDX 思路评分 | 是，但依赖本地 `.txt` | 已参考评分逻辑 | 适合校验半音容差、忽略八度、逐音符/逐行统计 |
| UltraSinger / UltrastarCreatorTool | YouTube/本地音频 -> 人声分离 -> Whisper/对齐 -> pitch -> 生成 UltraStar/MIDI | 可生成音符轨 | 不进 App 默认链路 | 重型制谱工具，适合未来桌面/自托管服务，不适合手机/TV 播放时即时执行 |
| AutoTranscriber | 音频 -> CQT/CREPE/多音高估计 -> MIDI/PDF | 可生成 MIDI 草稿 | 不进 App 默认链路 | 能作为离线制谱实验参考；不适合端侧实时默认能力 |
| 全民K歌/唱吧/酷狗唱唱等 K歌平台 | 公开逆向资料多是登录、搜索、伴奏/作品/播放地址 | 未确认稳定公开目标音高轨 | 不集成 | 签名、登录、版权和接口变动风险高；保留未来用户自备凭证 Provider 扩展点 |

当前落地选择：

1. 已实现本地旁挂和导入绑定，把 UltraStar `.txt` 作为第一标准音符轨格式。
2. 已实现 MIDI / KAR 导入，解析标准 MIDI note-on/off 事件并选择更像人声旋律的轨道，转换为 UltraStar 格式后绑定当前播放项。
3. 已实现 URL 导入，支持直接返回 UltraStar 文本的链接、GitHub/GitLab raw/blob 链接、USDB ID/详情页 view 重建。
4. 已实现在线搜索的非 USDB 默认来源：GitHub 公开 UltraStar 谱库、UltraStar-ES 匿名搜索；USDB 放在后置候选，不作为当前优先实现方向。
5. 已实现“轻量节奏评分谱”：从当前歌词时间轴生成 UltraStar RAP 类型评分窗口，用于无人工谱时的节奏/参与度评分，不做目标音高判断。
6. 已完成独立 `KaraokeTrackProvider` 抽象，当前 GitHub、UltraStar-ES、USDB 搜索都通过 Provider 调度；后续新增谱源不能混进 `LyricsRepository`。
7. 后续如果继续扩展登录态爬虫谱源，应使用用户自备 Cookie，不进入默认静默链路。

### 歌词/音乐逆向 API 的实际价值

| 来源 | 已知接口或方式 | 能拿到 | 是否可当音符轨 | WebHTV 用途 |
| --- | --- | --- | --- | --- |
| Kuwo / Bodian | `http://mlyric.kuwo.cn/mobi.s`，`type=lyric&req=2&lrcx=1&rid=...`，内容 Base64，含 `<start,duration>` token | 行级/逐字歌词时间 | 否 | 优先逐字歌词源；无谱娱乐评分的歌词时间窗口 |
| QQMusic | `https://u.y.qq.com/cgi-bin/musicu.fcg`，`music.musichallSong.PlayLyricInfo`，`qrc=1&trans=1&roma=1&crypt=1`，QRC 解密 | QRC 逐字、翻译、罗马音 | 否 | 高质量逐字歌词源 |
| NetEase | `/api/song/lyric`、`/lyric/new`、YRC/翻译/罗马音 | LRC/YRC/翻译 | 否 | 中文/日文歌词 fallback，外语歌曲匹配补强 |
| Kugou | `lyrics.kugou.com/search` + `lyrics.kugou.com/download`，KRC/LRC，Base64/加密内容 | KRC 逐字或 LRC | 否 | 逐字/普通歌词 fallback |
| Migu | `jadeite.migu.cn/music_search`、`resourceinfo.do`、`mrcurl/lrcUrl` | MRC/LRC | 否 | 中文曲库 fallback |
| AMLL TTML DB | `amll-ttml-db`、`amlldb.bikonoo.com`、镜像 CDN | TTML 逐字/翻译 | 否 | 高质量逐字歌词和多语种展示 |
| LRCLIB | `/api/get`、`/api/search` | 普通/同步 LRC | 否 | 通用 fallback，外语覆盖较好但中文逐字弱 |

关键判断：

- 这些 API 的“逐字”只表示每个字/词何时显示，不包含“这个字应该唱什么音高”。
- 它们可以支撑卡拉OK显示、歌词偏移、节奏参与度、演唱覆盖率，但不能支撑真正音准评分。
- 如果后续某个逆向源能稳定返回 UltraStar/MIDI 等音符轨，应新增 `KaraokeTrackProvider` 接入，而不是混进 `LyricsProvider`。

### K歌类 App / 伴奏类逆向来源

公开资料里常见的全民K歌、唱吧、酷狗唱唱、K歌伴奏站等逆向内容，多数集中在登录、搜索、播放地址、伴奏下载或作品下载；能稳定公开获取“目标音高轨/评分谱”的资料不足，且通常存在签名、登录、版权和接口频繁变动问题。

处理策略：

- 不作为默认在线源。
- 不把私有签名、登录态、灰色下载链路写入第一版。
- 保留扩展点：未来如果有稳定、可配置、用户自备凭证的来源，只实现为可关闭的 `KaraokeTrackProvider`。
- 伴奏如果只能依赖此类接口获取，也不进入第一版；有音频播放就能 K歌，无伴奏不阻塞。

### 轻量端侧方案

轻量端侧实现只做两类：

1. 无音符轨娱乐评分：直接使用歌词时间、麦克风音量、音高稳定度、起唱/停唱节奏，不判断目标音准。
2. 有音符轨实时评分：使用本地/导入 UltraStar 或 MIDI 的目标音高，端侧只做麦克风音高检测和半音距离计算。

可作为后续增强的轻量思路：

- Frank Karaoke 的 pitch oracle：对“参考音频”离线/缓存跑一次 YIN，得到原曲音高时间线，播放时用于识别手机麦克风里的扬声器串音。
- 该时间线不能等同于主唱音符轨，因为原曲里可能有伴奏、人声、和声；它更适合做串音过滤、旋律轮廓参考、缓存复用，不适合作为默认音准评分依据。
- 如果要实现，必须异步、可失败、可缓存；首次分析不能阻塞播放。

不建议默认做“播放时自动扒谱”：

- 原曲混有伴奏、人声、和声，端侧不做人声分离时很难稳定提取主唱旋律。
- 即使用 pYIN / CQT / HPSS 等传统方法，也需要较长分析时间和大量后处理，用户播放时等待体验差。
- 生成错误音符轨会导致评分明显误导，不如直接展示“娱乐跟唱分”。

### 明确抛弃的重方案

| 方案 | 代表项目 | 抛弃原因 |
| --- | --- | --- |
| Demucs / Spleeter / Open-Unmix 人声分离 | UltraSinger、UltrastarCreatorTool、AutoTranscriber 部分链路 | 包体、算力、耗电、延迟都不适合手机/TV 默认运行 |
| Whisper / WhisperX 自动歌词对齐 | UltraSinger、UltrastarCreatorTool | 大模型链路，用户已明确不需要 |
| CREPE / torchcrepe / 大型神经网络音高检测 | UltraSinger、AutoTranscriber 部分链路 | 端侧成本高，YIN/MPM 已够娱乐评分 |
| Python 桌面音频工具链内嵌 App | librosa、Essentia Python、madmom、pretty_midi | Android 打包和运行成本高，适合作外部工具，不进主 App |
| 复杂复调转 MIDI | NMF/PLCA/多音高追踪 | 准确率和维护成本不适合产品默认体验 |

保留价值：这些项目的算法流程可作为未来独立制谱工具参考，但不是 WebHTV App 内置功能。

### UltraStar / SingStar 评分思路

成熟 K歌游戏的评分模型整体很轻：

1. 歌曲包含参考音符轨：每个音符有开始时间、长度、目标音高和歌词片段。
2. 播放时按当前位置找到当前目标音符。
3. 麦克风实时检测用户音高。
4. 把用户音高折叠到目标音高附近的八度，避免男女声八度差导致误判。
5. 如果半音距离在容差内，则认为当前时间片命中。
6. 按命中的音符时长 / 总音符时长计算分数。

USDX 的核心命中逻辑可概括为：

- 用户音高与目标音高相差超过 6 个半音时，按八度加减 12 半音。
- 根据难度设置容差，娱乐模式可以用 ±1 到 ±2 半音。
- 命中后按音符长度累计分数，普通音符、金色音符、Rap 音符可有不同权重。

allkaraoke 的现代实现也类似：

- 频率转 MIDI 半音：`round(12 * log2(freq / 440)) + 69`。
- 计算 pitch class 距离，默认弱化八度差。
- 只有距离为 0 或在容差内的时间片计入命中。
- 最终分数按命中的加权 beat 数归一化。

## AI 回复内容的参考价值评估

用户提供的 AI 回复主要聚焦“非大模型音频分析”和“歌曲生成主旋律 / MIDI”的工具链，整体有参考价值，但它解决的是 K歌体系中的另一个问题：**如何在没有人工音符轨时，离线生成或辅助生成参考音符轨**。

### 有价值的部分

- `YIN / pYIN / SWIPE`：可用于单声部基频估计。YIN 适合端侧实时麦克风音高检测；pYIN 更适合离线分析。
- `aubio`：C 实现轻量，适合实时 pitch / onset 检测；如果未来不用 TarsosDSP，可评估 JNI 接入。
- `Essentia`：包含 Melodia、pYIN 等算法，适合离线主旋律提取工具。
- `librosa`：适合开发期和离线实验，做 HPSS、CQT、特征提取、NMF。
- `madmom`：适合离线 onset / beat 追踪，不建议放进 Android 第一版。
- `pretty_midi / mido / music21`：适合把提取结果导出成 MIDI 或做音符后处理。
- `Melodia`：传统主旋律提取算法，适合从伴奏中估算主唱旋律，但不适合端侧实时默认启用。
- `NMF / PLCA`：可用于传统复调分解实验，但准确性和调参成本高，不适合第一版产品化。

### 不适合作为第一版的部分

- `librosa + Essentia + madmom + pretty_midi` 都偏 Python / 桌面 / 离线工具链，不适合直接嵌入手机和电视。
- 复调转 MIDI、伴奏分离、主旋律自动扒谱都存在稳定性问题，复杂歌曲下误差明显。
- 自动生成音符轨即使不用大模型，也需要大量后处理和人工校验，不能作为用户实时播放时的默认链路。

### 推荐定位

- 端侧第一版：只做实时麦克风音高检测和评分。
- 离线辅助工具：后续可单独做“歌曲 -> UltraStar `.txt` / MIDI”的实验工具，用 librosa / aubio / Essentia 验证。
- 产品默认体验：有音符轨则评分；无音符轨则自由唱，不等待离线分析完成。

### AutoTranscriber 评估

`GuyueHermit/AutoTranscriber` 的定位是“输入音频，输出 MIDI 乐谱文件”，从方向上和 K歌参考音符轨生成有关，但不适合作为手机/TV 第一版能力直接集成。

有参考价值的部分：

- CQT 频谱分析：适合把音乐频率映射到半音尺度，便于音符检测。
- 多音高估计：通过谐波减法从频谱峰值中提取多个候选音符，可作为离线和弦/伴奏转 MIDI 的实验参考。
- 人声单音处理：使用 pYIN 思路提取人声旋律，适合离线生成旋律草稿。
- 音符追踪：把逐帧音高合并成 note-on / note-off 事件，包含最小音符过滤、相邻音符合并、振幅衰减判断等后处理。
- MIDI 输出：可借鉴“音符事件 -> MIDI”的数据流，再进一步转换为 UltraStar 音符轨。

不适合直接采用的部分：

- README 明确包含 Demucs 音源分离；这是深度模型链路，和当前“不考虑大模型/高性能消耗”的要求不符。
- 文件结构中还有 `crepe_wrapper.py`；CREPE 属于神经网络音高检测，也不适合作为端侧默认方案。
- 依赖 `librosa`、`scipy`、`pretty_midi`、`soundfile` 等 Python 生态，更适合桌面/服务端/开发工具，不适合直接塞进 Android 手机和 TV。
- 仓库未声明许可证，不能直接拷代码进入主项目。
- 项目规模和活跃度较小，不能作为稳定产品依赖。

建议结论：

- 不进入 WebHTV App 主链路。
- 可以作为后续“外部制谱工具 / 自托管模式”的参考之一。
- 如果后续做工具，应借鉴它的 CQT、pYIN、音符追踪、MIDI 输出思路，但避开 Demucs / CREPE 等模型链路。
- 对 WebHTV 端侧 K歌，仍然优先实现 `AudioRecord + YIN/MPM + UltraStar/MIDI参考音符轨 + 轻量评分`。

### 第三份 AI 回复评估：项目清单过滤

这份回复有参考价值，但它把“实时 K歌评分”“音频转 MIDI / 制谱”“唱歌声音检测”“泛音频特征提取”混在一起了。对 WebHTV 来说，不能按项目数量做判断，必须按是否能在手机/TV 端低成本落地来筛选。

本轮核验到的关键项目：

| 项目 | 核验结论 | 对 WebHTV 的处理 |
| --- | --- | --- |
| `spotify/basic-pitch` | Apache-2.0，Python，音频转 MIDI，支持 pitch bend；质量和活跃度高，但属于机器学习转录工具 | 可作为外部制谱 / 自托管工具候选，不进入 App 默认链路 |
| `Asvarox/allkaraoke` | 浏览器在线 K歌，TypeScript，SingStar / UltraStar 方向，包含 pitch detection 和评分流程 | 继续作为无谱娱乐评分、有谱评分 UI 和多人/房间体验参考；许可证不明，不能直接拷代码 |
| `cwilso/PitchDetect` | MIT，Web Audio autocorrelation pitch detection，代码小而经典 | 可借鉴 autocorrelation / ACF 思路；Android 端仍优先自实现或使用 Java/Kotlin 音高检测 |
| `peterkhayes/pitchfinder` | JavaScript/TypeScript，多种 pitch detection 算法集合；许可证未明确 | 只作为算法对照和调参参考，不作为依赖 |
| `aubio/aubio` | C/GPL-3.0，成熟 pitch/onset/tempo 库 | 未来可评估 JNI 接入；当前 Java `AudioRecord + YIN` 已能满足第一版 |
| `MTG/essentia` | C++/Python，AGPL-3.0，Melodia / pYIN 等 MIR 能力强 | 适合离线工具或研究；AGPL 和集成成本使其不适合直接进主 App |
| `libAudioFlux/audioFlux` | C/MIT，音频特征、频谱、pitch 等能力，较轻 | 可作为未来 native 音频分析备选；第一版不引入新 native 依赖 |
| `vadymmarkov/Beethoven` | Swift/iOS pitch detection | 可了解 UI/调音器体验，不适合 Android 直接复用 |
| `TuneNN/TuneNN` | transformer-based pitch detection | 属于模型链路，不进入端侧默认实现 |
| `keums/melodyExtraction_JDC` | CRNN 唱歌旋律提取研究项目 | 属于深度学习研究，不进入端侧默认实现 |

可采纳的部分：

- `YIN / MPM / autocorrelation` 仍是端侧实时麦克风音高检测的主路线，适合低延迟、低算力、可解释评分。
- `allkaraoke / UltraStar / USDX / Vocaluxe` 证明“音符轨 + 实时 pitch + 半音容差”的评分模型足够轻，适合家庭娱乐。
- `basic-pitch / librosa / Essentia / aubio / audioFlux / pretty_midi` 可进入“外部制谱工具”池，用于生成 MIDI 或 UltraStar 草稿。
- `PitchDetect / pitchfinder / Beethoven` 这类项目可帮助对比 pitch detector 的稳定性、噪声门限和 UI 反馈，不作为直接依赖。

需要过滤掉的部分：

- `Sonic Pi` 主要是音乐编程/教学环境，不解决 K歌评分谱来源问题。
- `PyAudio` 是 Python 实时音频 I/O，不适合 Android App 内置。
- `TuneNN`、`melodyExtraction_JDC`、`SingingVoiceDetection`、深伪检测、情绪识别、音乐类型识别等项目与“家庭 K歌评分”关系弱，不能因为命中搜索关键词就纳入路线。
- `basic-pitch` 虽然名字里有 lightweight，但它仍是音频转录模型/工具，不等于可在 TV 播放时实时跑。
- 任何需要 Demucs、Whisper、CREPE、PyTorch、TensorFlow 模型下载的链路，都不进入默认手机/TV 端。

落地结论：

1. 端侧继续优先完成 `AudioRecord + YIN/MPM + 歌词窗口/UltraStar音符轨 + 轻量评分`。
2. 在线谱源继续围绕 UltraStar `.txt`、USDB、UltraStar-ES、用户 Cookie / URL 导入，不把普通歌词 API 当评分谱源。
3. 制谱能力如果要做，应做成独立工具或自托管服务，优先试 `basic-pitch`、`librosa + aubio`、`audioFlux`、`pretty_midi` 这类可离线运行的链路。
4. App 内第一版不引入 Python runtime、新 native DSP 库或模型推理；只有当现有 YIN 稳定性不足时，才评估 `aubio` / `audioFlux` 的 JNI 方案。

## 技术路线

### 核心数据链路

```text
播放器位置 / 歌曲信息
        |
        v
歌词数据 LyricsDocument  ----->  KTV歌词显示
        |
        +---- 无 NoteTrack ---->  自由唱娱乐评分
        |
        +---- 有 NoteTrack ---->  当前目标音符
        |
        v
麦克风输入 AudioRecord  ----->  PitchDetector  ----->  当前用户音高
        |
        v
KaraokeScorer  ----->  娱乐跟唱分 / 音准命中率 / 稳定度 / 评级
```

### 端侧推荐实现

| 模块 | 推荐实现 | 说明 |
| --- | --- | --- |
| 麦克风采集 | Android `AudioRecord` | 手机和 TV 都可用，注意权限和音频焦点 |
| 音高检测 | TarsosDSP YIN / MPM，或自实现 YIN | 本仓库 GPL-3.0，TarsosDSP GPL-3.0，许可证可兼容 |
| 音高平滑 | 中值滤波 + 置信度过滤 + 音量门限 | 避免噪音触发假音高 |
| 参考音符轨 | UltraStar `.txt` 优先，MIDI 次之 | UltraStar 格式最贴合 K歌 |
| 无谱评分 | 歌词时间窗 + 音量/节奏/音高稳定度 | 给“娱乐跟唱分”，不判断目标音准 |
| 有谱评分 | 半音距离 + 时间片命中率 | 有 UltraStar / MIDI 时才显示音准命中 |
| 延迟校准 | 全局麦克风延迟偏移 | TV/蓝牙麦克风场景必须支持 |
| UI | 手机/TV 分 View，共用状态 | TV 全屏、手机可竖屏/横屏适配 |

### 不推荐端侧第一版使用

| 方案 | 原因 |
| --- | --- |
| 实时人声分离 | 算力、延迟、包体和稳定性都不适合第一版 |
| 实时歌曲自动扒谱 | 准确率不稳定，复杂伴奏下体验不可控 |
| Python 音频库嵌入 | Android 打包和性能成本高 |
| 大模型评分 | 用户明确不需要，且端侧太重 |

## 功能设计

### K歌模式入口

- 音频播放页增加“K歌模式”开关。
- 播放设置中增加默认行为：
  - 自动进入 K歌模式。
  - 默认开启麦克风。
  - 歌词偏移。
  - 麦克风延迟校准。
  - 是否显示评分。
- 仅对音频内容默认展示，视频内容不主动干扰。

### 自由唱模式

无参考音符轨时启用。

能力：

- 大号歌词显示。
- 当前行 / 下一行。
- 逐字歌词高亮复用现有 `LyricWord` 能力。
- 麦克风音量条。
- 当前音高 / 音名显示。
- 娱乐跟唱分：
  - 演唱覆盖率：有声输入时长 / 歌词有效时长。
  - 节奏参与度：起唱、停唱是否落在当前行/逐字时间窗附近。
  - 音量稳定度：音量是否持续、不过低、不过载。
  - 音高稳定度：有声片段内音高是否可检测、抖动是否过大。
  - 歌句完成度：每句歌词是否至少有足够比例的有效演唱输入。

建议初始权重：

```text
娱乐跟唱分 = 演唱覆盖率 35
          + 节奏参与度 25
          + 音量稳定度 20
          + 音高稳定度 15
          + 歌句完成度 5
```

实现细节：

- 有逐字歌词时优先用 `LyricWord` 的 start/end 作为节奏窗口。
- 只有行级 LRC 时用当前行 start 到下一行 start 作为节奏窗口。
- 无同步歌词时只显示实时麦克风反馈，不给高置信娱乐分。
- 播放暂停、拖动进度、切歌后要重置或冻结当前评分，避免噪声被计入。
- 可参考 Frank Karaoke 的 warmup 思路：播放开始或 seek 后 2-5 秒只校准环境底噪，不累计评分。

限制：

- 不显示“音准命中率”。
- 不做专业评分。
- UI 上应明确是“自由唱”或“娱乐跟唱分”，避免用户理解为专业音准评分。

### 评分模式

存在参考音符轨时启用。

能力：

- 显示目标音符条。
- 显示用户实时音高线。
- 显示当前得分、命中率、连击、评级。
- 支持难度：
  - 轻松：±2 半音。
  - 标准：±1.5 半音。
  - 严格：±1 半音。
- 支持忽略八度差，默认开启。
- 支持麦克风延迟偏移。

评分建议：

```text
每个时间片：
  如果麦克风音量低于门限：不命中
  如果当前没有目标音符：不计分
  用户频率 -> MIDI 半音
  用户半音按八度折叠到目标音符附近
  abs(用户半音 - 目标半音) <= 容差：命中

最终分：
  命中目标音符时长 / 目标音符总时长 * 100
```

后续可增加：

- 长音稳定度加分。
- 连击加分。
- 金色音符加权。
- 歌句完成度奖励。

### TV 家庭 K歌模式

TV 端存在三个现实问题：

- 部分电视/盒子没有麦克风。
- 蓝牙麦克风输入链路不统一。
- TV 端输入延迟可能明显。

推荐分两步：

1. TV 本机麦克风模式：如果系统能提供 `AudioRecord` 输入，则直接使用。
2. 手机麦克风模式：手机采集音量/音高，局域网发送到 TV，TV 端负责显示和评分。

手机当麦克风时，建议传输“分析结果”而不是原始音频：

```json
{
  "timestampMs": 123456,
  "frequencyHz": 440.0,
  "midi": 69,
  "volume": 0.72,
  "confidence": 0.91
}
```

优点：

- 网络带宽低。
- 隐私风险低。
- TV 端不需要实时处理原始音频。
- 手机麦克风质量和权限更可控。

## 数据格式

### UltraStar `.txt`

优先支持。

典型字段：

```text
#TITLE:Song Title
#ARTIST:Artist
#MP3:song.mp3
#BPM:300
#GAP:1234
: 0 4 60 Ly
: 4 4 62 ric
- 8
* 8 4 64 gold
F 12 4 60 free
E
```

需要解析：

- `#TITLE`
- `#ARTIST`
- `#BPM`
- `#GAP`
- `:` 普通音符
- `*` 金色音符
- `F` 自由音符
- `-` 换行
- `E` 结束

时间换算：

```text
noteStartMs = GAP + beatToMs(startBeat)
noteEndMs = noteStartMs + beatToMs(lengthBeat)
```

### MIDI

可作为第二优先级。

优点：

- 通用。
- 可由外部工具生成。

缺点：

- 不包含歌词或歌词对齐不稳定。
- 多轨 MIDI 中哪个轨道是主旋律需要选择。

建议：

- 第一版只支持单轨或用户指定主旋律轨。
- 不做复杂自动识别。

### 逐字歌词

用于显示，不用于评分。

可复用现有来源：

- Kuwo `<start,duration>` token。
- QQMusic QRC。
- NetEase YRC。
- TTML。
- 本地 `.lrc` / `.ttml`。

## 与现有歌词功能的关系

现有歌词功能是 K歌模式的基础，但不是完整 K歌评分。

依赖项：

- 自动歌词匹配。
- 逐字歌词解析。
- 歌词偏移。
- 本地/缓存/网络 Provider 优先级。
- 手机非全屏、手机全屏、TV 全屏共用歌词状态。

需要新增：

- 音符轨模型 `NoteTrack`。
- 音符轨解析器 `UltraStarParser` / `MidiParser`。
- 实时麦克风采集 `MicRecorder`。
- 音高检测 `PitchDetector`。
- K歌状态管理 `KaraokeSession`。
- 评分器 `KaraokeScorer`。
- KTV UI 覆盖层。

## 分阶段实施计划

### 第一阶段：K歌显示与麦克风检测（已基本完成，待体验验证）

目标：用户能进入 K歌模式，看到大号歌词和实时麦克风反馈。

任务：

- 已完成：K歌模式入口和播放设置开关。
- 已完成：麦克风权限申请。
- 已完成：`AudioRecord` 采集。
- 已完成：YIN 音高检测。
- 已完成：实时音量可视化、频率、音名。
- 已完成：实时分数/演唱覆盖条，帮助用户区分“唱了多少”和“得分多少”。
- 已完成：复用现有歌词显示当前行 / 下一行 / 逐字高亮。
- 已完成：麦克风延迟偏移设置。
- 待验证：不同手机、平板、TV 盒子上的麦克风可用性和延迟表现。

验收：

- 手机端音频播放时可开启 K歌模式。
- TV 端不崩溃，无法录音时给出明确状态。
- 麦克风输入有稳定音高反馈。
- 无歌词或无麦克风时可降级。

### 第二阶段：无音符轨娱乐跟唱评分（已完成基础版，待体验验证）

目标：没有 UltraStar / MIDI 时也有完整 K歌体验，且不误导用户这是音准评分。

任务：

- 已完成：`KaraokeFreeSingScorer` 无谱分支。
- 已完成：用歌词时间窗统计娱乐跟唱分，覆盖音量、音高稳定度、歌句参与度等轻量指标。
- 已完成：有逐字歌词时使用 word window；只有行级歌词时使用 line window；无同步歌词时不给高置信娱乐分。
- 已完成：播放开始、恢复播放、seek 后使用 warmup / 大跳变保护，不累计异常片段。
- 已完成：播放结束或退出 K歌时显示结果弹窗，包含娱乐分、等级、逐句统计、演唱覆盖和最长连击，零分也显示。
- 已完成：结果弹窗改为可视化面板，分数、等级、演唱覆盖、有效时长、连击和逐句统计更适合手机/TV 快速阅读；无评分谱时明确提示这是自由唱娱乐估算。
- 已完成：UI 文案明确显示“自由唱 / 娱乐跟唱分”，不显示“音准命中率”。
- 待验证：真实家庭环境里的电视扬声器串音、手机麦克风距离、环境噪声对娱乐分的影响。

验收：

- 没有音符轨的普通音频也能看到实时麦克风反馈和娱乐分。
- 静音、说话、噪声不会轻易拿高分。
- 正常跟随歌词持续演唱时分数明显更高。
- seek、暂停、后台切换不会造成分数跳变。

### 第三阶段：UltraStar 音符轨与音准评分（已有基础，继续完善）

目标：有参考音符轨时提供可用评分。

任务：

- 已完成：UltraStar `.txt` parser。
- 已完成：同目录旁挂匹配 `歌曲名.ultrastar.txt`、`歌曲名.karaoke.txt`、`歌曲名.txt`。
- 已完成：`KaraokeTrack` / `KaraokeNote` 数据模型。
- 已完成：当前播放位置匹配当前目标音符。
- 已完成：半音容差评分、忽略八度、难度选项。
- 已完成：实时 UI 显示得分、当前音高、命中/偏差、目标音/当前音偏差条、目标音符时间线、用户音高历史曲线、麦克风音量组件。
- 已完成：结束或退出 K歌时显示结果弹窗，零分也显示，并补充等级、逐句统计、演唱覆盖和最长连击。
- 已完成：结果弹窗改为统一可视化面板，手机端和电视端展示一致。
- 已完成：普通音符、金色音符、自由音符、Rap 音符的基础权重和评分处理。
- 已完成：文件选择器 / URL 导入 UltraStar `.txt` 并缓存到歌曲 key。
- 已完成：MIDI / KAR 主旋律轨导入启发式。
- 已完成：实时状态浮层显示当前音高、命中状态、当前句分数、目标音偏差、目标音符时间线、用户音高历史曲线、麦克风音量、分数/覆盖条和连续命中/连续发声提示。
- 已完成：按分数给出 S+ / S / A / B / C / D / E 娱乐评级。
- 已完成：目标音符条支持宽屏自适应、当前音符高亮、命中/偏差即时颜色、金色音符光晕提示。

验收：

- 使用带 UltraStar 文件的歌曲可以看到目标音符条。
- 正常跟唱时分数上升。
- 静音或乱唱时分数明显降低。
- 可通过偏移设置校准麦克风延迟。

### 第四阶段：音符轨来源扩展

目标：尽量提高“有谱评分”的可用率，但不依赖不稳定爬虫。

任务：

- 已完成：本地 UltraStar `.txt` 导入。
- 已完成：长按播放页“K歌模式”进入“评分谱”管理，导入后按当前播放项缓存绑定。
- 已完成：URL 导入，允许用户粘贴直接返回 UltraStar `.txt` 的链接。
- 已完成：UltraStar 文件有效性检测，解析失败回退自由唱。
- 已完成：MIDI / KAR 导入与主旋律轨选择启发式。
- 已完成：GitHub 公开 UltraStar 谱库搜索，当前接入 `razzertronic/usdx-songs`、`Vasil-Pahomov/UltraStarSongs`。
- 已完成：UltraStar-ES 匿名搜索，下载仍取决于用户登录态或手动导入。
- 已完成：USDB ID/详情页通过 `view.php` 重建；关键词搜索在用户已有登录 Cookie 时走网页 POST 搜索，否则回退 RSS/ID。
- 已完成：从当前歌词时间轴生成轻量节奏评分谱，作为无人工谱时的可用补位方案。
- 已完成：正式抽象 `KaraokeTrackProvider` 接口，把 GitHub/UltraStar-ES/USDB 搜索从仓库类里拆出去，便于后续增减谱源。
- 已完成：播放页“评分谱”菜单增加自定义 GitHub 谱源配置，每行支持 `owner/repo@branch` 或 GitHub 仓库 URL。

验收：

- 用户能给当前歌曲手动绑定 UltraStar 文件。
- 绑定后再次播放直接进入音准评分。
- 绑定文件失效或格式错误时回退自由唱，不影响播放。

### 第五阶段：TV 家庭模式

任务：

- TV 全屏 KTV UI。
- 遥控器操作：开始/暂停、重唱、切歌、显示/隐藏评分。
- 手机作为麦克风的局域网协议设计。
- 手机端采集音高/音量并发送到 TV。
- TV 端接收手机音高数据并评分。

验收：

- TV 无麦克风时也能通过手机参与 K歌。
- 手机断开后 TV 能降级为纯歌词模式。
- 局域网延迟可通过偏移校准。

### 第六阶段：外部制谱工具 / 自托管模式（可选，不进 App 默认链路）

目标：解决用户没有 UltraStar 音符轨的问题，但不阻塞端侧体验。

任务：

- 开发独立实验工具或自托管服务，不嵌入主 App 第一版。
- 输入歌曲音频，输出 UltraStar `.txt` 或 MIDI。
- 优先尝试传统轻量算法链路：
  - `librosa` 做预处理、HPSS、CQT。
  - `aubio` / `Essentia pYIN` 提取主旋律。
  - `madmom` / `aubio` 做 onset / offset。
  - `pretty_midi` 或自定义 writer 输出 MIDI / UltraStar。
- 只在外部工具里参考 UltraSinger / Nightingale / UltrastarCreatorTool 的流程。
- 涉及 Demucs / WhisperX / CREPE / PyTorch 的方案不进 Android App；如确实要做，只能作为用户主动部署的桌面/服务器工具。
- 对生成结果做后处理：
  - 半音量化。
  - 短音符合并。
  - 静音过滤。
  - 歌词时间对齐。

### 第六阶段补充：App 内轻量生成（已实现）

已实现一个不依赖重模型、不解码原曲 PCM 的 App 内补位方案：当当前播放项已经有带时间轴的歌词时，用户可在“评分谱”菜单选择“生成轻量节奏评分谱”。

- 输入：当前歌词的逐字时间轴优先；没有逐字时使用逐句时间轴并按文字粗分。
- 输出：UltraStar 文本，音符类型使用 `R` / RAP，目标 pitch 固定但评分不要求音高。
- 评分含义：只衡量歌词窗口内是否有有效人声参与，更接近节奏/覆盖率评分，不是专业音准评分。
- 产品定位：用于“没有人工 UltraStar/MIDI 谱也能玩起来”；如果用户导入真实 UltraStar/MIDI，仍优先使用真实音符轨做音准评分。

验收：

- 对简单人声清晰歌曲能生成可用草稿。
- 生成结果允许用户手动校正。
- 复杂歌曲允许失败，不影响主 App。

## UI/UX 建议

### 手机端

- 非全屏：保持播放器可见，K歌控件不要遮挡核心播放区域。
- 全屏：大号歌词居中，底部显示音高/分数。
- 横屏：接近 TV K歌布局。
- 麦克风状态必须清晰：
  - 未授权。
  - 无输入。
  - 输入过小。
  - 检测中。
  - 正常。

### TV 端

- 第一视觉层是歌词，不是设置面板。
- 音符条和分数靠边显示，不能压住歌词。
- 遥控器操作要少层级：
  - OK：暂停/播放。
  - 上/下：切换歌词/评分信息密度。
  - 菜单：K歌设置。
  - 返回：退出 K歌覆盖层或播放器。
- 设置项：
  - K歌模式。
  - 麦克风来源。
  - 麦克风延迟。
  - 歌词偏移。
  - 评分难度。
  - 忽略八度。

## 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 无参考音符轨 | 无法做音准评分 | 进入自由唱模式，显示娱乐跟唱分 |
| TV 无麦克风 | TV 评分不可用 | 支持手机当麦克风 |
| 蓝牙麦克风延迟 | 音准判断滞后 | 提供延迟校准 |
| 环境噪声 | 音高误检 | 音量门限 + 置信度过滤 |
| 手机麦克风串音 | 播放器声音被当成人声 | warmup 底噪校准、bandpass、音量门限、必要时 pitch oracle 过滤 |
| 男女性别八度差 | 容易误判跑调 | 默认忽略八度差 |
| 复杂歌曲自动扒谱不准 | 评分错误 | 离线工具仅作为草稿 |
| 在线 K歌谱源不稳定 | 频繁失效、合规风险 | 默认不内置爬虫，优先用户导入和本地绑定 |
| GPL 项目代码复用 | 许可证风险 | 借鉴思路，优先自实现核心小模块或使用兼容库 |
| 低端 TV 性能 | UI 卡顿 | 简化绘制，降低刷新频率 |

## 推荐默认策略

1. 默认进入自由唱模式，不强依赖音符轨。
2. 检测到 UltraStar `.txt` 后自动升级为评分模式。
3. 默认忽略八度差。
4. 默认评分容差为 ±2 半音。
5. 无音符轨时只显示“娱乐跟唱分”，不显示“音准命中率”。
6. 默认不启用自动扒谱。
7. TV 端优先引导手机作为麦克风。
8. 所有网络/离线生成能力都不能阻塞播放。

## 后续决策点

需要后续确认：

1. UltraStar `.txt` 文件从哪里导入：
   - 同目录旁挂。
   - 本地文件选择。
   - URL 导入。
   - 后续可配置在线库。
2. 是否支持 MIDI / KAR 导入，以及主旋律轨如何选择。
3. TV 手机麦克风协议先用 HTTP 轮询、WebSocket，还是局域网发现 + WebSocket。
4. 是否做多人评分。
5. 是否做外部制谱工具 / 自托管服务，还是只支持用户已有 UltraStar / MIDI 文件。

## 建议落地顺序

优先做：

1. 已完成：无谱娱乐跟唱分。
2. 已完成：播放结束/退出 K歌结果弹窗。
3. 已完成基础版：评分状态的 seek / 长跳变保护和 warmup。
4. 已完成：UltraStar / MIDI 导入、绑定和缓存。
5. 已完成基础版：有谱评分 UI，包括当前音高、目标音偏差、命中状态、当前句分数、目标音符时间线、用户音高历史曲线、音量、分数/覆盖条、连击提示和评级。
6. 下一步：更完整的大屏目标音符条和金色音符视觉特效。
7. 后续：TV K歌布局和手机当麦克风。

暂缓做：

1. 自动伴奏分离。
2. 自动扒谱。
3. 复杂复调 MIDI 生成。
4. 专业评分。
5. 多人联机。
6. 内置 K歌平台逆向爬虫。

最终建议：先把 WebHTV 做成“能唱、好看、可娱乐评分”的家庭 K歌，而不是一开始追求自动制谱和专业评分。音符轨生成、伴奏提取和复杂节拍分析可以作为独立工具或后续增强，不进入手机/TV 第一版主链路。
