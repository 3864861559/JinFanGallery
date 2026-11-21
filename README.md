# 金凡图库（JinFanGallery）

运行在 AOSP 上的极简轻量图库，原生 View 实现，无 Flutter / Compose / 重型图片框架，
内存占用对齐 AOSP 原生 Gallery 水平。

## 一、功能清单

| 模块 | 实现 |
| --- | --- |
| 激活引导页 | 「欢迎使用金凡图库」+ 蓝色实心圆 + 白色箭头，点击后缩放/摆动反馈，进入开屏页 |
| 开屏页 | 指定照片固定展示 **0.3 秒**，无转场进入图库主屏 |
| 图库主屏 | 日期分组网格（3 列），MediaStore 异步读取，系统缩略图 |
| 凝光悬浮底栏 | OPPO 凝光视效：半透明亚克力基底 + 左上角 45° 斜向柔光，悬浮于内容之上 |
| 点击反馈 | 图标与文字随父容器整体轻微放大 1.05x，原生 StateListAnimator |
| 长按预览 | 3D Touch 式放大，跟手拖动；抬起后 ColorOS 抛物线归位 |
| 深色模式 | `values-night` 资源自动切换，玻璃底色 / 高光 / 文字跟随日夜模式 |
| 高刷 | 窗口优先申请 120Hz，设备不支持自动回退原生刷新率 |

## 二、启动流程

```
ActivationActivity  ──点击箭头──▶  SplashActivity(300ms)  ──▶  MainGalleryActivity
```

## 三、目录结构

```
JinFanGallery/
├── AndroidManifest.xml
├── Android.bp                     # AOSP 内置编译配置
├── build.gradle                   # 可选：Android Studio 编译配置
├── settings.gradle
├── res/
│   ├── drawable/splash_bg.webp   # 开屏照片（替换此文件即可换图）
│   ├── layout/                   # 激活页 / 开屏页 / 主屏 / 列表项
│   ├── animator/item_press.xml   # 点击缩放状态动画
│   ├── values/                   # 浅色资源
│   └── values-night/             # 深色资源
└── src/com/jinfan/gallery/
    ├── ActivationActivity.java    # 激活引导页
    ├── SplashActivity.java        # 0.3s 开屏
    ├── MainGalleryActivity.java   # 主屏、权限、Loader、高刷与窗口模糊
    ├── GalleryAdapter.java        # 日期分组网格适配器
    ├── MediaStoreHelper.java       # 元数据查询 + 轻量缩略图加载器
    ├── NingGuangFloatingBar.java   # 凝光悬浮底栏
    └── PreviewHelper.java          # 3D Touch 预览 + 抛物线
```

## 四、编译方式

### 方式 A：AOSP 内置（系统应用）

1. 将整个 `JinFanGallery/` 放入源码树 `packages/apps/JinFanGallery/`；
2. `Android.bp` 已配置 `platform_apis: true`、平台签名；
3. RecyclerView 来自 androidx / support 库，按源码树实际名称在 `Android.bp` 的
   `libs`（或 `static_libs`）中补充 recyclerview 依赖，例如：

   ```bp
   libs: ["androidx.recyclerview_recyclerview"],
   ```

4. 编译：

   ```bash
   mm JinFanGallery
   ```

> 固件需开启 `ro.surface_flinger.supports_background_blur=1` 才能启用系统级背景模糊；
> 未开启时底栏自动降级为半透明纯色，不崩溃、不做软件模糊。

### 方式 B：Android Studio

根目录已附 `build.gradle` / `settings.gradle`，用 Android Studio 打开本目录即可，
依赖仅 `androidx.recyclerview:recyclerview`。

## 五、低内存 / 高性能策略

- **不引入图片框架**：缩略图走 `MediaStore.Images.Thumbnails`，后台单线程加载，
  `LruCache` 按 Bitmap 字节数限量缓存，不做磁盘缓存；
- **不截全屏做模糊**：背景模糊优先交给 SurfaceFlinger 硬件合成；老版本直接半透明纯色，
  全程不生成离屏模糊 Bitmap；
- **动画走 RenderNode**：缩放 / 位移 / 透明度全部属性动画，修改变换矩阵，不触发
  `onDraw` 重绘；Shader / Paint 在 View 构造或尺寸变化时复用，不在绘制时新建对象；
- **Loader 托管游标**：`AsyncTaskLoader` 异步查询，页面退出立即释放游标、缩略图缓存
  与 Worker 线程；
- **过度绘制控制**：缩略图圆角使用 Outline 硬件剪裁，不用 Bitmap 遮罩；预览层仅一层
  纯色压暗；
- **无后台服务、无定时扫描、无埋点统计**。

## 六、资源替换

- 更换开屏照片：替换 `res/drawable/splash_bg.webp`（保持同名，或同步修改
  `activity_splash.xml` 中的 `android:src`）；
- 底栏图标：替换 `ic_photo.xml` / `ic_album.xml` Vector 图；
- 凝光底色与高光透明度：见 `colors.xml` 与 `NingGuangFloatingBar#rebuildGradientIfNeeded()`。

## 七、已知约束

- 仅展示图片，未包含视频相册、图片编辑、分享、收藏等非必要功能；
- 长按预览复用网格缩略图分辨率，不加载原图；进入单图预览页时再按需解码；
- 120Hz 最终由屏幕硬件、显示驱动与省电策略决定，App 仅提交偏好，不能强制锁帧。
