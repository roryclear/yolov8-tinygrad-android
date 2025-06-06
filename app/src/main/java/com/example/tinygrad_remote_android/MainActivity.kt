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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

val yoloClasses = listOf(
    Pair("person", Color.RED),
    Pair("bicycle", Color.GREEN),
    Pair("car", Color.BLUE),
    Pair("motorcycle", Color.CYAN),
    Pair("airplane", Color.MAGENTA),
    Pair("bus", Color.YELLOW),
    Pair("train", Color.parseColor("#FFA500")), // Orange
    Pair("truck", Color.parseColor("#800080")), // Purple
    Pair("boat", Color.parseColor("#A52A2A")), // Brown
    Pair("traffic light", Color.parseColor("#7FB33F")), // RGB(0.5, 0.7, 0.2)
    Pair("fire hydrant", Color.parseColor("#CC1A1A")), // RGB(0.8, 0.1, 0.1)
    Pair("stop sign", Color.parseColor("#4D4DCC")), // RGB(0.3, 0.3, 0.8)
    Pair("parking meter", Color.parseColor("#B38C4D")), // RGB(0.7, 0.5, 0.3)
    Pair("bench", Color.parseColor("#666633")), // RGB(0.4, 0.4, 0.2)
    Pair("bird", Color.parseColor("#1A80E6")), // RGB(0.1, 0.5, 0.9)
    Pair("cat", Color.parseColor("#CC33CC")), // RGB(0.8, 0.2, 0.6)
    Pair("dog", Color.parseColor("#E64D4D")), // RGB(0.9, 0.3, 0.3)
    Pair("horse", Color.parseColor("#3399CC")), // RGB(0.2, 0.6, 0.7)
    Pair("sheep", Color.parseColor("#B34D80")), // RGB(0.7, 0.3, 0.5)
    Pair("cow", Color.parseColor("#66CC66")), // RGB(0.4, 0.8, 0.4)
    Pair("elephant", Color.parseColor("#4D66E6")), // RGB(0.3, 0.4, 0.9)
    Pair("bear", Color.parseColor("#9933CC")), // RGB(0.6, 0.2, 0.8)
    Pair("zebra", Color.parseColor("#CC8033")), // RGB(0.8, 0.5, 0.2)
    Pair("giraffe", Color.parseColor("#80E61A")), // RGB(0.5, 0.9, 0.1)
    Pair("backpack", Color.parseColor("#4DB366")), // RGB(0.3, 0.7, 0.4)
    Pair("umbrella", Color.parseColor("#6699E6")), // RGB(0.4, 0.6, 0.9)
    Pair("handbag", Color.parseColor("#E63380")), // RGB(0.9, 0.2, 0.5)
    Pair("tie", Color.parseColor("#804DB3")), // RGB(0.5, 0.3, 0.7)
    Pair("suitcase", Color.parseColor("#99B333")), // RGB(0.6, 0.7, 0.2)
    Pair("frisbee", Color.parseColor("#B33366")), // RGB(0.7, 0.2, 0.4)
    Pair("skis", Color.parseColor("#4DE64D")), // RGB(0.3, 0.9, 0.3)
    Pair("snowboard", Color.parseColor("#CC1A99")), // RGB(0.8, 0.1, 0.6)
    Pair("sports ball", Color.parseColor("#664DCC")), // RGB(0.4, 0.3, 0.8)
    Pair("kite", Color.parseColor("#3380CC")), // RGB(0.2, 0.5, 0.7)
    Pair("baseball bat", Color.parseColor("#996633")), // RGB(0.6, 0.4, 0.2)
    Pair("baseball glove", Color.parseColor("#B31A66")), // RGB(0.7, 0.1, 0.4)
    Pair("skateboard", Color.parseColor("#80CC80")), // RGB(0.5, 0.8, 0.5)
    Pair("surfboard", Color.parseColor("#CC4D99")), // RGB(0.8, 0.3, 0.6)
    Pair("tennis racket", Color.parseColor("#33B3E6")), // RGB(0.2, 0.7, 0.9)
    Pair("bottle", Color.parseColor("#E6334D")), // RGB(0.9, 0.2, 0.3)
    Pair("wine glass", Color.parseColor("#99994D")), // RGB(0.6, 0.6, 0.3)
    Pair("cup", Color.parseColor("#4D66E6")), // RGB(0.3, 0.4, 0.9)
    Pair("fork", Color.parseColor("#66B333")), // RGB(0.4, 0.7, 0.2)
    Pair("knife", Color.parseColor("#CC3380")), // RGB(0.8, 0.2, 0.5)
    Pair("spoon", Color.parseColor("#994DB3")), // RGB(0.6, 0.3, 0.7)
    Pair("bowl", Color.parseColor("#33CC66")), // RGB(0.2, 0.8, 0.4)
    Pair("banana", Color.parseColor("#B3B31A")), // RGB(0.7, 0.7, 0.1)
    Pair("apple", Color.parseColor("#E61A66")), // RGB(0.9, 0.1, 0.4)
    Pair("sandwich", Color.parseColor("#6680CC")), // RGB(0.4, 0.5, 0.8)
    Pair("orange", Color.parseColor("#CC9933")), // RGB(0.8, 0.6, 0.2)
    Pair("broccoli", Color.parseColor("#4DCC4D")), // RGB(0.3, 0.8, 0.3)
    Pair("carrot", Color.parseColor("#B33399")), // RGB(0.7, 0.2, 0.6)
    Pair("hot dog", Color.parseColor("#E64D80")), // RGB(0.9, 0.3, 0.5)
    Pair("pizza", Color.parseColor("#804DCC")), // RGB(0.5, 0.3, 0.8)
    Pair("donut", Color.parseColor("#CC1A66")), // RGB(0.8, 0.1, 0.4)
    Pair("cake", Color.parseColor("#B3801A")), // RGB(0.7, 0.5, 0.1)
    Pair("chair", Color.parseColor("#993366")), // RGB(0.6, 0.2, 0.4)
    Pair("couch", Color.parseColor("#669933")), // RGB(0.4, 0.6, 0.2)
    Pair("potted plant", Color.parseColor("#CC6699")), // RGB(0.8, 0.4, 0.5)
    Pair("bed", Color.parseColor("#4DB3B3")), // RGB(0.3, 0.7, 0.7)
    Pair("dining table", Color.parseColor("#80CC4D")), // RGB(0.5, 0.8, 0.3)
    Pair("toilet", Color.parseColor("#B36699")), // RGB(0.7, 0.4, 0.6)
    Pair("tv", Color.parseColor("#E68033")), // RGB(0.9, 0.5, 0.2)
    Pair("laptop", Color.parseColor("#994DB3")), // RGB(0.6, 0.3, 0.7)
    Pair("mouse", Color.parseColor("#33E680")), // RGB(0.2, 0.9, 0.5)
    Pair("remote", Color.parseColor("#CC664D")), // RGB(0.8, 0.4, 0.3)
    Pair("keyboard", Color.parseColor("#4D99CC")), // RGB(0.3, 0.6, 0.8)
    Pair("cell phone", Color.parseColor("#B34DE6")), // RGB(0.7, 0.3, 0.9)
    Pair("microwave", Color.parseColor("#66E666")), // RGB(0.4, 0.9, 0.4)
    Pair("oven", Color.parseColor("#80B333")), // RGB(0.5, 0.7, 0.2)
    Pair("toaster", Color.parseColor("#E6334D")), // RGB(0.9, 0.2, 0.3)
    Pair("sink", Color.parseColor("#99CC4D")), // RGB(0.6, 0.8, 0.3)
    Pair("refrigerator", Color.parseColor("#CC66B3")), // RGB(0.8, 0.4, 0.7)
    Pair("book", Color.parseColor("#4D80E6")), // RGB(0.3, 0.5, 0.9)
    Pair("clock", Color.parseColor("#B3B31A")), // RGB(0.7, 0.7, 0.2)
    Pair("vase", Color.parseColor("#E66680")), // RGB(0.9, 0.4, 0.5)
    Pair("scissors", Color.parseColor("#33B3CC")), // RGB(0.2, 0.7, 0.8)
    Pair("teddy bear", Color.parseColor("#994DE6")), // RGB(0.6, 0.3, 0.9)
    Pair("hair drier", Color.parseColor("#CC334D")), // RGB(0.8, 0.2, 0.3)
    Pair("toothbrush", Color.parseColor("#66B399")) // RGB(0.4, 0.7, 0.6)
)

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
    private lateinit var analyzerThread: HandlerThread
    private lateinit var analyzerHandler: Handler
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

        analyzerThread = HandlerThread("AnalyzerThread")
        analyzerThread.start()
        analyzerHandler = Handler(analyzerThread.looper)

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
                for (i in 1..100) {
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

    fun getAnalyzerHandler(): Handler = analyzerHandler

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
                val countMap = mutableMapOf<String, Int>()

                for (i in 0 until floatArray.size / 6) {
                    val row = floatArray.sliceArray(i * 6 until (i + 1) * 6)

                    val classIndex = row[5].toInt()
                    val className = yoloClasses.getOrNull(classIndex)?.first ?: "Unknown"

                    if (row[4] >= 0.25f) {
                        countMap[className] = countMap.getOrDefault(className, 0) + 1
                    }

                    //Log.d("MainActivity", "[${row.take(5).joinToString(", ") { "%.4f".format(it) }}, $className]")
                }

                Log.d("MainActivity", "Class counts:")
                for ((name, count) in countMap) {
                    Log.d("MainActivity", "$name: $count")
                }
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

    private var frameCount = 0
    private var lastFpsTimestampNs = System.nanoTime()

    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image ?: run {
            image.close()
            return
        }

        activity.getAnalyzerHandler().post {
            val rgbByteArray = mediaImage.toRGBByteArray()
            val input = ByteArray(rgbByteArray.size * 4)

            for (i in 0 until rgbByteArray.size / 4) {
                input[i * 3 * 4 + 0] = rgbByteArray[i * 4 + 0]
                input[i * 3 * 4 + 4] = rgbByteArray[i * 4 + 1]
                input[i * 3 * 4 + 8] = rgbByteArray[i * 4 + 2]
            }

            val execOps = activity.getExecOperations()
            if (execOps != null) {
                val startTime = System.nanoTime()
                executeOps(activity, execOps, input)
                val endTime = System.nanoTime()
                val durationMs = (endTime - startTime) / 1_000_000.0
                Log.d("ImageAnalysis", "Frame processing took %.3f ms".format(durationMs))
            }

            // FPS tracking with decimal precision
            frameCount++
            val nowNs = System.nanoTime()
            val elapsedSec = (nowNs - lastFpsTimestampNs) / 1_000_000_000.0

            if (elapsedSec >= 1.0) {
                val fps = frameCount / elapsedSec
                Log.d("ImageAnalysis", "FPS: %.2f".format(fps))
                frameCount = 0
                lastFpsTimestampNs = nowNs
            }

            image.close()
        }
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