package com.meme.stickermachine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Tiếng "rọt rọt" tự tổng hợp (không cần file âm thanh). */
object Slurp {
    fun play(pulses: Int) {
        Thread {
            try {
                val sr = 22050
                val pulseN = (sr * 0.28).toInt()
                val gapN = (sr * 0.10).toInt()
                val buf = ShortArray(pulses * (pulseN + gapN))
                var idx = 0
                var ph = 0.0
                var noise = 0.0
                val rnd = java.util.Random(7)
                repeat(pulses) { k ->
                    for (i in 0 until pulseN) {
                        val x = i / pulseN.toDouble()
                        val f = 180.0 + 650.0 * x * x + (if (k == 1) 80.0 else 0.0)
                        ph += 2 * PI * f / sr
                        val gur = 0.6 + 0.4 * sin(2 * PI * 38 * i / sr)
                        noise = noise * 0.92 + (rnd.nextDouble() * 2 - 1) * 0.08
                        val env = sin(PI * x).pow(0.6)
                        val v = (sin(ph) * 0.55 * gur + noise * 0.55) * env
                        buf[idx++] = (v * 0.8 * Short.MAX_VALUE).toInt().toShort()
                    }
                    idx += gapN
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(buf.size * 1000L / sr + 150)
                track.release()
            } catch (_: Exception) { }
        }.start()
    }
}
