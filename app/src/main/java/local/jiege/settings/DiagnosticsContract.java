package local.jiege.settings;

/**
 * Interface between the settings app and the hooked camera (Diagnostics). The camera cannot see
 * the settings app (package visibility), so the app asks: call(CAMERA_AUTHORITY, METHOD_STATE)
 * on the camera's exported EntryProvider, whose call() the hook answers for the module's uid with
 * the keys below. The call starts the camera process when it is not running. An empty Bundle
 * means the camera is not hooked. The gallery answers the same call on its exported
 * GalleryOpenProvider (GALLERY_AUTHORITY), the one without a permission.
 */
public final class DiagnosticsContract {
    public static final String CAMERA_AUTHORITY = "com.oplus.camera.entry";
    public static final String CAMERA_PROVIDER_CLASS = "com.oplus.camera.entry.EntryProvider";
    public static final String GALLERY_PACKAGE = "com.coloros.gallery3d";
    public static final String GALLERY_AUTHORITY = "com.oppo.gallery3d.open.provider";
    public static final String GALLERY_PROVIDER_CLASS = "com.oplus.gallery.openabilitypage.GalleryOpenProvider";
    public static final String METHOD_STATE = "jiege.diagnostics";
    /**
     * 把一组大师预设参数写进相机效果栏（MasterPresetApply）。只由相机进程应答。
     *
     * 入参（extras）：{@link #KEY_PRESET_ACTION}、{@link #KEY_PRESET_TONE}、
     * {@link #KEY_PRESET_SATURATION}、{@link #KEY_PRESET_WARM_COOL}、{@link #KEY_PRESET_CYAN_MAGENTA}、
     * {@link #KEY_PRESET_SHARPNESS}、{@link #KEY_PRESET_VIGNETTE}、{@link #KEY_PRESET_FILTER}、
     * {@link #KEY_PRESET_FILTER_STRENGTH}、{@link #KEY_PRESET_SOFT_LIGHT}。
     * 没有下发的键保持相机原值。
     *
     * 出参：{@link #KEY_PRESET_RESULT}（成功时为空）、{@link #KEY_PRESET_APPLIED}（写成功的项数）、
     * {@link #KEY_PRESET_ITEMS}（相机效果栏的实际组成，action=dump 时用于核对映射）。
     */
    public static final String METHOD_APPLY_PRESET = "jiege.preset";
    /** apply：写入并返回结果；dump：只返回效果栏组成。 */
    public static final String KEY_PRESET_ACTION = "action";
    public static final String ACTION_APPLY = "apply";
    public static final String ACTION_DUMP = "dump";
    public static final String KEY_PRESET_TONE = "tone";
    public static final String KEY_PRESET_SATURATION = "saturation";
    public static final String KEY_PRESET_WARM_COOL = "warmCool";
    public static final String KEY_PRESET_CYAN_MAGENTA = "cyanMagenta";
    public static final String KEY_PRESET_SHARPNESS = "sharpness";
    public static final String KEY_PRESET_VIGNETTE = "vignette";
    public static final String KEY_PRESET_FILTER = "filter";
    public static final String KEY_PRESET_FILTER_STRENGTH = "filterStrength";
    public static final String KEY_PRESET_SOFT_LIGHT = "softLight";
    /**
     * 模块自己插进效果栏的「对比度」（原厂没有这一项，键是 key_master_mode_effect_muse_contrast）。
     * 单独用一个键，别和影调那个 contrast 混。
     */
    public static final String KEY_PRESET_MUSE_CONTRAST = "museContrast";
    /** 预设名（预设库里的名字）：滤镜名在本机不存在时，用它新建一个自定义预设。 */
    public static final String KEY_PRESET_NAME = "presetName";
    /**
     * 新建的预设要绑哪个滤镜：相机滤镜表里的 id（例如 {@code kodak.cube.rgb.bin}），
     * 「标准」是 {@code default}。
     *
     * <p>为什么需要：预设库里的滤镜名来自别的 ColorOS 版本，「浓郁」在本机改名「菲林」——
     * 名字对不上，但滤镜本身还在。这时新建的预设应当绑到那个滤镜上（相机自己会在切换到这条
     * 预设时把它切过去），而不是一律落在「标准」上。不知道绑哪个就不传，按标准处理。
     */
    public static final String KEY_PRESET_FILTER_TYPE = "filterType";
    /** 自定义预设键（custom_<n>），诊断改名字时指认是哪一条。 */
    public static final String KEY_PRESET_KEY = "presetKey";
    /**
     * 要不要新建自定义预设：预设写的滤镜名本机没有（例如「浓郁」在 ColorOS 17 已改名「菲林」）时为
     * true——参数照写到「标准」上，再落一个名为 {@link #KEY_PRESET_NAME} 的预设。
     */
    public static final String KEY_PRESET_CREATE = "createPreset";
    /** 诊断：只新建自定义预设（不碰效果栏），用来核对落库那一段。 */
    public static final String ACTION_CREATE = "createpreset";
    public static final String KEY_PRESET_RESULT = "result";
    public static final String KEY_PRESET_APPLIED = "applied";
    public static final String KEY_PRESET_ITEMS = "items";
    /**
     * 待写入的预设：相机当时不在大师模式（效果栏还没建）时，设置 App 把参数先写到设置目录里的
     * 这个文件，相机进大师模式、效果栏构造时自己读出来写入一次。
     * 内容是一行 {@code nonce=<毫秒>} 加若干 {@link #KEY_PRESET_ACTION} 之类的键值行。
     */
    public static final String PENDING_FILE = "master_preset_pending.txt";
    /** 相机写入成功后，在它自己的数据目录里记下这次 nonce，避免每次启动都重放。 */
    public static final String APPLIED_FILE = "preset_applied.txt";
    public static final String MODULE_PACKAGE = "local.jiege.camera";
    public static final String KEY_PROCESS = "process";
    public static final String KEY_PID = "pid";
    public static final String KEY_START = "start";
    /** The camera APK. */
    public static final String KEY_APK = "apk";
    /** The module APK the hooks were loaded from. */
    public static final String KEY_MODULE_APK = "moduleApk";
    public static final String KEY_SYMBOLS_RESOLVED = "symbolsResolved";
    public static final String KEY_SYMBOLS_MISSING = "symbolsMissing";
    /** Number of error lines (hooks that failed to install or failed at run time). */
    public static final String KEY_ERRORS = "errors";
    /** Whether the process sees the module's /odm files (false: unmounted for it, features off). */
    public static final String KEY_FILES_MOUNTED = "filesMounted";
    /** Recent module log lines of the camera process. */
    public static final String KEY_LOG = "log";
    /** The camera's master filter list (MasterFilterContract stock list text), null before the camera built it. */
    public static final String KEY_MASTER_STOCK = "masterStock";

    private DiagnosticsContract() {}
}
