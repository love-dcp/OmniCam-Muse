package local.jiege.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import local.jiege.settings.DiagnosticsContract
import local.jiege.settings.MasterFilterContract

/**
 * Serves the master filter configuration for diagnostics
 * (MasterFilterContract). Only the camera and this app may call it. The camera reads the
 * configuration and the imported LUTs from the LSPosed preferences directory, not from here.
 */
class MasterFilterProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        val store = MasterFilters.store(context!!)
        return when (method) {
            MasterFilterContract.METHOD_CONFIG -> Bundle().apply { putString(MasterFilterContract.KEY_TEXT, store.configText()) }
            else -> null
        }
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = context!!.packageManager.getPackagesForUid(uid).orEmpty()
        if (MasterFilterContract.CAMERA_PACKAGE !in packages) throw SecurityException("Not allowed: uid $uid")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * The app's single master filter store, in the module's LSPosed preferences directory (the one
 * xposedsharedprefs gives module_settings.xml), which the camera reads. Without an active LSPosed
 * it falls back to private files, where the camera cannot see it.
 */
object MasterFilters {
    @Volatile private var store: local.jiege.settings.MasterFilterStore? = null

    fun store(context: android.content.Context): local.jiege.settings.MasterFilterStore =
        store ?: synchronized(this) {
            store ?: local.jiege.settings.MasterFilterStore(directory(context.applicationContext)).also { store = it }
        }

    /** True when the camera can read the store (LSPosed preferences directory in use). */
    fun visibleToCamera(context: android.content.Context): Boolean = sharedDirectory(context.applicationContext) != null

    internal fun directory(context: android.content.Context): java.io.File =
        sharedDirectory(context) ?: java.io.File(context.filesDir, "master")

    @Suppress("DEPRECATION")
    private fun sharedDirectory(context: android.content.Context): java.io.File? = try {
        val preferences = context.getSharedPreferences(local.jiege.settings.ModuleSettings.FILE, android.content.Context.MODE_WORLD_READABLE)
        val field = preferences.javaClass.getDeclaredField("mFile").apply { isAccessible = true }
        (field.get(preferences) as java.io.File).parentFile
    } catch (_: Throwable) {
        null
    }

    fun header(context: android.content.Context): ByteArray =
        context.assets.open("mslut_header.bin").use { it.readBytes() }
}

/**
 * 大师预设库，和滤镜配置放在同一个 LSPosed 偏好目录。
 *
 * 内置数据 assets/master_presets.txt 来自 OMaster —— 大师模式调色参数库
 * （https://github.com/iCurrer/OMaster，作者 Silas/@iCurrer、Luminary/@fengyec2），
 * 原项目以 CC BY-NC-SA 4.0 发布；本模块为免费非商业项目，按该许可署名并以相同方式共享。
 */
object MasterPresets {
    @Volatile private var library: local.jiege.settings.MasterPresetLibrary? = null

    fun library(context: android.content.Context): local.jiege.settings.MasterPresetLibrary =
        library ?: synchronized(this) {
            // 空库或旧格式（缺封面）时按内置数据重建；正常升级只补新条目，不动用户收藏与导入。
            library ?: local.jiege.settings.MasterPresetLibrary(MasterFilters.directory(context.applicationContext))
                .also { store ->
                    val builtin = builtin(context.applicationContext)
                    if (store.needsReseed()) store.reseed(builtin) else store.syncBuiltin(builtin)
                }
        }

    /** True when the camera can read the preset file (same condition as the filter store). */
    fun visibleToCamera(context: android.content.Context): Boolean = MasterFilters.visibleToCamera(context)

    /**
     * 把一组预设参数写进设置目录的待写入文件：相机当时不在大师模式（效果栏还没建）时，
     * 它进大师模式后会自己读出来写一次。所有值都写成文本，相机侧按 key=value 解析。
     */
    fun queue(context: android.content.Context, args: Bundle) {
        val file = java.io.File(MasterFilters.directory(context.applicationContext), DiagnosticsContract.PENDING_FILE)
        val text = buildString {
            append("nonce=").append(System.currentTimeMillis()).append('\n')
            for (key in args.keySet()) {
                val value = args.get(key) ?: continue
                append(key).append('=').append(value).append('\n')
            }
        }
        runCatching {
            file.writeText(text)
            file.setReadable(true, false)
        }
    }

    /** 相机已经写进去了，排队文件就可以删掉。 */
    fun clearQueue(context: android.content.Context) {
        runCatching {
            java.io.File(MasterFilters.directory(context.applicationContext), DiagnosticsContract.PENDING_FILE).delete()
        }
    }

    private fun builtin(context: android.content.Context): String = try {
        context.assets.open("master_presets.txt").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        ""
    }
}
