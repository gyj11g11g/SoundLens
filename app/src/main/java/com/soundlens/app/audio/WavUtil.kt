package com.soundlens.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Minimal PCM16 mono WAV writer/reader.
 */
object WavUtil {

    const val SAMPLE_RATE = 16000

    fun writeWav(file: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeBytes("RIFF")
            writeIntLe(out, 36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeIntLe(out, 16)
            writeShortLe(out, 1)              // PCM
            writeShortLe(out, 1)              // mono
            writeIntLe(out, SAMPLE_RATE)
            writeIntLe(out, SAMPLE_RATE * 2)  // byte rate
            writeShortLe(out, 2)              // block align
            writeShortLe(out, 16)             // bits per sample
            out.writeBytes("data")
            writeIntLe(out, dataSize)
            for (s in pcm) {
                val v = s.toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
        }
    }

    fun readWav(file: File): ShortArray {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { ins ->
            ins.skipBytes(44)
            val buf = ArrayList<Short>(64000)
            while (true) {
                val lo = ins.read()
                if (lo < 0) break
                val hi = ins.read()
                if (hi < 0) break
                buf.add((((hi shl 8) or lo)).toShort())
            }
            return ShortArray(buf.size) { buf[it] }
        }
    }

    private fun writeIntLe(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
