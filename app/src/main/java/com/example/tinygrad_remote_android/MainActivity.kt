package com.example.yolov8_tinygrad_android

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.graphics.ImageFormat
import android.media.Image
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

sealed class ExecOp {
    data class ProgramExecOp(
        val name: String,
        val dispatchSize: IntArray,
        val bufferIndexes: IntArray,
        val shaderBytes: ByteArray
    ) : ExecOp() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ProgramExecOp

            if (name != other.name) return false
            if (!dispatchSize.contentEquals(other.dispatchSize)) return false
            if (!bufferIndexes.contentEquals(other.bufferIndexes)) return false
            if (!shaderBytes.contentEquals(other.shaderBytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + dispatchSize.contentHashCode()
            result = 31 * result + bufferIndexes.contentHashCode()
            result = 31 * result + shaderBytes.contentHashCode()
            return result
        }
    }

    data class CopyOutOp(val bufferNum: String) : ExecOp()
    data class CopyInOp(val bufferNum: Int, val data: ByteArray) : ExecOp() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CopyInOp

            if (bufferNum != other.bufferNum) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bufferNum
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private lateinit var previewView: PreviewView
    public val h = mutableMapOf<String, ByteArray>()
    // Add execOps as a member variable to be accessible by ImageAnalyzer
    private var execOps: List<ExecOp>? = null

    // Camera permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Log.w("MainActivity", "Camera permission denied")
        }
    }

    external fun runVulkanCompute(shaderBytes: ByteArray, dispatchSize: IntArray, bufferIndexes: IntArray, name: String): Float
    external fun createBuffer(key: String, sizeInBytes: Int)
    external fun getBuffer(key: String): ByteArray
    external fun uploadBuffer(bufferIndex: Int, data: ByteArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First set up the camera preview view (but don't start camera yet)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        setContentView(previewView)

        // Load shader and process batch data in background
        Thread {
            try {
                val shaderStream: InputStream = assets.open("shader.comp.spv")
                val shaderBytes = shaderStream.readBytes()
                shaderStream.close()

                val batchDataStream: InputStream = assets.open("batch_data")
                val batchData = batchDataStream.readBytes()
                batchDataStream.close()

                // Process the batch data and prepare execOps for both benchmark and camera
                val parsedHashDataMap = parseHashDataMap(batchData)
                val operations = extractOperationsFromHashData(parsedHashDataMap)
                val (initOps, lastCopyIn) = separateOperations(operations)
                executeInitializationOps(this@MainActivity, initOps, parsedHashDataMap)
                // Store execOps here so it can be used by YourImageAnalyzer
                this.execOps = prepareExecutionOps(operations, h, parsedHashDataMap, lastCopyIn)

                // Execute operations in benchmark loop using the prepared execOps
                // Pass null for rgbData as we are not using camera data in this loop
                for (i in 1..10) {
                    val startTime = System.nanoTime()
                    executeOps(this@MainActivity, execOps!!) // Use '!!' because we know it's initialized here
                    val endTime = System.nanoTime()
                    val durationMs = (endTime - startTime) / 1_000_000.0
                    Log.d("MainActivity", "Run #$i took %.3f ms".format(durationMs))
                }

                // After benchmarks complete, request camera permission
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startCamera()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in background processing: ${e.message}")
            }
        }.start()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Add image analysis configuration
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        // Pass 'this@MainActivity' to YourImageAnalyzer so it can access 'execOps' and 'executeOps'
                        it.setAnalyzer(ContextCompat.getMainExecutor(this), YourImageAnalyzer(this@MainActivity))
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis  // Add image analysis to the bind list
                )

                Log.d("MainActivity", "Camera started successfully")
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Helper to get execOps, ensuring it's not null before use
    fun getExecOperations(): List<ExecOp>? {
        return execOps
    }
}

// Renamed and modified processBatchData to only prepare operations, not run the loop
fun prepareBatchOperations(activity: MainActivity, body: ByteArray): List<ExecOp> {
    val hashDataMap = parseHashDataMap(body)
    val operations = extractOperationsFromHashData(hashDataMap)
    val (initOps, lastCopyIn) = separateOperations(operations)
    executeInitializationOps(activity, initOps, hashDataMap)
    return prepareExecutionOps(operations, activity.h, hashDataMap, lastCopyIn)
}


fun parseHashDataMap(body: ByteArray): MutableMap<String, ByteArray> {
    val hashDataMap = mutableMapOf<String, ByteArray>()
    var ptr = 0
    while (ptr + 0x28 <= body.size) {
        val hash = body.copyOfRange(ptr, ptr + 0x20)
        val lenBytes = body.copyOfRange(ptr + 0x20, ptr + 0x28)
        val datalen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long.toInt()

        if (ptr + 0x28 + datalen > body.size) {
            Log.w("MainActivity", "Invalid datalen $datalen at ptr=$ptr, skipping rest.")
            break
        }

        val blob = body.copyOfRange(ptr + 0x28, ptr + 0x28 + datalen)
        val hex = hash.joinToString("") { "%02x".format(it) }
        hashDataMap[hex] = blob
        ptr += 0x28 + datalen
    }
    return hashDataMap
}

