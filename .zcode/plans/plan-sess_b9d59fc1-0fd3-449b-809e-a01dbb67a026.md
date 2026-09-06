## 曲面屏功能实现计划（基于已回退的 20:12 干净基线）

### 目标
视频屏幕支持可调曲面：平面 / 水平凸弧 / 水平凹弧 / 双向凸弧(球面) / 双向凹弧，水平弧度与垂直弧度分别调节，用于复刻巨型球式曲面大屏。曲率为**每屏属性**（默认平面 = 走现有代码路径，零开销零回归）；顺带说明超大屏面积限制怎么调。

### 1. 每屏数据（3 个新 int，走现有 9 处同步链）
- `CinemaScreen` 追加：`curvatureType(0平面 1水平凸 2水平凹 3双向凸 4双向凹)`、`curvatureDegX(0..300°)`、`curvatureDegY(0..150°)`，默认全 0。
- 必改锚点（探明）：CinemaScreen record 与 3 个 legacy 构造器补默认 0、`withSettings`/`withAudioSettings` 透传、新增 `withCurvatureSettings`（clamp）、STREAM_CODEC 增 3 个 varInt 读写；UpdateScreenSettingsPayload 增 3 项；ScreenManager.updateSettings 签名+确认消息+save/load 的 NBT（"curvType/curvX/curvY"，缺省 0=平面 向后兼容）；NetworkHandlers.handleSettingsUpdate 透传 3 项；ScreenControlScreen.sendSettings 与 ScreenSoundSettingsScreen.sendAudioSettings 两个 payload 构造点补字段。旧存档自动按 0（平面）兼容。
- 参数由服务端持久化并随屏幕同步广播，调整即时生效（无需重启）。

### 2. GUI（屏幕控制页，亮度/分辨率那组之后加行）
- 曲率类型：循环按钮（平面→水平凸弧→水平凹弧→双向凸弧→双向凹弧）。
- 水平弧度：[-] 值 [+] 行（步长 15°，0..300°）；垂直弧度同理（0..150°）。平面时两行显示但不生效，附一行黄色提示说明。

### 3. 几何渲染（ScreenQuad，平面路径原样保留）
- `computeVerts` 平面 4 角逻辑不变；当 `curvatureType>0 && 对应弧度>0` 时进入网格路径：
  - 从（缩放后的）平面四角计算和弦 W/H → 以屏幕为参照把平面网格化：cols≈ceil(W/2)、rows≈ceil(H/2)，上限 120×64；
  - 法线方向偏移 = sagX(u)+sagY(v)（可分离双向弯曲，两轴半径由各自弦长与弧度推导，边缘落在原平面角点上、中心外凸/内凹），凸/凹由类型符号决定；
  - 每格双面提交（沿用现有 26.2 submitCustomGeometry + ENTITY_SOLID 不透明管线 + 全亮度 uv2(240,240)），UV 按格线性映射；现有 Z-fight 相机外推对网格逐顶点统一平移。
- 黑色背板（无帧时）与纯色调试面板在曲面屏上也走同一网格，避免出现"平面黑底+曲面画面"。
- 默认平面屏完全走旧路径（本次改动对现有屏幕零影响）。

### 4. 超大屏限制说明
- 创建屏幕面积上限默认 1024 格²（ScreenManager.create 校验处读取 `ServerConfig.maxScreenArea`）。巨型球屏请把服务器 `config/cinemaforyou-server.json` 的 `maxScreenArea` 调大（如 10000）后重启生效；本次会顺带确认没有别处用写死的 32×32 常量拦截（若有则改为读配置）。

### 5. 不做
- 不做曲面上的逐块贴墙吸附/自动匹配球体半径的"吸附工具"（你先手动调弧度，观感不满意再迭代）。
- 不加全局默认/0=跟随语义（你已选仅每屏）。

### 测试协议
1. 单机建一面 10×7 左右的屏播放视频 → 控制页把类型切到"水平凸弧"、弧度 60°→90°→120°，确认画面像弧幕一样弯过去且内容不变形；
2. 双向凸弧 + 水平 120° 垂直 45° 看"枕头屏/球面前脸"效果；内凹类型确认方向相反；
3. 巨型球场景：把 `maxScreenArea` 调大后建大屏微调弧度，反馈是否需要"按半径(格)直接设"的补充模式；
4. 全程确认平面屏（默认）外观与之前完全一致。

### 产物
修改文件：CinemaScreen、UpdateScreenSettingsPayload、NetworkHandlers、ScreenManager、ScreenControlScreen、ScreenSoundSettingsScreen、ScreenQuad（+ScreenManager 面积常量检查）；打包 `gradlew.bat build`（JAVA_HOME=jdk-26.0.1），双端替换 jar 后测试。