# 🍎 牛逼笔记本 · iOS 客户端（Swift/SwiftUI）

## ⚠️ 重要前提

**iOS 应用只能在 Mac 上用 Xcode 编译**，Windows 无法生成 .ipa 安装包。
本目录是完整可编译源码，你在 Mac 上按下面步骤 3 分钟即可构建。

## 构建步骤（Mac）

1. 安装 [Xcode](https://apps.apple.com/cn/app/xcode/id497799835)（App Store，需 macOS 12+）
2. 打开 Xcode → `File → New → Project → iOS → App`
   - Product Name：`NBNotebook`，Interface：**SwiftUI**，Language：**Swift**
3. 把本目录 `NBNotebook/` 下的 **NBNotebookApp.swift** 覆盖到工程同名文件，
   把 **Info.plist** 的键值合并进工程的 Info（或在 Target → Info 里添加：
   `NSAppTransportSecurity → NSAllowsArbitraryLoads = YES`）
4. 连上你的 iPhone（需开启开发者模式），选设备 → ⌘R 运行

> 没有 Mac？**不用等 Swift**，iPhone 现在就能用网页版：
> Safari 打开 `http://192.168.0.2:8080` → 点分享 → **添加到主屏幕**，
> 图标会出现在桌面，全屏运行、体验和 App 一样，还能直接系统打印/分享。

## 功能

- 首次打开输入电脑的局域网 IP + 端口（和安卓版一致）
- 记住地址，自动连接；加载失败自动回到设置页
- 网页全功能可用：AI 分类、笔记编辑、卷子提图、批量打印

## 打印说明（iOS 限制）

WKWebView 不支持 JS 的 `window.print()`。网页里的打印按钮在 iOS 原生壳内可能无反应，
推荐两种方式：
1. **主屏幕 PWA 方式**（推荐）：Safari 渲染，打印按钮会弹出系统打印面板，可存 PDF
2. 原生壳内：用系统分享 → 打印整页（长按页面 → 打印）

## 目录

```
ios/
├── README.md
└── NBNotebook/
    ├── NBNotebookApp.swift   # 全部源码（设置页 + WebView 容器）
    └── Info.plist            # 允许局域网 http
```