fun extractOperationsFromHashData(hashDataMap: Map<String, ByteArray>): List<String> {
    val finalBlob = hashDataMap.values.lastOrNull()
    val stringData = finalBlob?.toString(Charsets.UTF_8) ?: ""
    val cleanData = stringData.trim().removeSurrounding("[", "]")
    return cleanData.split("""(?<=\))\s*,\s*(?=\w+\()""".toRegex())
}

fun executeInitializationOps(activity: MainActivity, initOps: List<String>, hashDataMap: Map<String, ByteArray>) {
    try {
        if (initOps.size == 1 && initOps[0].trim().startsWith("GetProperties(")) {
            Log.d("MainActivity", "Got properties request - skipping in offline mode")
            return
        }

        for (op in initOps) {
            if (op.isBlank()) continue

            val trimmedOp = op.trim()
            val match = Regex("""(\w+)\((.*)\)""").find(trimmedOp) ?: continue

            when (match.groupValues[1]) {
                "ProgramAlloc" -> {
                    val params = parseOperationParams(match.groupValues[2])
                    val datahash = params["datahash"] ?: ""
                    val name = params["name"] ?: ""
                    activity.h[name] = hashDataMap[datahash] ?: byteArrayOf()
                }
                "BufferAlloc" -> {
                    val params = parseOperationParams(match.groupValues[2])
                    val bufferNum = params["buffer_num"] ?: "0"
                    val size = params["size"]?.toIntOrNull() ?: 0
                    activity.createBuffer(bufferNum, size)
                }
                "CopyIn" -> {
                    val params = parseOperationParams(match.groupValues[2])
                    val bufferNum = params["buffer_num"]?.toIntOrNull() ?: continue
                    val datahash = params["datahash"] ?: continue
                    hashDataMap[datahash]?.let { data ->
                        activity.uploadBuffer(bufferNum, data)
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "ERROR IN PROCESSING: ${e.stackTraceToString()}")
    }
}

fun prepareExecutionOps(
    operations: List<String>,
    programMap: Map<String, ByteArray>,
    hashDataMap: Map<String, ByteArray>,
    lastCopyIn: String?
): List<ExecOp> {
    return operations.filter {
        it.trim().startsWith("ProgramExec(") ||
                it.trim().startsWith("CopyOut(") ||
                (lastCopyIn != null && it.trim() == lastCopyIn.trim()) // Include the last CopyIn for execOps
    }.mapNotNull { op ->
        // Handle CopyIn separately for the last one
        if (op.trim() == lastCopyIn?.trim()) {
            parseCopyInOp(op, hashDataMap)
        } else {
            parseExecOp(op, programMap)
        }
    }
}

// Modify executeOps to accept optional rgbData
fun executeOps(activity: MainActivity, ops: List<ExecOp>, rgbData: ByteArray? = null) {
    ops.forEach { op ->
        when (op) {
            is ExecOp.ProgramExecOp -> {
                activity.runVulkanCompute(
                    op.shaderBytes,
                    op.dispatchSize,
                    op.bufferIndexes,
                    op.name
                )
            }
            is ExecOp.CopyOutOp -> {
                val bufferData = activity.getBuffer(op.bufferNum)
                val floatArray = FloatArray(bufferData.size / 4).apply {
                    ByteBuffer.wrap(bufferData)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(this)
                }
                Log.d("MainActivity", "COPYING OUT BUFFER: ${op.bufferNum}")
                Log.d("MainActivity", "buffer is [")
                for (i in 0 until floatArray.size / 6) {
                    val row = floatArray.sliceArray(i * 6 until (i + 1) * 6)
                    Log.d("MainActivity", "  [${row.joinToString(", ") { "%.4f".format(it) }}]")
                }
                Log.d("MainActivity", "]")
            }
            is ExecOp.CopyInOp -> {
                Log.d("MainActivity", "COPYING INTO BUFFER: ${op.bufferNum}")
                // Use rgbData if provided, otherwise fall back to op.data
                activity.uploadBuffer(op.bufferNum, rgbData ?: op.data)
                Log.d("MainActivity", "  op.data length: ${op.data.size} bytes")
            }
        }
    }
}

fun separateOperations(operations: List<String>): Pair<List<String>, String?> {
    val copyInOps = operations.filter { it.trim().startsWith("CopyIn(") }
    val lastCopyIn = copyInOps.lastOrNull()

    val initOps = operations.filter { op ->
        when {
            op.trim().startsWith("BufferAlloc(") -> true
            op.trim().startsWith("ProgramAlloc(") -> true
            // Only include CopyIn ops in initOps if they are NOT the lastCopyIn
            op.trim().startsWith("CopyIn(") -> op != lastCopyIn
            else -> false
        }
    }

    return Pair(initOps, lastCopyIn)
}

fun parseExecOp(op: String, programMap: Map<String, ByteArray>): ExecOp? {
    val trimmedOp = op.trim()
    val match = Regex("""(\w+)\((.*)\)""").find(trimmedOp) ?: return null

    return when (match.groupValues[1]) {
        "ProgramExec" -> {
            val params = parseOperationParams(match.groupValues[2])
            val name = params["name"] ?: return null
            val shaderBytes = programMap[name] ?: return null

            val dispatchSize = (params["global_size"]?.removeSurrounding("(", ")") ?: "1,1,1")
                .split(',')
                .map { it.trim().toIntOrNull() ?: 1 }
                .take(3)
                .toIntArray()

            val bufferIndexes = (params["bufs"]?.removeSurrounding("(", ")") ?: "")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toIntArray()

            ExecOp.ProgramExecOp(name, dispatchSize, bufferIndexes, shaderBytes)
        }
        "CopyOut" -> {
            val params = parseOperationParams(match.groupValues[2])
            ExecOp.CopyOutOp(params["buffer_num"] ?: "0")
        }
        else -> null // For cases like CopyIn that are handled separately
    }
}

fun parseCopyInOp(op: String, hashDataMap: Map<String, ByteArray>): ExecOp.CopyInOp? {
    val match = Regex("""CopyIn\((.*)\)""").find(op.trim()) ?: return null
    val params = parseOperationParams(match.groupValues[1])
    val bufferNum = params["buffer_num"]?.toIntOrNull() ?: return null
    val datahash = params["datahash"] ?: return null

    return hashDataMap[datahash]?.let { data ->
        ExecOp.CopyInOp(bufferNum, data)
    }
}

// This function is no longer needed in its original form as operations are separated
// fun executeOperations(activity: MainActivity, operations: List<String>, hashDataMap: Map<String, ByteArray>) { ... }

fun parseOperationParams(paramString: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    // Updated regex to correctly handle nested parentheses for global_size, etc.
    val pattern = """(\w+)=((?:\([^)]*\)|'[^']*'|"[^"]*"|[^,)]+)(?:\s*,\s*\([^)]*\))*)"""
    val regex = Regex(pattern)

    regex.findAll(paramString).forEach { match ->
        val key = match.groupValues[1]
        var value = match.groupValues[2].trim() // Trim to remove leading/trailing whitespace

        // Clean up quotes or parentheses only if they fully enclose the value
        value = when {
            value.startsWith("('") && value.endsWith("')") ->
                value.substring(2, value.length - 2) // Remove (' and ')
            value.startsWith("'") && value.endsWith("'") ->
                value.removeSurrounding("'")
            value.startsWith("\"") && value.endsWith("\"") ->
                value.removeSurrounding("\"")
            // Handle cases like "global_size=(1,1,1)" where value is "(1,1,1)"
            value.startsWith("(") && value.endsWith(")") ->
                value.removeSurrounding("(", ")")
            else -> value
        }
        params[key] = value
    }
    return params
}


private class YourImageAnalyzer(private val activity: MainActivity) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        // This is called for each frame
        // Log.d("CameraFrame", "New frame captured! ${image.width}x${image.height}") // This can be very chatty

        val rgbByteArray = image.image?.toRGBByteArray()
        val input = rgbByteArray?.let { ByteArray(it.size * 4) }
        if (rgbByteArray != null && input != null) {
            for (i in 0 until rgbByteArray.size / 4) {
                input[i * 3 * 4 + 0] = rgbByteArray[i * 4 + 0]
                input[i * 3 * 4 + 4] = rgbByteArray[i * 4 + 1]
                input[i * 3 * 4 + 8] = rgbByteArray[i * 4 + 2]
            }
        }
        // Get the prepared execOps from MainActivity
        val execOps = activity.getExecOperations()

        if (execOps != null) {
            val startTime = System.nanoTime()
            executeOps(activity, execOps, input) // Pass the rgb data here
            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            Log.d("ImageAnalysis", "Frame processing took %.3f ms".format(durationMs))
        }

        // Make sure to close the image when done
        image.close()
    }
}

// Add this extension function outside the MainActivity class
private fun Image.toRGBByteArray(): ByteArray {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize) // VU order for NV21
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)

    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val jpegBytes = out.toByteArray()

    // Decode to RGB Bitmap
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    val rgbBuffer = ByteBuffer.allocate(bitmap.byteCount)
    bitmap.copyPixelsToBuffer(rgbBuffer)

    val signedRgbBytes = rgbBuffer.array()
    val unsignedRgbBytes = ByteArray(signedRgbBytes.size) { i ->
        (signedRgbBytes[i].toInt() and 0xFF).toByte()
    }

    return unsignedRgbBytes
}