package io.github.psd2live.workflow

import io.github.psd2live.project.ProjectArchive
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class WorkflowImagesTest {
    @Test fun pngJpegAndWebpPreviewMatchSelectedBytes() {
        val root = Files.createTempDirectory("psd2live-project-input-preview-")
        try {
            val raster = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
            raster.setRGB(20, 10, 0xff336699.toInt())
            val png = root.resolve("source.png")
            ImageIO.write(raster, "png", png.toFile())
            val jpg = root.resolve("source.jpg")
            ImageIO.write(raster, "jpg", jpg.toFile())
            val webp = root.resolve("source.webp")
            Image.makeFromEncoded(Files.readAllBytes(png)).use { image ->
                image.encodeToData(EncodedImageFormat.WEBP, 100)!!.use { Files.write(webp, it.bytes) }
            }
            for (file in listOf(png, jpg, webp)) {
                val selected = WorkflowImages.read(file)
                assertEquals(SourceVersions.sha256(file), selected.sha256)
                assertEquals(64, selected.preview.width)
                assertEquals(32, selected.preview.height)
            }
            val invalid = Files.write(root.resolve("invalid.png"), byteArrayOf(0))
            assertFails { WorkflowImages.read(invalid) }
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }
}
