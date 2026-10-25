package com.frcam.app.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure Kotlin animated GIF89a encoder.
 * Based on the public domain Kevin Weiner / NeuQuant GIF encoding algorithm.
 */
class AnimatedGifEncoder {

    private var width: Int = 0
    private var height: Int = 0
    private var delayMs: Int = 200
    private var repeat: Int = 0 // 0 = loop indefinitely
    private var out: OutputStream? = null

    private var pixels: ByteArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorTab: ByteArray? = null
    private var usedEntry = BooleanArray(256)
    private var palSize: Int = 7 // color table size (1 << (palSize + 1)) = 256
    private var firstFrame: Boolean = true

    fun setDelay(ms: Int) {
        delayMs = (ms / 10).coerceAtLeast(2) * 10
    }

    fun setRepeat(iter: Int) {
        repeat = iter
    }

    fun start(outputStream: OutputStream): Boolean {
        out = outputStream
        firstFrame = true
        return try {
            writeString("GIF89a")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun addFrame(im: Bitmap): Boolean {
        if (out == null) return false
        return try {
            val bmp = if (im.config != Bitmap.Config.ARGB_8888) {
                im.copy(Bitmap.Config.ARGB_8888, false)
            } else im

            width = bmp.width
            height = bmp.height
            getImagePixels(bmp)
            analyzePixels()

            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }

            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) {
                writePalette()
            }
            writePixels()
            firstFrame = false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun finish(): Boolean {
        if (out == null) return false
        var ok = true
        try {
            out?.write(0x3B) // GIF trailer
            out?.flush()
        } catch (_: Exception) {
            ok = false
        }
        pixels = null
        indexedPixels = null
        colorTab = null
        out = null
        return ok
    }

    private fun getImagePixels(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val rgb = IntArray(w * h)
        bmp.getPixels(rgb, 0, w, 0, 0, w, h)
        pixels = ByteArray(rgb.size * 3)
        var count = 0
        for (color in rgb) {
            pixels!![count++] = ((color shr 16) and 0xFF).toByte() // R
            pixels!![count++] = ((color shr 8) and 0xFF).toByte()  // G
            pixels!![count++] = (color and 0xFF).toByte()         // B
        }
    }

    private fun analyzePixels() {
        val nPix = pixels!!.size / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels!!, pixels!!.size, 10)
        colorTab = nq.process()

        for (i in 0 until 256) {
            usedEntry[i] = false
        }

        var k = 0
        for (i in 0 until nPix) {
            val index = nq.map(
                pixels!![k++].toInt() and 0xFF,
                pixels!![k++].toInt() and 0xFF,
                pixels!![k++].toInt() and 0xFF
            )
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        pixels = null
    }

    private fun writeGraphicCtrlExt() {
        out?.write(0x21) // Extension introducer
        out?.write(0xF9) // GCE label
        out?.write(4)    // Block size
        out?.write(0)    // Packed fields: no transparency
        val d = delayMs / 10
        out?.write(d and 0xFF)
        out?.write((d shr 8) and 0xFF)
        out?.write(0)    // Transparent color index
        out?.write(0)    // Block terminator
    }

    private fun writeImageDesc() {
        out?.write(0x2C) // Image separator
        writeShort(0)    // Image left
        writeShort(0)    // Image top
        writeShort(width)
        writeShort(height)
        if (firstFrame) {
            out?.write(0)
        } else {
            out?.write(0x80 or palSize) // Local color table flag
        }
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out?.write(0x80 or (7 shl 4) or palSize) // Global color table, 256 colors
        out?.write(0) // Background color index
        out?.write(0) // Pixel aspect ratio
    }

    private fun writeNetscapeExt() {
        out?.write(0x21)
        out?.write(0xFF)
        out?.write(11)
        writeString("NETSCAPE2.0")
        out?.write(3)
        out?.write(1)
        writeShort(repeat)
        out?.write(0)
    }

    private fun writePalette() {
        out?.write(colorTab!!, 0, colorTab!!.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) {
            out?.write(0)
        }
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels!!, 8)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        for (i in s.indices) {
            out?.write(s[i].code)
        }
    }

    /**
     * NeuQuant Neural-Net Quantization Algorithm by Anthony Dekker.
     */
    private class NeuQuant(thepic: ByteArray, len: Int, sample: Int) {
        private val network = Array(256) { DoubleArray(4) }
        private val netindex = IntArray(256)
        private val bias = DoubleArray(256)
        private val freq = DoubleArray(256)
        private val thepicture: ByteArray = thepic
        private val lengthcount: Int = len
        private val samplefac: Int = sample

        init {
            for (i in 0 until 256) {
                network[i][0] = ((i shl 4) or (i shr 4)).toDouble()
                network[i][1] = network[i][0]
                network[i][2] = network[i][0]
                freq[i] = 1.0 / 256.0
                bias[i] = 0.0
            }
        }

        fun process(): ByteArray {
            learn()
            unbiasnet()
            inxbuild()
            return colorMap()
        }

        private fun colorMap(): ByteArray {
            val map = ByteArray(3 * 256)
            val index = IntArray(256)
            for (i in 0 until 256) {
                index[network[i][3].toInt()] = i
            }
            var k = 0
            for (i in 0 until 256) {
                val j = index[i]
                map[k++] = network[j][0].toInt().coerceIn(0, 255).toByte()
                map[k++] = network[j][1].toInt().coerceIn(0, 255).toByte()
                map[k++] = network[j][2].toInt().coerceIn(0, 255).toByte()
            }
            return map
        }

        private fun inxbuild() {
            var previouscol = 0
            var startpos = 0
            for (i in 0 until 256) {
                val p = network[i]
                var smallpos = i
                var smallval = p[1].toInt()
                for (j in i + 1 until 256) {
                    val q = network[j]
                    if (q[1].toInt() < smallval) {
                        smallpos = j
                        smallval = q[1].toInt()
                    }
                }
                val q = network[smallpos]
                if (i != smallpos) {
                    for (x in 0..3) {
                        val t = p[x]
                        p[x] = q[x]
                        q[x] = t
                    }
                }
                if (smallval != previouscol) {
                    netindex[previouscol] = (startpos + i) shr 1
                    for (j in previouscol + 1 until smallval) {
                        netindex[j] = i
                    }
                    previouscol = smallval
                    startpos = i
                }
            }
            netindex[previouscol] = (startpos + 255) shr 1
            for (j in previouscol + 1 until 256) {
                netindex[j] = 255
            }
        }

        private fun learn() {
            val nCycles = 100
            val step = if (lengthcount < 1500) 1 else 3 * samplefac
            var pix = 0
            val lim = lengthcount - 3

            for (i in 0 until nCycles) {
                if (pix > lim) pix = 0
                val b = thepicture[pix++].toInt() and 0xFF
                val g = thepicture[pix++].toInt() and 0xFF
                val r = thepicture[pix++].toInt() and 0xFF

                var best = contest(b, g, r)
                altersingle(1.0 / (1.0 + (i.toDouble() / 10.0)), best, b, g, r)
                pix += step
            }
        }

        private fun altersingle(alpha: Double, i: Int, b: Int, g: Int, r: Int) {
            network[i][0] -= alpha * (network[i][0] - b)
            network[i][1] -= alpha * (network[i][1] - g)
            network[i][2] -= alpha * (network[i][2] - r)
        }

        private fun contest(b: Int, g: Int, r: Int): Int {
            var bestd = Double.MAX_VALUE
            var bestbiasd = bestd
            var best = -1
            var bestpos = best

            for (i in 0 until 256) {
                val n = network[i]
                val dist = abs(n[0] - b) + abs(n[1] - g) + abs(n[2] - r)
                if (dist < bestd) {
                    bestd = dist
                    best = i
                }
                val biasdist = dist - bias[i]
                if (biasdist < bestbiasd) {
                    bestbiasd = biasdist
                    bestpos = i
                }
                freq[i] -= 0.001 * freq[i]
                bias[i] += 0.001 * (1.0 / 256.0 - freq[i])
            }
            freq[bestpos] += 0.001
            bias[bestpos] -= 0.001 * 256.0
            return bestpos
        }

        private fun unbiasnet() {
            for (i in 0 until 256) {
                network[i][3] = i.toDouble()
            }
        }

        fun map(b: Int, g: Int, r: Int): Int {
            var bestd = 1000.0
            var best = -1
            var i = netindex[g]
            var j = i - 1

            while (i < 256 || j >= 0) {
                if (i < 256) {
                    val n = network[i]
                    var dist = n[1] - g
                    if (dist >= bestd) {
                        i = 256
                    } else {
                        i++
                        if (dist < 0) dist = -dist
                        var a = n[0] - b
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            a = n[2] - r
                            if (a < 0) a = -a
                            dist += a
                            if (dist < bestd) {
                                bestd = dist
                                best = n[3].toInt()
                            }
                        }
                    }
                }
                if (j >= 0) {
                    val n = network[j]
                    var dist = g - n[1]
                    if (dist >= bestd) {
                        j = -1
                    } else {
                        j--
                        if (dist < 0) dist = -dist
                        var a = n[0] - b
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            a = n[2] - r
                            if (a < 0) a = -a
                            dist += a
                            if (dist < bestd) {
                                bestd = dist
                                best = n[3].toInt()
                            }
                        }
                    }
                }
            }
            return best.coerceIn(0, 255)
        }
    }

    /**
     * LZW GIF image data encoder.
     */
    private class LzwEncoder(
        private val imgW: Int,
        private val imgH: Int,
        private val pixAry: ByteArray,
        private val initCodeSize: Int
    ) {
        private var cur_accum = 0
        private var cur_bits = 0
        private val masks = intArrayOf(
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
            0x001F, 0x003F, 0x007F, 0x00FF, 0x01FF,
            0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF,
            0x7FFF, 0xFFFF
        )
        private var a_count = 0
        private val accum = ByteArray(256)
        private val htab = IntArray(5003)
        private val codetab = IntArray(5003)
        private var curPixel = 0

        fun encode(outs: OutputStream) {
            outs.write(initCodeSize)
            compress(initCodeSize + 1, outs)
            outs.write(0) // Empty block packet
        }

        private fun char_out(c: Byte, outs: OutputStream) {
            accum[a_count++] = c
            if (a_count >= 254) flush_char(outs)
        }

        private fun flush_char(outs: OutputStream) {
            if (a_count > 0) {
                outs.write(a_count)
                outs.write(accum, 0, a_count)
                a_count = 0
            }
        }

        private fun output(code: Int, outs: OutputStream) {
            cur_accum = cur_accum or (code shl cur_bits)
            cur_bits += n_bits

            while (cur_bits >= 8) {
                char_out((cur_accum and 0xff).toByte(), outs)
                cur_accum = cur_accum shr 8
                cur_bits -= 8
            }

            if (free_ent > maxcode || clear_flg) {
                if (clear_flg) {
                    maxcode = (1 shl n_bits) - 1
                    clear_flg = false
                } else {
                    n_bits++
                    maxcode = if (n_bits == 12) 1 shl 12 else (1 shl n_bits) - 1
                }
            }

            if (code == EOFCode) {
                while (cur_bits > 0) {
                    char_out((cur_accum and 0xff).toByte(), outs)
                    cur_accum = cur_accum shr 8
                    cur_bits -= 8
                }
                flush_char(outs)
            }
        }

        private var n_bits = 0
        private var maxcode = 0
        private var clear_flg = false
        private var ClearCode = 0
        private var EOFCode = 0
        private var free_ent = 0

        private fun compress(init_bits: Int, outs: OutputStream) {
            n_bits = init_bits
            maxcode = (1 shl n_bits) - 1
            ClearCode = 1 shl (init_bits - 1)
            EOFCode = ClearCode + 1
            free_ent = ClearCode + 2
            clear_flg = false

            var ent = nextPixel()
            var hshift = 0
            var fcode = 5003
            while (fcode < 65536) {
                hshift++
                fcode *= 2
            }
            hshift = 8 - hshift

            for (i in 0 until 5003) htab[i] = -1

            output(ClearCode, outs)

            var c = nextPixel()
            while (c != -1) {
                fcode = (c shl 12) + ent
                var i = (c shl hshift) xor ent

                if (htab[i] == fcode) {
                    ent = codetab[i]
                    c = nextPixel()
                    continue
                } else if (htab[i] >= 0) {
                    var disp = 5003 - i
                    if (i == 0) disp = 1
                    var found = false
                    while (true) {
                        i -= disp
                        if (i < 0) i += 5003
                        if (htab[i] == fcode) {
                            ent = codetab[i]
                            found = true
                            break
                        }
                        if (htab[i] < 0) break
                    }
                    if (found) {
                        c = nextPixel()
                        continue
                    }
                }

                output(ent, outs)
                ent = c
                if (free_ent < (1 shl 12)) {
                    codetab[i] = free_ent++
                    htab[i] = fcode
                } else {
                    for (k in 0 until 5003) htab[k] = -1
                    free_ent = ClearCode + 2
                    clear_flg = true
                    output(ClearCode, outs)
                }
                c = nextPixel()
            }
            output(ent, outs)
            output(EOFCode, outs)
        }

        private fun nextPixel(): Int {
            if (curPixel >= pixAry.size) return -1
            return pixAry[curPixel++].toInt() and 0xFF
        }
    }
}
