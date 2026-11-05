# MONOPAN 50 / MONOPAN 50 迫冲（莱卡一瞬 M3 移植）

Opus 1.3.0 相对 1.2.0 只多了这一项功能。本文记录逆向得到的事实、本模块的实现方式，以及**还没做完的部分**。

## 一、Opus 1.3 到底加了什么

`OmniCam-Opus-Release-1.3.0-KSU.zip` 与 1.2.0 的差异（逐字节比对）：

| 文件 | 大小 | 作用 |
| --- | --- | --- |
| `payload/meishe_lut/JG_MONOPAN50.cube.rgb.bin` | 98480 | 滤镜类型用的 LUT |
| `payload/meishe_lut/JG_MONOPAN50PRO.cube.rgb.bin` | 98480 | 同上 |
| `payload/monopan/leica_filter_param_m3.bin` | 93170 | M3 暗角/Vig 参数表 |
| `payload/monopan/libmialgo_monopan.so` | 18312840 | 厂商颗粒库（闭源） |
| `payload/monopan/libmpancxx_sh.so` | 9236352 | 上面库的依赖 |
| `payload/monopan/mialgo_monopan_cl.bin` | 79858 | 厂商 OpenCL 内核 |
| `payload/monopan/monopan_helper` | 201080 | 作业进程（本工程源码 `app/src/main/cpp/monopan_helper.cpp`） |

`service.sh` 负责：把 `leica_filter_param_m3.bin` 放进 `files/jiege/monopan/`，把 helper 与三个库拷到 `/data/local/tmp/monopan`（`/data/adb` 下的程序不允许解析厂商 OpenCL），然后 `LD_LIBRARY_PATH=... nohup monopan_helper <libdir> <jobdir> &`。

## 二、关键结论：两张 LUT 是恒等表

两张 LUT **逐字节相同**（md5 `98495fe1b4268bfecf37a6f0c3f1bc0d`），且解出来的 32³ 表是**恒等映射**（`out.R` 只随 `in.R` 变化，中性灰原样通过，纯色通道各自原样通过）。
也就是说：它们只负责在相机里**注册滤镜类型**，颜色/观感一点没给。真正的画面全在 native 后处理里。

因此“把两个 bin 拷进 payload 就完事”是行不通的——滤镜会出现在列表里，但按下后画面不变。

## 三、后处理引擎（逆向结果）

### 3.1 helper 的作业协议

`monopan_helper` 带完整 DWARF，变量名/类型/行号都在，协议因此是可读的：

```
job/in.y    8bit 亮度，width*height 字节
job/req     "%u %u %u" = width, height, variant   （驱动侧用 "%d %d %d" 写）
job/out.y   处理后的亮度，同尺寸
job/done    helper 写，表示 out.y 完整
```

* 启动参数：`monopan_helper <库目录> <作业目录>`；库路径 = `<库目录>/libmialgo_monopan.so`，CL 内核 = `<库目录>/mialgo_monopan_cl.bin`。
* 先 `dlopen(libmpancxx_sh.so)`，再 `dlopen(libmialgo_monopan.so)`；`dlsym` 三个入口：
  `MonopanInit` / `MonopanProcess` / `MonopanDeinit`。
* helper 会 `stat(jobdir)` 取 owner 并对自己的输出 `fchown`，所以相机（非 root）能覆盖这些文件。
* 30 s 无应答时驱动放弃本次作业。

### 3.2 厂商库的 ABI（DWARF 逐字恢复）

```cpp
typedef void* (*Init)(const Config*);
typedef int   (*Process)(void* handle, const Image* in, Image* out);
typedef void  (*Deinit)(void** handle);

struct Config {            // 168 B
    uint32_t density;      // @0
    float    grainSize;    // @4
    float    mix;          // @8
    uint32_t kernelSize;   // @12
    float    sigma;        // @16
    uint32_t mode;         // @20
    float    curve[32];    // @24（128 B）
    float    curveEnd;     // @152
    uint32_t reserved;     // @156
    const char *openclBinaryPath;  // @160
};

struct Image {             // 112 B
    uint32_t format;       // 2000 = NV12
    uint32_t width, height, planeCount;
    uint32_t stride[4], scanlines[4], planeBytes[4];
    uint8_t *planes[4];
    int32_t  fds[4];       // -1
};
```

