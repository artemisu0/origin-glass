package com.artemiy.glasstest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * v0.29: любой шрифт TTF/OTF для «Стеклянного времени».
 *
 * Шрифт выбирается в панели (StylePanel → «＋ Свой шрифт» → системный выбор файла, FontPickActivity), проверяется и
 * копируется в files/fonts приложения. Ключ шрифта уходит в contentDescription часов ("…;font=file:<имя>").
 * Цифры рисуются В ПРОЦЕССЕ ЛАУНЧЕРА, а файлы приложения ему недоступны (другой uid) — поэтому лаунчер берёт файл
 * через FontProvider (openFile → дескриптор) и строит Typeface из него. Root не нужен.
 *
 * Ключи: "dela" — встроенный Dela Gothic One; "sys:<семейство>" — системный; "file:<имя>" — свой файл.
 */
object GlassFonts {
    const val AUTHORITY = "com.vivo.widget.widgetsdk_inner_demo.fonts"
    const val DEFAULT = "dela"
    private const val PREFS = "glass_look"

    class Entry(val key: String, val title: String, val user: Boolean = false)

    val BUILTIN = listOf(
        Entry(DEFAULT, "Dela Gothic"),
        Entry("sys:sans-serif-black", "Sans Black"),
        Entry("sys:sans-serif", "Sans"),
        Entry("sys:serif", "Serif"),
        Entry("sys:monospace", "Mono")
    )

    fun dir(c: Context) = File(c.filesDir, "fonts").apply { mkdirs() }

    private fun isFontFile(name: String) = name.endsWith(".ttf", true) || name.endsWith(".otf", true) || name.endsWith(".ttc", true)

    /** «My_Font_12345.ttf» → «My Font». */
    fun prettyName(file: String): String {
        val base = file.substringBeforeLast('.')
        val noStamp = base.substringBeforeLast('_').ifEmpty { base }
        return noStamp.replace('_', ' ').trim().ifEmpty { file }
    }

    fun userFonts(c: Context): List<Entry> =
        (dir(c).listFiles() ?: emptyArray()).filter { it.isFile && isFontFile(it.name) }
            .sortedBy { it.lastModified() }
            .map { Entry("file:" + it.name, prettyName(it.name), user = true) }

    fun all(c: Context) = BUILTIN + userFonts(c)

    fun selected(c: Context): String {
        val k = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("font", DEFAULT) ?: DEFAULT
        if (k.startsWith("file:") && !File(dir(c), k.substring(5)).isFile) return DEFAULT
        return k
    }

    fun select(c: Context, key: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("font", key).apply()
    }

    fun delete(c: Context, key: String) {
        if (!key.startsWith("file:")) return
        File(dir(c), key.substring(5)).delete()
        synchronized(cache) { cache.remove(key) }
        if (selected(c) == key || c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("font", null) == key) select(c, DEFAULT)
    }

    /**
     * Скопировать выбранный файл к себе и проверить, что это шрифт. Возвращает ключ "file:<имя>" или null.
     * Вызывать не в главном потоке.
     */
    fun import(c: Context, uri: Uri): String? {
        var display = "font"
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0)?.let { display = it }
            }
        } catch (_: Throwable) {}
        val tmp = File(dir(c), ".import.tmp")
        try {
            c.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
            // тип — по сигнатуре файла, а не по имени: OTTO → otf, ttcf → ttc, иначе ttf (0x00010000 / true)
            val magic = ByteArray(4)
            tmp.inputStream().use { it.read(magic) }
            val sig = String(magic, Charsets.ISO_8859_1)
            val ext = when (sig) { "OTTO" -> "otf"; "ttcf" -> "ttc"; else -> "ttf" }
            if (sig != "OTTO" && sig != "ttcf" && sig != "true" && !(magic[0] == 0.toByte() && magic[1] == 1.toByte())) return null
            if (Typeface.Builder(tmp).build() == null) return null
            val base = display.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "font" }
            val name = "${base}_${System.currentTimeMillis() % 1_000_000}.$ext"
            val out = File(dir(c), name)
            if (!tmp.renameTo(out)) return null
            return "file:$name"
        } catch (_: Throwable) {
            return null
        } finally {
            tmp.delete()
        }
    }

    // --- загрузка Typeface (в любом процессе) ---
    private val cache = HashMap<String, Typeface>()
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun cached(key: String): Typeface? = synchronized(cache) { cache[key] }

    /** Может читать файл/провайдер — для своих файлов в лаунчере вызывать не в главном потоке. */
    fun typeface(c: Context, key: String?): Typeface? {
        val k = key ?: DEFAULT
        cached(k)?.let { return it }
        val tf: Typeface? = try {
            when {
                k == DEFAULT -> c.resources.getFont(R.font.dela_gothic_one)
                k.startsWith("sys:") -> Typeface.create(k.substring(4), Typeface.NORMAL)
                k.startsWith("file:") -> loadFile(c, k.substring(5))
                else -> null
            }
        } catch (_: Throwable) { null }
        if (tf != null) synchronized(cache) { cache[k] = tf }
        return tf
    }

    private fun loadFile(c: Context, name: String): Typeface? {
        // свой процесс — прямо из файла; лаунчер (чужой uid) — через FontProvider
        val own = File(dir(c), name)
        if (own.canRead()) return Typeface.Builder(own).build()
        val uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()
        return c.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> Typeface.Builder(pfd.fileDescriptor).build() }
    }
}

/**
 * v0.29: отдаёт файлы шрифтов из files/fonts лаунчеру и Jovi (только чтение, только эта папка).
 */
class FontProvider : ContentProvider() {
    private val allowed = setOf("com.bbk.launcher2", "com.vivo.hiboard", "com.vivo.widget.widgetsdk_inner_demo")

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val c = context ?: throw FileNotFoundException()
        val pkgs = c.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        if (pkgs.none { it in allowed }) throw SecurityException("not allowed")
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.startsWith(".")) throw FileNotFoundException(name)
        val f = File(GlassFonts.dir(c), name)
        if (!f.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".otf", true) == true) "font/otf" else "font/ttf"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
