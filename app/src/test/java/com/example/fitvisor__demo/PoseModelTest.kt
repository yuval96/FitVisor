package com.example.fitvisor__demo

import com.example.fitvisor__demo.settings.PoseModel
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class PoseModelTest {
    @Test fun storedSelectionsResolveToExactAssets() {
        val expected = mapOf(
            "LITE" to "pose_landmarker_lite.task",
            "FULL" to "pose_landmarker_full.task",
            "HEAVY" to "pose_landmarker_heavy.task"
        )
        for ((selection, path) in expected) {
            val model = PoseModel.fromNameOrDefault(selection)
            assertEquals(selection, model.name)
            assertEquals(path, model.requireAsset { it == path })
        }
        assertEquals(PoseModel.HEAVY, PoseModel.fromNameOrDefault(null))
        assertEquals(PoseModel.HEAVY, PoseModel.fromNameOrDefault("unknown"))
    }

    @Test fun missingSelectedAssetFailsWithoutTryingAnotherModel() {
        for (model in PoseModel.values()) {
            val attempted = mutableListOf<String>()
            val error = assertThrows(IllegalStateException::class.java) {
                model.requireAsset { attempted.add(it); false }
            }
            assertEquals(listOf(model.assetPath), attempted)
            assertTrue(error.message!!.contains(model.assetPath))
            assertTrue(error.message!!.contains("no other model was substituted"))
        }
    }

    @Test fun allBundledAssetsContainIntactTfliteComponents() {
        for (model in PoseModel.values()) {
            val file = File("src/main/assets", model.assetPath)
            assertTrue("Missing ${file.absolutePath}", file.isFile)
            assertTrue(file.length() in 1 until 100L * 1024 * 1024)
            ZipFile(file).use { bundle ->
                for (name in listOf("pose_detector.tflite", "pose_landmarks_detector.tflite")) {
                    val entry = bundle.getEntry(name)
                    assertNotNull("${model.name}: missing $name", entry)
                    val bytes = bundle.getInputStream(entry).use { it.readBytes() }
                    assertTrue(bytes.size > 8)
                    assertEquals("TFL3", String(bytes, 4, 4, Charsets.US_ASCII))
                    assertEquals(entry.size, bytes.size.toLong())
                    assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                }
            }
        }
    }
}
