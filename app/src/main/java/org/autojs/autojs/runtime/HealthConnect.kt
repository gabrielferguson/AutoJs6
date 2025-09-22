package org.autojs.autojs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.autojs.autojs.annotation.ScriptInterface
import org.autojs.autojs.extension.ArrayExtensions.toNativeArray
import org.autojs.autojs.extension.ArrayExtensions.toNativeObject
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.util.RhinoUtils.coerceString
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Health Connect API 包装器
 * 为 AutoJs6 脚本提供睡眠数据读写功能
 */
class HealthConnect(private val context: Context, private val scriptRuntime: ScriptRuntime) {

    companion object {
        private const val TAG = "HealthConnect"
        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        
        // Health Connect 权限
        private val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class)
        )
    }

    private var healthConnectClient: HealthConnectClient? = null

    init {
        initializeHealthConnect()
    }

    /**
     * 初始化 Health Connect 客户端
     */
    private fun initializeHealthConnect() {
        try {
            when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    healthConnectClient = HealthConnectClient.getOrCreate(context)
                    Log.d(TAG, "Health Connect client initialized successfully")
                }
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    Log.w(TAG, "Health Connect SDK is unavailable")
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Log.w(TAG, "Health Connect SDK requires provider update")
                }
                else -> {
                    Log.w(TAG, "Health Connect SDK status unknown")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Health Connect", e)
        }
    }

    /**
     * 检查 Health Connect 是否可用
     */
    @ScriptInterface
    fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect availability", e)
            false
        }
    }

    /**
     * 检查是否已授予所需权限
     */
    @ScriptInterface
    fun hasPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        
        return try {
            runBlocking {
                val grantedPermissions = client.permissionController.getGrantedPermissions()
                REQUIRED_PERMISSIONS.all { permission ->
                    grantedPermissions.contains(permission)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    /**
     * 请求 Health Connect 权限
     */
    @ScriptInterface
    fun requestPermissions() {
        if (!isAvailable()) {
            throw IllegalStateException("Health Connect is not available on this device")
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW_PERMISSION_USAGE)
                .setPackage(HEALTH_CONNECT_PACKAGE)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Health Connect permissions", e)
            openHealthConnectSettings()
        }
    }

    /**
     * 打开 Health Connect 设置页面
     */
    @ScriptInterface
    fun openHealthConnectSettings() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE"))
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Health Connect settings", e)
            throw RuntimeException("无法打开 Health Connect 设置页面", e)
        }
    }

    /**
     * 读取睡眠记录
     */
    @ScriptInterface
    @JvmOverloads
    fun getSleepRecords(days: Int = 7): NativeArray {
        if (!isAvailable()) {
            throw IllegalStateException("Health Connect is not available")
        }

        if (!hasPermissions()) {
            throw IllegalStateException("Health Connect permissions not granted. Call requestPermissions() first.")
        }

        val client = healthConnectClient ?: throw IllegalStateException("Health Connect client not initialized")

        return try {
            runBlocking {
                val endTime = Instant.now()
                val startTime = endTime.minusSeconds(days * 24 * 3600L)

                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )

                val response = client.readRecords(request)
                val sleepRecords = mutableListOf<NativeObject>()

                for (record in response.records) {
                    val sleepRecord = mutableMapOf<String, Any?>()
                    
                    sleepRecord["id"] = record.metadata.id
                    sleepRecord["startTime"] = formatInstant(record.startTime)
                    sleepRecord["endTime"] = formatInstant(record.endTime)
                    sleepRecord["duration"] = record.endTime.epochSecond - record.startTime.epochSecond
                    
                    // 添加睡眠阶段信息（如果可用）
                    if (record.stages.isNotEmpty()) {
                        val stages = record.stages.map { stage ->
                            mapOf(
                                "stage" to stage.stage.toString(), // 修复：使用 toString() 
                                "startTime" to formatInstant(stage.startTime),
                                "endTime" to formatInstant(stage.endTime)
                            ).toNativeObject()
                        }
                        sleepRecord["stages"] = stages.toNativeArray()
                    }

                    sleepRecord["title"] = record.title ?: ""
                    sleepRecord["notes"] = record.notes ?: ""

                    sleepRecords.add(sleepRecord.toNativeObject())
                }

                Log.d(TAG, "Retrieved ${sleepRecords.size} sleep records")
                sleepRecords.toNativeArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sleep records", e)
            throw RuntimeException("读取睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 写入睡眠记录
     */
    @ScriptInterface
    fun writeSleepRecord(sleepData: Any): Boolean {
        if (!isAvailable()) {
            throw IllegalStateException("Health Connect is not available")
        }

        if (!hasPermissions()) {
            throw IllegalStateException("Health Connect permissions not granted. Call requestPermissions() first.")
        }

        val client = healthConnectClient ?: throw IllegalStateException("Health Connect client not initialized")

        return try {
            require(sleepData is Map<*, *>) { "Sleep data must be an object with startTime and endTime" }
            val dataMap = sleepData as Map<String, Any?>

            val startTimeStr = coerceString(dataMap["startTime"])
            val endTimeStr = coerceString(dataMap["endTime"])

            if (startTimeStr.isEmpty() || endTimeStr.isEmpty()) {
                throw IllegalArgumentException("startTime and endTime are required")
            }

            val startTime = parseDateTime(startTimeStr)
            val endTime = parseDateTime(endTimeStr)

            if (endTime.isBefore(startTime) || endTime == startTime) { // 修复：使用 == 
                throw IllegalArgumentException("endTime must be after startTime")
            }

            runBlocking {
                // Create SleepSessionRecord with required parameters for Health Connect 1.1.0-beta01
                val sleepRecord = SleepSessionRecord(
                    startTime = startTime,
                    startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                    endTime = endTime,
                    endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime),
                    title = coerceString(dataMap["title"] ?: "AutoJs6 Sleep Record"),
                    notes = coerceString(dataMap["notes"] ?: ""),
                    metadata = Metadata.manualEntry() // 1.1.0-alpha12 及以后必填
                )

                client.insertRecords(listOf(sleepRecord))
                Log.d(TAG, "Sleep record written successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep record", e)
            throw RuntimeException("写入睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 删除睡眠记录
     */
    @ScriptInterface
    fun deleteSleepRecord(recordId: String): Boolean {
        if (!isAvailable()) {
            throw IllegalStateException("Health Connect is not available")
        }

        if (!hasPermissions()) {
            throw IllegalStateException("Health Connect permissions not granted")
        }

        val client = healthConnectClient ?: throw IllegalStateException("Health Connect client not initialized")

        return try {
            runBlocking {
                client.deleteRecords(
                    recordType = SleepSessionRecord::class,
                    idList = listOf(recordId),
                    clientRecordIdsList = emptyList()
                )
                Log.d(TAG, "Sleep record deleted successfully: $recordId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete sleep record", e)
            throw RuntimeException("删除睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 获取睡眠统计信息
     */
    @ScriptInterface
    fun getSleepStats(days: Int = 7): NativeObject {
        val records = getSleepRecords(days)
        val stats = mutableMapOf<String, Any?>()

        if (records.size == 0) {
            stats["totalRecords"] = 0
            stats["averageDuration"] = 0
            stats["totalDuration"] = 0
            return stats.toNativeObject()
        }

        var totalDuration = 0L
        var minDuration = Long.MAX_VALUE
        var maxDuration = 0L

        for (i in 0 until records.size) {
            val record = records[i] as NativeObject
            val duration = record["duration"] as Long
            totalDuration += duration
            minDuration = minOf(minDuration, duration)
            maxDuration = maxOf(maxDuration, duration)
        }

        stats["totalRecords"] = records.size
        stats["totalDuration"] = totalDuration
        stats["averageDuration"] = totalDuration / records.size
        stats["minDuration"] = minDuration
        stats["maxDuration"] = maxDuration
        stats["averageHours"] = (totalDuration / records.size) / 3600.0

        return stats.toNativeObject()
    }

    private fun formatInstant(instant: Instant): String {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun parseDateTime(dateTimeStr: String): Instant {
        return try {
            when {
                dateTimeStr.contains("T") -> {
                    if (dateTimeStr.endsWith("Z")) {
                        Instant.parse(dateTimeStr)
                    } else {
                        Instant.parse("${dateTimeStr}Z")
                    }
                }
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) -> {
                    LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toInstant()
                }
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) -> {
                    LocalDateTime.parse("$dateTimeStr:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault()).toInstant()
                }
                else -> {
                    throw IllegalArgumentException("Unsupported date format: $dateTimeStr")
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid date format: $dateTimeStr. Use 'yyyy-MM-dd HH:mm:ss' or ISO format", e)
        }
    }
}
