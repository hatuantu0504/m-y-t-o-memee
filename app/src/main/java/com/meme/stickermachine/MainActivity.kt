package com.meme.stickermachine

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var machine: MachineView
    private lateinit var btnSave: Button
    private lateinit var btnRedo: Button
    private val remover = BgRemover()
    private var attempt = 0
    private var current: Bitmap? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        machine = MachineView(this)
        btnRedo = Button(this).apply {
            text = "🔁  Xoá lại"; isEnabled = false
            setOnClickListener { redo() }
        }
        btnSave = Button(this).apply {
            text = "⬇  Tải về"; isEnabled = false
            setOnClickListener { save() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 24)
            addView(btnRedo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btnSave, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(machine, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        machine.onFunnelTap = { picker.launch("image/*") }
        machine.onPhase = { ph ->
            val done = ph == MachineView.Phase.DONE
            btnSave.isEnabled = done
            btnRedo.isEnabled = done
        }
    }

    private fun onPicked(uri: Uri) {
        val bmp = decode(uri)
        if (bmp == null) { toast("Không mở được ảnh này"); return }
        attempt = 0
        current = null
        machine.startFeed(bmp)
        remover.prepare(bmp) { ok ->
            if (!ok) {
                toast("Chưa tách được nền (lần đầu cần mạng để tải model)")
                machine.reset()
                return@prepare
            }
            runCut(0)
        }
    }

    private fun redo() {
        if (current == null) return
        attempt = min(attempt + 1, 6)
        machine.startRedo()
        runCut(attempt)
    }

    private fun runCut(level: Int) {
        Thread {
            val r = remover.cut(level)
            runOnUiThread { current = r; machine.deliver(r) }
        }.start()
    }

    private fun save() {
        val bmp = current ?: return
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "sticker_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MemeSticker")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Đã lưu vào Pictures/MemeSticker")
        } catch (e: Exception) {
            toast("Lưu lỗi: ${e.message}")
        }
    }

    private fun decode(uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > 1024) {
                val s = 1024f / big
                dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    } catch (e: Exception) { null }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
