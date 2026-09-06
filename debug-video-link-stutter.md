# [OPEN] video-link-stutter → 2026-09-05 重构修复

## Symptoms（原始）

- 网页视频链接仍无法播放，本地视频可播放
- 屏幕存在频闪
- 视频播放卡顿、帧率低
- 音频卡顿且与画面不同步

## Root Causes（已定位并修复）

### 1. 低帧率 / 频闪：纹理上传被 20Hz 客户端 tick 锁死
- `VideoPlayer.tick()`（纹理上传）之前只挂在 `END_CLIENT_TICK` 上，
  客户端 tick = 20 次/秒 → 画面最多 20fps 更新。30/60fps 源以 20Hz
  双帧跳变呈现 = 明显的频闪/顿挫观感。
- 修复：`ScreenRenderer` 每个渲染帧先调用 `mgr.tick()`（幂等），
  上传节奏 = 渲染帧率（≈ 显示器刷新率）；`END_CLIENT_TICK` 保留为兜底。

### 2. 解码/转换路径 CPU 与 GC 开销高
- 每帧 `Java2DFrameConverter → BufferedImage → getRGB`（整帧 Java 堆分配 +
  复制，GC 压力拖慢渲染线程）。
- 修复：`grabber.setPixelFormat(BGR24)` 后解码线程按 `Frame.imageStride`
  逐行手工转 ABGR，写入预分配的 3 帧槽，主路径零每帧分配；
  异常格式保留 AWT 回退。解码高度限制改为"只缩小不放大"（`scale=-2:min(H,ih)`）。

### 3. 音画不同步：双独立时钟 + 音频 EOF 无限回绕
- 音频、视频各自独立 `FFmpegFrameGrabber`，各自按墙钟/解码速度播放 → 漂移。
- 音频 EOF 处 `grabber.setTimestamp(0)` 无限回绕重播（旧日志确认位置反复
  回到 633/666ms），音频反复、与画面错乱。
- 修复：
  - `AudioPlayer` 成为主时钟：`getPositionMs()` = 声卡实际已播出位置
    （累计写出字节 − 声卡滞留缓冲），每循环拍刷新；
  - 视频帧按 PTS 映射到同一媒体时间轴，`tick()` 只呈现"到期"的最新帧
    （提前量 25ms），未到期的帧排队等待，音频未就绪时画面门控不提前跑；
  - 音频 EOF → 冲完残余数据 → 等声卡缓冲排空 → `finished`，绝不回绕；
    视频 EOF → 保持末帧、等音频播完（排空）再发 STOP，不再掐尾音；
  - 无音频/音频结束后自动无缝切换到墙钟节流；
  - 声卡写入改为"每次只写 available() 允许的量"非阻塞节奏，消除
    单次 `line.write` 阻塞 60-78ms 的问题。

### 4. URL 无法播放（无声/失败）：yt-dlp DASH 分离流只取了一行
- YouTube/B站高清流基本都是 DASH：yt-dlp `-g` 输出两行 URL
  （视频直链 + 音频直链）。旧实现只取第一行 → 音频播放器永远拿不到
  音频流（视频无声），且 B站 `best[acodec!=none][vcodec!=none]...`
  这类格式请求在只有分离流的站点直接报
  `Requested format is not available`。
- 修复：
  - `UrlResolver.resolve()` 改为返回 `ResolvedSource(videoUrl, audioUrl)`：
    单行 → 音视频同 URL；多行 → 首行视频、次行音频；
  - 视频/音频 grabber 分别打开各自直链；
  - 格式选择链重排：封装流优先 → `bestvideo[height<=?1080]+bestaudio`
    合并流 → `best` 兜底；
  - 音频 grabber 补 UA + Referer 头（B站 CDN 校验），与视频一致；
  - B站/YouTube 反爬（412/登录）仍走既有 cookies 配置（cookies.txt 或
    浏览器名），错误提示保留。

### 5. 其它加固
- seek 语义：seek 后解码器从"目标前最近关键帧"出帧，新增 roll 逻辑
  丢弃 PTS < 目标的帧，防止 seek 后短暂音画错位；暂停中也能处理 seek；
- 帧槽状态机（3 槽：EMPTY/DECODING/FILLED/UPLOADING）替换旧双缓冲
  无握手方案，杜绝"解码线程覆写正在上传的缓冲"导致的撕裂；
- 尺寸变化重分配与上传中槽互斥；release() 不再跨线程释放仍在网络读取
  中的 grabber（交给解码线程 finally 释放），避免 native 崩溃。

## 如何验证

1. `gradlew build`（或 debug jar）后进游戏：
   - 本地 1080p/4K 视频：观察画面帧率（应有明显提升，随游戏 FPS）与
     片尾是否自然结束、不掐音；
   - B站 / YouTube 链接：应能出声；若仍 412/登录拦截，按聊天提示配置
     cookies.txt（设置界面）；
   - 快进快退（-/+10s/30s）：跳转后音画应快速对齐；
   - 暂停/恢复多次：不应出现声音继续而画面冻结。
2. `.dbg/trae-debug-log-video-link-stutter.ndjson` 新记录 `runId: sync-rewrite`，
   标记 `B`（视频链路）/`C`（音频，已精简）/`A`（yt-dlp 解析）保留关键事件。

## 已知边界（未处理）

- 解码速度低于实时的极端网络/超高分源：视频会短暂冻结追赶，不会累积漂移
  （帧到期才呈现 + 到期跳帧追赶）；如频繁出现请把屏幕"分辨率"调低（默认 720p 解码）。
- 服务端 5s 校准 + >2s 漂移 seek 逻辑保持不变（跨玩家同步语义）。
