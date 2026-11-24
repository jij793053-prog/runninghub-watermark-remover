# RunningHub Clear Studio · 无损去水印工作台

面向 Windows 的 **RunningHub AI 水印自动去除工具**，提供现代化暗黑桌面控制面板（`pywebview` + Edge WebView2）与命令行批量接口，**100% 本地离线运行**，不上传任何图片或隐私数据。

## 核心特性

- **双引擎自适应去水印**
  - **Mode-A（Alpha 精确逆混合）**：针对 RunningHub 原生导出的 RGBA PNG，自动识别 Alpha 通道水印轮廓并做逐像素数学逆混合还原，保留 100% 原始画布纹理与笔触细节，水印区域外像素零改动。
  - **Mode-B（多尺度亚像素模板匹配）**：针对经社交软件压缩或转为 JPG / WebP / 无 Alpha 通道的图片，自动在四角进行梯度模板匹配、亚像素（`0.1px`）对齐及核心逆混合 / 细笔画自适应修补。
- **现代化可视化桌面面板**
  - 原生拖拽单图、多图或文件夹即可自动排队处理，无需终端黑框。
  - 内置**水印局部特写对比滑块**与**全图对比滑块**，支持 `原图 / 去水印后` 实时拖拽对照。
  - 自动跳过非 RunningHub 水印图片，不覆盖原图（默认生成 `<原图名>_去水印.png`），支持自定义输出目录。
- **纯本地安全隐私**
  - 仅在 `127.0.0.1` 随机本地端口向自身面板提供预览服务，无任何外网请求、遥测或云端上传。

## 环境要求与安装

- Windows 10 / 11（系统内置 Microsoft Edge WebView2 运行时）
- Python 3.10+

```bash
pip install -r requirements.txt
```

## 使用方式

### 1. 桌面面板模式（推荐）

- 直接双击 [`去RunningHub水印.bat`](去RunningHub水印.bat) 启动图形面板（无终端窗口），把图片或文件夹拖进窗口即可自动处理。
- 也可直接将图片或文件夹拖放到 `.bat` 文件上启动并自动加载处理。

### 2. 命令行批量模式

```bash
# 处理单张或多张图片
python remove_rh_watermark.py image1.png image2.jpg

# 处理整个文件夹并指定输出目录
python remove_rh_watermark.py ./input_folder --out-dir ./output_folder
```

## 文件说明

| 文件 | 说明 |
| --- | --- |
| `rh_panel_app.py` | 桌面 GUI 主程序（本地预览服务、队列调度、Win11 暗黑标题栏） |
| `panel_ui.html` | 桌面面板前端界面与交互式前后对比滑块 |
| `remove_rh_watermark.py` | Mode-A / Mode-B 去水印核心算法及命令行入口 |
| `rh_template.png` | 标准 RunningHub AI 水印归一化灰度模板 |
| `去RunningHub水印.bat` | Windows 无黑框一键启动脚本 |
| `app.ico` | 应用图标 |

## 免责声明

本工具仅用于个人已生成或拥有合法版权图像的画质修复与学习研究，请勿用于侵犯他人知识产权或违反平台服务条款的用途。
