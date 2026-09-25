package com.fersaiyan.cyanbridge.ui.media

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.fersaiyan.cyanbridge.media.OpusOggWrapper
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaItem
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaType
import com.fersaiyan.cyanbridge.ui.hasBluetooth
import com.fersaiyan.cyanbridge.ui.hasWifiP2pPermission
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** UI host only: the ViewModel/repository own media sessions, HTTP and MediaStore writes. */
class Bv300MediaActivity : AppCompatActivity() {
    private data class AudioPreview(val convertedFile: File?)

    private lateinit var model: Bv300MediaViewModel
    private var audioPlayer: MediaPlayer? = null
    private var playingAudioUri: Uri? = null
    private var previewJob: Job? = null
    private var previewFile: File? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, Bv300MediaViewModel.Factory(applicationContext))[Bv300MediaViewModel::class.java]
        val appearance = AppearancePreferences(this)
        setContent {
            val settings by rememberAppearanceSettings(appearance)
            val state by model.state.collectAsState()
            CyanBridgeTheme(settings) {
                Bv300MediaScreen(state, ::thumbnail, ::finish,
                    onRefresh = { withPermissions(model::refresh) },
                    onDownload = { ids -> withPermissions { model.download(ids) } },
                    onDeleteLocal = { ids ->
                        withLocalStoragePermission {
                            previewJob?.cancel()
                            releasePlayer()
                            model.deleteLocal(ids)
                        }
                    },
                    onDownloadNew = { withPermissions(model::downloadNew) },
                    onCancel = model::cancel,
                    onOpen = ::open,
                    onCredentials = ::showWifiCredentials,
                    onOpenOldLibrary = { startActivity(Intent(this, RecordingsListActivity::class.java)) })
            }
        }
        if (savedInstanceState == null) withPermissions(model::refresh)
    }

    override fun onDestroy() {
        previewJob?.cancel()
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        audioPlayer?.release()
        audioPlayer = null
        playingAudioUri = null
        previewFile?.delete()
        previewFile = null
    }

    private fun withPermissions(action: () -> Unit) {
        val needsStorage = Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (hasBluetooth(this) && hasWifiP2pPermission(this) && !needsStorage) {
            action()
            return
        }
        val permissions = mutableListOf<String>()
        if (!hasBluetooth(this)) permissions += if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasWifiP2pPermission(this)) permissions += if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needsStorage) permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        pendingPermissionAction = action
        @Suppress("DEPRECATION")
        requestPermissions(permissions.distinct().toTypedArray(), PERMISSION_REQUEST)
    }

    private fun withLocalStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingPermissionAction = action
        @Suppress("DEPRECATION")
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST)
    }

    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        val next = pendingPermissionAction
        pendingPermissionAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) next?.invoke()
        else Toast.makeText(this, "Необходимое разрешение не предоставлено", Toast.LENGTH_LONG).show()
    }

    private fun showWifiCredentials() {
        val prefs = getSharedPreferences("moyoung_w620", MODE_PRIVATE)
        val ssid = EditText(this).apply { hint = "SSID"; setText(prefs.getString("file_wifi_ssid", "Glass-01")) }
        val password = EditText(this).apply {
            hint = "Пароль"
            setText(prefs.getString("file_wifi_password", "12345678"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(ssid)
            addView(password)
        }
        AlertDialog.Builder(this).setTitle("Wi-Fi BV300")
            .setMessage("Данные сети очков для передачи файлов. Они сохраняются только на телефоне.")
            .setView(fields).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                if (ssid.text.isBlank()) {
                    Toast.makeText(this, "Введите имя сети", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("file_wifi_ssid", ssid.text.toString().trim())
                        .putString("file_wifi_password", password.text.toString()).apply()
                    withPermissions(model::refresh)
                }
            }.show()
    }

    private suspend fun thumbnail(uriString: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext null
        val bitmap = synchronized(thumbnailCache) { thumbnailCache.get(uriString) }
            ?: runCatching { contentResolver.loadThumbnail(Uri.parse(uriString), Size(360, 360), null) }.getOrNull()
                ?.also { synchronized(thumbnailCache) { thumbnailCache.put(uriString, it) } }
        bitmap?.asImageBitmap()
    }

    private fun open(item: Bv300MediaItem) {
        val uri = item.localUri?.let(Uri::parse) ?: return
        if (item.type == Bv300MediaType.AUDIO) {
            if (playingAudioUri == uri && audioPlayer != null) {
                runCatching { audioPlayer?.let { if (it.isPlaying) it.pause() else it.start() } }
                    .onFailure { releasePlayer() }
                return
            }
            previewJob?.cancel()
            releasePlayer()
            previewJob = lifecycleScope.launch {
                val playable = try { prepareAudioPreview(uri, item.fileName) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(this@Bv300MediaActivity, "Не удалось подготовить запись", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (playable == null) {
                    Toast.makeText(this@Bv300MediaActivity, "Формат этой записи пока не поддерживается", Toast.LENGTH_LONG).show()
                    return@launch
                }
                previewFile = playable.convertedFile
                runCatching {
                    val player = MediaPlayer()
                    audioPlayer = player
                    playingAudioUri = uri
                    if (playable.convertedFile != null) player.setDataSource(playable.convertedFile.absolutePath)
                    else player.setDataSource(this@Bv300MediaActivity, uri)
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener { releasePlayer() }
                    player.setOnErrorListener { _, _, _ ->
                        releasePlayer()
                        Toast.makeText(this@Bv300MediaActivity, "Не удалось воспроизвести эту запись", Toast.LENGTH_LONG).show()
                        true
                    }
                    player.prepareAsync()
                }.onFailure {
                    releasePlayer()
                    Toast.makeText(this@Bv300MediaActivity, "Не удалось воспроизвести эту запись", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val mime = if (item.type == Bv300MediaType.VIDEO) "video/*" else "image/*"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show() }
    }

    private suspend fun prepareAudioPreview(uri: Uri, name: String): AudioPreview? = withContext(Dispatchers.IO) {
        if (!name.endsWith(".opus", ignoreCase = true)) return@withContext AudioPreview(null)
        val raw = contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_AUDIO_PREVIEW_BYTES) { "Recording too large for preview" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        } ?: return@withContext null
        val (wrapped, format) = OpusOggWrapper.wrapIfNeeded(raw)
        if (format == "raw-unwrapped") return@withContext null
        if (format == "ogg-already") return@withContext AudioPreview(null)
        AudioPreview(File.createTempFile("bv300-preview-", ".ogg", cacheDir).also { it.writeBytes(wrapped) })
    }

    private companion object {
        const val PERMISSION_REQUEST = 6407
        const val MAX_AUDIO_PREVIEW_BYTES = 20 * 1024 * 1024
    }
}
