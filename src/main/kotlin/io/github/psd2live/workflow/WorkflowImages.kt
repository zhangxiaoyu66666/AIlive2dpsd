package io.github.psd2live.workflow

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

data class WorkflowInput(val path: String, val sha256: String, val preview: BufferedImage)
data class WorkflowLayer(val name: String, val preview: BufferedImage)

internal object WorkflowImages {
    fun read(path: Path): WorkflowInput {
        require(Files.isRegularFile(path) && Files.size(path) in 1..(32L * 1024 * 1024)) { "Select an image up to 32 MiB" }
        require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")) { "Expected PNG, JPEG or WebP" }
        val bytes = Files.readAllBytes(path)
        // Skia is already shipped with Compose and supports WebP as well as PNG/JPEG.
        val preview = Image.makeFromEncoded(bytes).use { image ->
            require(image.width.toLong() * image.height <= 32L * 1024 * 1024) { "Image exceeds 32 megapixels" }
            image.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                thumbnail(ImageIO.read(png.bytes.inputStream()), 1600)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return WorkflowInput(path.toAbsolutePath().normalize().toString(), hash, preview)
    }

    fun thumbnail(source: BufferedImage, maximum: Int): BufferedImage {
        val scale = minOf(1.0, maximum.toDouble() / maxOf(source.width, source.height))
        if (scale == 1.0) return source
        val target = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
        target.createGraphics().let { graphics ->
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(source, 0, 0, target.width, target.height, null)
            } finally { graphics.dispose() }
        }
        return target
    }
}