### 3.3 两个变体的参数（helper 里的常量，已解出）

`req` 的第三个数字就是变体，helper 用 `cmp variant, #2` 分两套：

| 字段 | variant == 2 | variant != 2（即 1） |
| --- | --- | --- |
| density | 4 | 20 |
| grainSize | 1.8 | 0.8 |
| mix | 0.3 | 0.3 |
| kernelSize | 7 | 3 |
| sigma | 0.5 | 0.2 |
| mode | 1 | 0 |
| curve（32 点） | 0, 0.0118, 0.0251, 0.0408, 0.0601, 0.0841, 0.1132, 0.1451, 0.1789, 0.2143, 0.2511, 0.2891, 0.3281, 0.3677, 0.4077, 0.4483, 0.4897, 0.5317, 0.5739, 0.6163, 0.6585, 0.7004, 0.7417, 0.7822, 0.8213, 0.8581, 0.8917, 0.9213, 0.9460, 0.9654, 0.9803, 0.9916 | 0.098, 0.114, 0.129, 0.153, 0.176, 0.204, 0.231, 0.255, 0.282, 0.306, 0.337, 0.369, 0.400, 0.431, 0.463, 0.494, 0.525, 0.557, 0.588, 0.620, 0.651, 0.682, 0.710, 0.741, 0.769, 0.800, 0.827, 0.855, 0.882, 0.914, 0.941, 0.969 |

变体到滤镜的对应关系（**推断**，依据“迫冲＝感光过度冲洗”：黑位抬高 + 颗粒更密更细）：

* `variant 2`（黑位 0、颗粒密度 4、颗粒更粗 1.8）→ **MONOPAN 50**
* `variant 1`（黑位抬到 0.098、密度 20、颗粒更细 0.8）→ **MONOPAN 50 迫冲**

### 3.4 单色是怎么来的

驱动侧送给 helper 的输入 `Image` 是 NV12、`planeCount 2`，第二平面填的是常量 `0x80`（中性色度），输出只写回亮度。也就是说：**单色 = 把色度置中性**，颜色早在进 helper 之前就被丢掉了。本模块的实现沿用了这一点。

### 3.5 暗角表：谁在读它（已定案）

先纠正一个容易走错的前提：**`leica_filter_param_m3.bin` 不是厂商库在读，也不是 helper 在读。**

* `libmialgo_monopan.so` 的 `MonopanInit` 只吃 `Config`（168 B），字段是 `density / grainSize / mix / kernelSize / sigma / mode / curve[32] / curveEnd / reserved / openclBinaryPath`——**没有任何路径、没有暗角、没有场景**。`Image`（112 B）只有 `format/width/height/planeCount/stride[]/scanlines[]/planeBytes[]/planes[]/fds[]`。
* helper 的字符串表里根本没有 `leica_filter` / `aiscene` / `lux` / `.bin`（除了 `/mialgo_monopan_cl.bin`），它只接受 `<库目录> <作业目录>` 两个参数。

真正读它的是 **Opus 自己的 `libjiege_native.so`**（`omnicam-muse-1.3.0.apk` 内，478 640 B，已 strip）。其中的关键串：

```
/data/user/0/com.oplus.camera/files/jiege/monopan[/job]
/leica_filter_param_m3.bin
MonoPanGrain
vignette table missing under %s
%dx%d lux %d zoom %.2f: vignette %s (start %.2f end %.2f scale %.2f/%.2f), grain %s (status %d), %lld ms
```

所以暗角是他们自己算的，四个参数就是 **`start` / `end` / `scaleX` / `scaleY`**。

### 3.6 暗角的取值方式（已解到能照抄的程度）

