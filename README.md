# 声纹透镜 SoundLens

一个"会成长"的 Android 环境声音训练器：**采集 → 标注 → 训练 → 识别**，全程在手机端完成，无需联网、无需服务器，标注越多识别越准。

## 功能

| 页面 | 功能 |
|---|---|
| 采集 | 后台前台服务持续录制约 1 秒/片的音频样本，可先选好标签实现"边采边标" |
| 标注 | 管理声音类别标签；试听片段、补打标签、删除无效样本 |
| 训练 | 用已标注样本在手机上训练分类器（YAMNet 嵌入 + Softmax 回归），显示训练准确率 |
| 识别 | 持续监听环境声音，实时显示 Top-3 识别结果与置信度；随时用新样本重新训练，模型不断进化 |

**技术路线**：内置 Google YAMNet（TFLite，4MB）提取 1024 维声音特征 → 纯 Kotlin 实现的在线 Softmax 分类器端侧训练 → 权重持久化到本地，重启 app 不丢失模型。

## 隐私说明

- 麦克风采集以 Android 前台服务运行，通知栏会**常驻显示**"声音采集运行中"（系统强制要求，也是隐私透明的体现）。
- 所有音频与模型数据保存在 app 私有目录，不上传任何服务器。

## 云端编译 APK（本地无需任何 Android 环境）

1. 在 GitHub 新建一个仓库（如 `SoundLens`），把本目录全部文件推送上去：

   ```bash
   cd SoundLens
   git init
   git add .
   git commit -m "SoundLens v1.0"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/SoundLens.git
   git push -u origin main
   ```

2. 打开 GitHub 仓库页面 → **Actions** 标签 → 等待 "Build APK" 工作流跑完（首次约 5-15 分钟）。
3. 在该次运行详情页底部的 **Artifacts** 下载 `SoundLens-debug-APK`（zip 解压后即 `app-debug.apk`）。
4. 把 APK 传到手机安装（需允许"安装未知来源应用"）。

> 打上 `v1.0` 这样的版本 tag 推送时，还会自动创建 GitHub Release，直接下载 APK 附件。

## 使用流程建议

1. 【标注】页先创建 2-5 个标签（如：键盘声、说话、风扇、安静）。
2. 【采集】页选择标签 → 开始采集，让每种声音各收集 20-60 条样本（每条约 1 秒）。
3. 【标注】页试听并修正错误标签，删掉无效片段。
4. 【训练】页点"开始训练"（几秒完成），查看训练准确率。
5. 【识别】页开始实时识别。
6. 发现识别不准 → 回【采集】页继续采新样本 → 重新训练。**循环下去，识别越来越准。**

## 目录结构

```
SoundLens/
├── app/
│   ├── src/main/
│   │   ├── assets/yamnet.tflite        # 内置 YAMNet 特征提取模型
│   │   ├── java/com/soundlens/app/
│   │   │   ├── audio/                  # 前台采集服务 + WAV 读写
│   │   │   ├── data/Db.kt              # SQLite 存储（标签/片段）
│   │   │   ├── ml/                     # 嵌入提取 / 分类器 / 训练器
│   │   │   └── MainActivity.kt         # Compose 四页 UI
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/build.yml         # 云端自动编译 APK
└── build.gradle.kts / settings.gradle.kts
```

## 环境要求

- Android 8.0+（minSdk 26），目标 Android 14
- 如需本地构建：JDK 17 + Android SDK（AGP 8.5.2 / Gradle 8.7）
