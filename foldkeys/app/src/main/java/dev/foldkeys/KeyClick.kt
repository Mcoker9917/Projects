package dev.foldkeys

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A short, soft "tock" for key presses, generated in code (no recorded sounds), with a
 * slightly different pitch for letters, delete and space/return like an iPhone.
 * Silent when the phone is on vibrate or silent.
 */
class KeyClick(private val context: Context) {

    private enum class Kind(val pitch: Double, val decayMs: Double) {
        LETTER(1750.0, 2.6), DELETE(1350.0, 3.0), MODIFIER(1050.0, 3.4)
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = HashMap<Kind, Int>()

    init {
        for (k in Kind.values()) {
            val f = File(context.cacheDir, "click_${k.name.lowercase()}_v1.wav")
            if (!f.exists()) writeWav(f, synth(k))
            ids[k] = pool.load(f.path, 1)
        }
    }

    fun play(type: KeyType) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val kind = when (type) {
            KeyType.DELETE -> Kind.DELETE
            KeyType.SPACE, KeyType.RETURN, KeyType.SHIFT, KeyType.TO_NUMBERS, KeyType.TO_SYMBOLS,
            KeyType.TO_LETTERS, KeyType.TO_EMOJI, KeyType.MIC, KeyType.GLOBE -> Kind.MODIFIER
            else -> Kind.LETTER
        }
        ids[kind]?.let { pool.play(it, 0.45f, 0.45f, 1, 0, 1f) }
    }

    fun release() = pool.release()

    /** A damped tone with a little filtered noise on the attack: a short wooden tap. */
    private fun synth(k: Kind): ShortArray {
        val rate = 44100
        val n = rate * 22 / 1000
        val rnd = Random(7)
        var lp = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = exp(-t * 1000 / k.decayMs)
            lp += 0.35 * (rnd.nextDouble(-1.0, 1.0) - lp)
            val attack = if (t < 0.0015) 1.0 else exp(-(t - 0.0015) * 2500)
            val s = 0.75 * sin(2 * PI * k.pitch * t) + 0.25 * sin(2 * PI * k.pitch * 2.02 * t) + 0.5 * lp * attack
            (s * env * 0.6 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44100).putInt(44100 * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        FileOutputStream(f).use {
            it.write(h.array())
            it.write(data.array())
        }
    }
}
