package com.frcam.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AnimatedGifEncoderTest {

    @Test
    fun testGifCreationAndValidity() {
        val w = 320
        val h = 180
        val baos = ByteArrayOutputStream()
        val encoder = AnimatedGifEncoder()
        val started = encoder.start(baos)
        assertTrue("start should succeed", started)
        encoder.setRepeat(0)
        encoder.setDelay(280)

        // Generate 3 test frames with distinct colors and shapes
        val frame1 = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x20
        }
        val frame2 = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (0x20 shl 16) or ((x * 255 / w) shl 8) or (y * 255 / h)
        }
        val frame3 = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((y * 255 / h) shl 16) or (0x40 shl 8) or (x * 255 / w)
        }

        assertTrue("Frame 1 should add", encoder.addFrame(frame1, w, h))
        assertTrue("Frame 2 should add", encoder.addFrame(frame2, w, h))
        assertTrue("Frame 3 should add", encoder.addFrame(frame3, w, h))
        assertTrue("Finish should succeed", encoder.finish())

        val gifBytes = baos.toByteArray()
        println("Generated GIF size: ${gifBytes.size} bytes")
        assertTrue("GIF should have content", gifBytes.size > 100)

        // Read using standard ImageIO
        val iis = ImageIO.createImageInputStream(ByteArrayInputStream(gifBytes))
        assertNotNull("ImageInputStream should not be null", iis)

        val readers = ImageIO.getImageReaders(iis)
        assertTrue("Should have GIF reader available", readers.hasNext())
        val reader = readers.next()
        reader.input = iis

        val numImages = reader.getNumImages(true)
        println("Number of frames read by ImageIO: $numImages")
        assertEquals("GIF should contain 3 frames", 3, numImages)

        for (i in 0 until numImages) {
            val img = reader.read(i)
            assertNotNull("Frame $i should be decoded", img)
            assertEquals("Frame $i width", w, img.width)
            assertEquals("Frame $i height", h, img.height)
        }
        println("All 3 frames decoded successfully by ImageIO!")
    }
}
