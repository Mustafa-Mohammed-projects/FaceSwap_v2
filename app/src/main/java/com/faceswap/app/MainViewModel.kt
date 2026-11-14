package com.faceswap.app

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faceswap.app.media.ImageIO
import com.faceswap.app.media.VideoProcessor
import com.faceswap.app.ml.FaceEngine
import com.faceswap.app.ml.ModelNames
import com.faceswap.app.ml.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class SwapResult(val uri: Uri, val video: Boolean)
class FaceChoice(val thumb: Bitmap, val emb: FloatArray)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val store = ModelStore(ctx)
    private val prefs = ctx.getSharedPreferences("faceswap", 0)
    private val sourceDir = File(ctx.filesDir, "sources").apply { mkdirs() }

    // setup
    var acknowledged by mutableStateOf(prefs.getBoolean("ack", false))
    var modelStatus by mutableStateOf(store.status())
    var importing by mutableStateOf(false)
    var importLabel by mutableStateOf("")
    var importProgress by mutableStateOf(0f)
    val ready: Boolean get() = ModelNames.REQUIRED.all { modelStatus[it] == true }
    val hasEnhancer: Boolean get() = modelStatus[ModelNames.ENHANCER] == true

    // source face photos
    val sources = mutableStateListOf<File>()

    // options
    /** 0 = largest face, 1 = all faces, 2 = choose faces from a preview of the media */
    var faceMode by mutableStateOf(0)
    val allFaces: Boolean get() = faceMode == 1

    // face picker
    var analyzing by mutableStateOf(false)
    var pendingUris by mutableStateOf<List<Uri>>(emptyList())
    var pendingVideo by mutableStateOf(false)
    val choices = mutableStateListOf<FaceChoice>()
    val picked = mutableStateListOf<Int>()
    val pickerOpen: Boolean get() = choices.isNotEmpty()
    var softness by mutableStateOf(0.5f)
    var colorMatch by mutableStateOf(0.8f)
    var enhance by mutableStateOf(false)
    var enhanceStrength by mutableStateOf(0.7f)
    var videoMaxSide by mutableStateOf(0) // 0 = original

    // job state
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var status by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    val results = mutableStateListOf<SwapResult>()

    @Volatile private var cancelled = false
    private var engine: FaceEngine? = null
    private var latent: FloatArray? = null

    init { reloadSources() }

    private fun reloadSources() {
        sources.clear()
        sources.addAll(sourceDir.listFiles()?.sortedBy { it.name } ?: emptyList())
    }

    fun acknowledge() { prefs.edit().putBoolean("ack", true).apply(); acknowledged = true }

    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        importing = true; message = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = store.importFiles(uris) { name, p -> importLabel = name; importProgress = p }
                engine?.close(); engine = null; latent = null
                modelStatus = store.status()
                if (r.ignored.isNotEmpty()) message = "Ignored: ${r.ignored.joinToString()}"
            } catch (e: Throwable) {
                message = e.message ?: e.toString()
            } finally { importing = false }
        }
    }

    fun resetModels() {
        engine?.close(); engine = null; latent = null
        store.clear(); modelStatus = store.status()
    }

    fun addSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEachIndexed { i, u ->
                ctx.contentResolver.openInputStream(u)?.use { input ->
                    File(sourceDir, "${System.currentTimeMillis()}_$i.jpg").outputStream().use { input.copyTo(it) }
                }
            }
            latent = null
            reloadSources()
        }
    }

    fun removeSource(f: File) { f.delete(); latent = null; reloadSources() }

    fun cancel() { cancelled = true; status = "Cancelling..." }

    private fun getEngine(): FaceEngine = engine ?: FaceEngine(store).also { engine = it }

    private fun ensureLatent(e: FaceEngine): FloatArray {
        latent?.let { return it }
        if (sources.isEmpty()) error("Add at least one photo of your face first.")
        val bmps = sources.map { ImageIO.decode(ctx, Uri.fromFile(it), 2048, mutable = false) }
        val l = e.latentFrom(bmps) ?: error("No face found in your source photos. Add a clear, front-facing photo.")
        bmps.forEach { it.recycle() }
        latent = l
        return l
    }

    private fun runJob(label: String, block: () -> Unit) {
        if (busy) return
        busy = true; cancelled = false; progress = 0f; message = null; status = label
        KeepAliveService.start(ctx)
        viewModelScope.launch(Dispatchers.Default) {
            try { block() }
            catch (e: Throwable) { message = e.message ?: e.toString() }
            finally { busy = false; KeepAliveService.stop(ctx) }
        }
    }

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (faceMode == 2) analyze(uris, false) else processImages(uris, null)
    }

    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        if (faceMode == 2) analyze(listOf(uri), true) else processVideo(uri, null)
    }

    private fun analyze(uris: List<Uri>, video: Boolean) {
        if (busy || analyzing) return
        analyzing = true; message = null; status = "Finding faces..."
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bmp: Bitmap = if (video) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, uris[0])
                        val raw = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("Could not read the video")
                        raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
                    } finally { r.release() }
                } else ImageIO.decode(ctx, uris[0], 4000, mutable = false)
                val found = getEngine().listFaces(bmp)
                bmp.recycle()
                if (found.isEmpty()) { message = "No face found in the first ${if (video) "frame" else "photo"}."; status = "" }
                else {
                    pendingUris = uris; pendingVideo = video
                    picked.clear(); picked.addAll(found.indices)
                    choices.clear(); choices.addAll(found.map { FaceChoice(it.first, it.second) })
                    status = ""
                }
            } catch (e: Throwable) { message = e.message ?: e.toString(); status = "" }
            finally { analyzing = false }
        }
    }

    fun togglePick(i: Int) { if (i in picked) picked.remove(i) else picked.add(i) }

    fun cancelPicker() { choices.clear(); picked.clear(); pendingUris = emptyList() }

    fun confirmPicker() {
        if (picked.isEmpty()) return
        val sel = picked.sorted().map { choices[it].emb }
        val uris = pendingUris; val video = pendingVideo
        cancelPicker()
        if (video) processVideo(uris[0], sel) else processImages(uris, sel)
    }

    fun processImages(uris: List<Uri>, selected: List<FloatArray>? = null) {
        if (uris.isEmpty()) return
        runJob("Loading models...") {
            val e = getEngine()
            e.setSoftness(softness)
            e.colorMatch = colorMatch
            val lat = ensureLatent(e)
            val strength = if (enhance && e.hasEnhancer) enhanceStrength else 0f
            var saved = 0
            val notes = StringBuilder()
            for ((i, uri) in uris.withIndex()) {
                if (cancelled) break
                status = "Photo ${i + 1} of ${uris.size}"
                val name = ImageIO.displayName(ctx, uri).substringBeforeLast('.')
                val bmp = ImageIO.decode(ctx, uri, 8000)
                val n = e.processBitmap(bmp, lat, allFaces, strength, selected)
                if (n == 0) notes.append("No face found in $name\n")
                else { 
                    // تطبيق الحماية ومستوى الضغط المتقدم وحقن الـ Exif
                    results.add(0, SwapResult(ImageIO.save(ctx, bmp, name + "_hardened_swap", ImageIO.isPng(ctx, uri)), false))
                    saved++ 
                }
                bmp.recycle()
                progress = (i + 1f) / uris.size
            }
            status = "Done: $saved saved to Pictures/FaceSwap"
            if (notes.isNotEmpty()) message = notes.toString().trim()
        }
    }

    fun processVideo(uri: Uri, selected: List<FloatArray>? = null) {
        runJob("Loading models...") {
            val e = getEngine()
            e.setSoftness(softness)
            e.colorMatch = colorMatch
            val lat = ensureLatent(e)
            val strength = if (enhance && e.hasEnhancer) enhanceStrength else 0f
            val out = VideoProcessor.process(ctx, uri, e, lat, allFaces, strength, videoMaxSide, selected,
                { p, t -> progress = p; status = t }, { cancelled })
            if (out != null) { results.add(0, SwapResult(out, true)); status = "Done: saved to Movies/FaceSwap" }
            else status = "Cancelled"
        }
    }

    override fun onCleared() { engine?.close() }
}

