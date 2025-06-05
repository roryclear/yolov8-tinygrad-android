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

                // Process the batch data and run benchmarks
                processBatchData(this@MainActivity, batchData)

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
                        it.setAnalyzer(ContextCompat.getMainExecutor(this), YourImageAnalyzer())
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
}

fun processBatchData(activity: MainActivity, body: ByteArray) {
    // 1. Parse binary data into hash map
    val hashDataMap = parseHashDataMap(body)

    // 2. Extract and clean operations
    val operations = extractOperationsFromHashData(hashDataMap)

    // 3. Separate and execute initialization operations
    val (initOps, lastCopyIn) = separateOperations(operations)
    executeInitializationOps(activity, initOps, hashDataMap)

    // 4. Prepare execution operations
    val execOps = prepareExecutionOps(operations, activity.h, hashDataMap, lastCopyIn)

    // 5. Execute operations in benchmark loop
    for (i in 1..100) {
        val startTime = System.nanoTime()
        executeOps(activity, execOps)
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000.0
        Log.d("MainActivity", "Run #$i took %.3f ms".format(durationMs))
    }
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
                it.trim().startsWith("CopyOut(")
    }.mapNotNull { op ->
        parseExecOp(op, programMap)
    } + listOfNotNull(lastCopyIn?.let { parseCopyInOp(it, hashDataMap) })
}

fun executeOps(activity: MainActivity, ops: List<ExecOp>) {
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
                activity.uploadBuffer(op.bufferNum, op.data)
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
        else -> null
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

fun executeOperations(activity: MainActivity, operations: List<String>, hashDataMap: Map<String, ByteArray>) {
    try {
        if (operations.size == 1 && operations[0].trim().startsWith("GetProperties(")) {
            Log.d("MainActivity", "Got properties request - skipping in offline mode")
            return
        }

        for (op in operations) {
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

fun parseOperationParams(paramString: String): Map<String, String> {
    val params = mutableMapOf<String, String>()
    val pattern = """(\w+)=(\(.*?\)|'[^']*'|"[^"]*"|[^,)]+)"""
    val regex = Regex(pattern)

    regex.findAll(paramString).forEach { match ->
        val key = match.groupValues[1]
        var value = match.groupValues[2]

        value = when {
            value.startsWith("('") && value.endsWith("')") ->
                value.removeSurrounding("('").removeSurrounding("')")
            value.startsWith("('") && value.endsWith("')") ->
                value.removeSurrounding("('").removeSurrounding("')")
            value.startsWith("'") && value.endsWith("'") ->
                value.removeSurrounding("'")
            value.startsWith("\"") && value.endsWith("\"") ->
                value.removeSurrounding("\"")
            else -> value
        }

        params[key] = value
    }

    return params
}

private class YourImageAnalyzer : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        // This is called for each frame
        Log.d("CameraFrame", "New frame captured! ${image.width}x${image.height}")

        // You can access the image data like this:
        val imageData = image.image?.toByteArray()
        // imageData contains the raw frame data

        // Make sure to close the image when done
        image.close()
    }
}

// Add this extension function outside the MainActivity class
private fun Image.toByteArray(): ByteArray {
    val planes = this.planes
    val buffer: ByteBuffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}