取值函数在 `0x632c0` 一带，应用/记录在 `0x64820` 起：

1. 打开 `%s/leica_filter_param_m3.bin`，`fseek/ftell` 取长度，**小于 16 字节就当失败**（状态字节置 0，日志 `vignette table missing under %s`）。
2. 解析结果放在全局：`0x7eb08` 起是数据基址三元组（begin/end/cap），`+0x18` 存一个 u32 偏移，`0x7eb24` 存像素区 u32 基址，`+0x20` 存 u16 的 zoom 节点数。
3. 查询时先按 **zoom** 找节点：节点步长 12 字节，`u16@+4` 是下一层索引、`float@+6` 是该节点的 zoom 键；循环里 `fcmp` 到 `zoom < key` 就停。
4. 再按 **lux** 在同一节点下的行里找，行首 `u16` 是 lux 上界；命中后按 `t = (lux - luxPrev) / (luxNext - luxPrev)` 做一次**一维线性插值**（`t` 与 `1-t` 加权，`fmul` + `fmla`）。
5. 每个叶子是 **32 字节 = 8 个 float**，前 4 个就是暗角参数（`start/end/scaleX/scaleY`），后 4 个是另一组（日志里的 `grain`）。
6. 叶子地址 = `像素区基址 + u8@+10 * 32 + u8@+11 * 8192`，所以这是一张 **256 行 × 32 字节步长** 的二维网格。

### 3.7 文件本身的结构（已解出的部分）

典型的 MI tuning 容器：

| 偏移 | 内容 |
| --- | --- |
| 0 | `u16` 场景数 = 6，随后 6 × `u16` 记录步长 = 78 |
| 14 | `u16` = 1024（场景记录起点）、`u16` = 4096、`u16` = 17、`u16` = 5、5 × `u16` = 54 |
| 32 / 288 / 544 / 800 | 四段 256 字节文本槽：`aiscene-lux-cct`、`uint16_t`、`0-1023`、`202510210954` |
| 1024 | 6 × 78 B 场景记录 = `char name[64]` + `u32` + `u16 lux` + `u16` + `u16` + `u16 cct` + `u16 sceneIndex` |
| 1492 | 5 个 zoom 块，步长 54 B：`float zoom`(0.6 / 1 / 3 / 4.3 / 10) + `u16 n`(=4) + 4 × 12 B 行 `{u16 luxLo, u16 luxHi, u16, u16, u16 cct=10000, u16 idx}`；行区间 `[0,210] [240,270] [290,380] [400,999]`，结束标记是 `float 0.0` |
| 4096 | 像素区：17 × 17 的网格，每格 3 字节；17 个轴刻度是 `0,16,32,…,0x80,0x8f,0x9f,…,0xff` |

**没解完的是树索引与 8 float 叶子的实际取值**（通用树 + 逐节点 `u16` 子索引），代价很高。

### 3.8 本模块的取舍

暗角仍用固定形状的径向衰减（第四节），不用这张表。理由：**这张表只决定暗角的强弱分布，不决定「有没有暗角」**；把它全解开要重写一套通用树解析 + 网格寻址，而观感差别只在角落的衰减曲线上。真要复刻，最快的路不是逆 `libjiege_native.so`，而是把 17×17 网格的 8-float 叶子批量 dump 出来按 lux/zoom 手动挑几组写进 `monopan.cpp`。

### 3.9 工具修正（踩过的坑）

`tools/elf_xref.py` 的 `adrp` 掩码原本写成 `0x9F00001F`，把 Rd 也一起比了——**只有 `adrp x0, …` 能命中，其它目标寄存器全部静默漏掉**，表现为「日志格式串居然没有任何引用」。正确写法是 `(w & 0x9F000000) == 0x90000000`，再单独读 `w & 0x1F`。另外 Git Bash 里 `--str /leica…` 会被 MSYS 改写成 Windows 路径，要么加 `MSYS_NO_PATHCONV=1`，要么直接传 vaddr。

## 四、本模块的实现

| 位置 | 内容 |
| --- | --- |
| `module/payload/monopan/*` | 五个文件原样搬运（含闭源库与 CL 内核） |
| `module/payload/meishe_lut/JG_MONOPAN50*.bin` | **重新烘焙**：不再是恒等表，而是“亮度 + 主曲线”的单色 LUT（`tools/bake_monopan_luts.py`）。这样取景器里就已经是黑白，迫冲的对比也略高 |
| `module/service.sh` | 部署参数表、拷 helper 与库到 `/data/local/tmp/monopan`、拉起 helper（`pkill` 单例） |
| `app/.../filters/MasterFilterDefaults.java` | 两个 filter entry：`MONOPAN 50` / `MONOPAN 50 迫冲`，类别「徕卡风格」 |
| `app/.../masterfilter/MonoPan.java` | 在拍摄元数据构建前把变体写进 `files/jiege/monopan/active` |
| `app/src/main/cpp/monopan.cpp` | 拍摄后处理：读变体 → 取亮度 → 跑 helper → 回写亮度 + 色度置中 → 暗角 |
| `app/src/main/cpp/soft_light.cpp` | 在 `MsRenderHDRHostBuffer` 的两条返回路径上各调用一次 `monopanApplyCapture` |

设计要点：

* **一次性标记**：native 读完即删 `active`，所以别的滤镜的拍摄不可能捡到上一次的变体；标记超过 120 s 也视为过期。
* **失败即放行**：helper 没起来、超时、输出不全，都直接 return，照片保持相机原始渲染。日志 tag 是 `MonoPan`。
* **超时预算**：默认 4 s（`persist.omnicam.monopan.timeout`），到点放弃本次作业而不是卡住快门。
* **暗角**：径向衰减，`persist.omnicam.monopan.vig` = 0–100，默认 35（约 −1 EV 在角上），从半径 0.45 开始到 1.0 落到底。
* **单色**：色度平面填 512（中性），亮度整体替换。

## 五、还没做完 / 上机要做的事

1. **暗角表没落地**（原因见 3.5–3.8）。现在用的是固定形状；要复刻 M3 原表，最快的一步是把像素区 17×17 网格的 8-float 叶子 dump 出来，按 lux/zoom 挑几组写进 `monopan.cpp`，而不是去重写通用树解析。
2. **取景器只有单色，没有颗粒/暗角**。Opus 同样只在拍摄链上做（`MsRenderHardwareBuffer`），预览走 GL，颗粒成本高。若要预览也有颗粒，得在 `soft_light.cpp` 的 GL 管线里加一遍（现有 `GlPipeline`/`scatterTexture` 可复用）。
3. **上机验证顺序**（都只看 logcat，tag `MonoPan`）：
   ```
   adb shell ps -A | grep monopan_helper        # helper 在跑
   adb shell ls -l /data/data/com.oplus.camera/files/jiege/monopan/job
   adb logcat -s MonoPan                        # 选 MONOPAN 拍摄，应出现 "variant N for this shot" 与 "capture ... ok"
   ```
   * 没有 `variant` 日志 → Java 侧没命中 `mFilterType`（可能取值不是 LUT 文件名），把 `MonoPan` 的 `variant()` 改成打印 `name` 先看实际值。
   * 有 `variant` 但 `the helper did not answer` → helper 没起来或库路径不对，看 `logcat | grep monopan`。
   * `buffer rejected` → host buffer 布局与 `HostBufferInfo` 不一致。
4. **两张 LUT 的观感**（对比/抬黑）是我按 M3 的常见观感定的，不是官方数据；若上机后发现与 Opus 1.3 差别大，改 `tools/bake_monopan_luts.py` 里的对比/抬黑重烘即可。
5. 关掉整项功能：`setprop persist.omnicam.aura.skip monopan` 后重启相机（Java 侧不再写标记）；native 侧可 `setprop persist.omnicam.monopan.vig 0` 去掉暗角。
