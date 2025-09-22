package org.autojs.autojs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord.Stage
import androidx.health.connect.client.records.metadata.Device
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
import kotlin.math.max

/**
 * Health Connect API 包装器
 * 为 AutoJs6 脚本提供睡眠数据读写功能
 *
 * 变更要点：
 * - 支持 13 位毫秒时间戳（startTimeMillis / endTimeMillis），兼容原有字符串时间
 * - 支持 stages 列表写入/读取（毫秒级）
 * - 支持用户自定义 metadata 记录方式（manual/auto/active/unknown）与设备类型
 * - 读取返回同时包含毫秒时间戳与可读字符串
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

        private val STAGE_NAME_TO_CONST = mapOf(
            "UNKNOWN" to SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            "AWAKE" to SleepSessionRecord.STAGE_TYPE_AWAKE,
            "SLEEPING" to SleepSessionRecord.STAGE_TYPE_SLEEPING,
            "OUT_OF_BED" to SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            "AWAKE_IN_BED" to SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            "LIGHT" to SleepSessionRecord.STAGE_TYPE_LIGHT,
            "DEEP" to SleepSessionRecord.STAGE_TYPE_DEEP,
            "REM" to SleepSessionRecord.STAGE_TYPE_REM
        )

        private val STAGE_CONST_TO_NAME = STAGE_NAME_TO_CONST.entries.associate { it.value to it.key }

        private val DEVICE_NAME_TO_TYPE = mapOf(
            "UNKNOWN" to Device.TYPE_UNKNOWN,
            "WATCH" to Device.TYPE_WATCH,
            "PHONE" to Device.TYPE_PHONE,
            "SCALE" to Device.TYPE_SCALE,
            "RING" to Device.TYPE_RING,
            "HEAD_MOUNTED" to Device.TYPE_HEAD_MOUNTED,
            "FITNESS_BAND" to Device.TYPE_FITNESS_BAND,
            "CHEST_STRAP" to Device.TYPE_CHEST_STRAP,
            "SMART_DISPLAY" to Device.TYPE_SMART_DISPLAY
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
                REQUIRED_PERMISSIONS.all { it in grantedPermissions }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    /**
     * 打开 Health Connect 权限页（或设置页兜底）
     */
    @ScriptInterface
    fun requestPermissions() {
        if (!isAvailable()) error("Health Connect is not available on this device")
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
     * @param options 可选参数对象：
     *  {
     *    startTimeMillis?: Long, // 13位
     *    endTimeMillis?: Long,   // 13位，默认 = now
     *    days?: Int,             // 与毫秒区间二选一，默认 7
     *  }
     */
    @ScriptInterface
    @JvmOverloads
    fun getSleepRecords(options: Any? = null): NativeArray {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted. Call requestPermissions() first.")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            runBlocking {
                // 解析过滤区间
                val (start, end) = parseReadWindow(options)

                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )

                val response = client.readRecords(request)
                val result = mutableListOf<NativeObject>()

                for (record in response.records) {
                    val obj = mutableMapOf<String, Any?>()

                    obj["id"] = record.metadata.id
                    obj["title"] = record.title ?: ""
                    obj["notes"] = record.notes ?: ""

                    // 返回两种时间表示
                    obj["startTime"] = formatInstant(record.startTime)
                    obj["endTime"] = formatInstant(record.endTime)
                    obj["startTimeMillis"] = record.startTime.toEpochMilli()
                    obj["endTimeMillis"] = record.endTime.toEpochMilli()
                    obj["durationSec"] = record.endTime.epochSecond - record.startTime.epochSecond

                    // 阶段
                    if (record.stages.isNotEmpty()) {
                        val stages = record.stages.map { st ->
                            mapOf(
                                "stage" to (STAGE_CONST_TO_NAME[st.stage] ?: st.stage.toString()),
                                "stageConst" to st.stage,
                                "startTime" to formatInstant(st.startTime),
                                "endTime" to formatInstant(st.endTime),
                                "startTimeMillis" to st.startTime.toEpochMilli(),
                                "endTimeMillis" to st.endTime.toEpochMilli()
                            ).toNativeObject()
                        }
                        obj["stages"] = stages.toNativeArray()
                    } else {
                        obj["stages"] = NativeArray(0)
                    }

                    // 元数据（可读）
                    obj["metadata"] = mapOf(
                        "recordingMethod" to recordingMethodName(record.metadata),
                        "clientRecordId" to record.metadata.clientRecordId,
                        "clientRecordVersion" to record.metadata.clientRecordVersion,
                        "deviceType" to deviceTypeName(record.metadata.device?.type),
                        "lastModifiedTime" to formatInstant(record.metadata.lastModifiedTime),
                        "lastModifiedTimeMillis" to record.metadata.lastModifiedTime?.toEpochMilli()
                    ).toNativeObject()

                    result.add(obj.toNativeObject())
                }

                Log.d(TAG, "Retrieved ${result.size} sleep records")
                result.toNativeArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sleep records", e)
            throw RuntimeException("读取睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 写入睡眠记录
     *
     * @param sleepData 形如：
     * {
     *   // 必填：传入 13位毫秒时间戳；也兼容旧字段 "startTime"/"endTime" 字符串
     *   startTimeMillis: 1726950000000,
     *   endTimeMillis:   1726980000000,
     *
     *   title?: "My Sleep",
     *   notes?: "Hello",
     *
     *   // 可选：阶段列表（推荐）
     *   stages: [
     *     { stage: "LIGHT", startTimeMillis: 1726950000000, endTimeMillis: 1726957200000 },
     *     { stage: "DEEP",  startTimeMillis: 1726957200000, endTimeMillis: 1726960800000 },
     *     { stage: "REM",   startTimeMillis: 1726960800000, endTimeMillis: 1726963200000 }
     *   ],
     *
     *   // 可选：metadata 配置（记录方式 + 设备）
     *   metadata: {
     *     method: "manual" | "auto" | "active" | "unknown",
     *     deviceType?: "PHONE" | "WATCH" | ...,
     *     clientRecordId?: "xxx",
     *     clientRecordVersion?: 1,
     *     id?: "fixed-record-id" // 可选，若指定则用 *WithId 变体
     *   },
     *
     *   // 可选：时区（例如 "Asia/Shanghai"），默认系统
     *   zoneId?: "Asia/Shanghai"
     * }
     */
    @ScriptInterface
    fun writeSleepRecord(sleepData: Any): Boolean {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted. Call requestPermissions() first.")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            val map = anyToMap(sleepData)

            // 1) 解析时区
            val zoneId = (map["zoneId"]?.toString()?.takeIf { it.isNotBlank() })?.let { ZoneId.of(it) }
                ?: ZoneId.systemDefault()

            // 2) 解析时间（毫秒优先）
            val start = parseAnyToInstant(map["startTimeMillis"] ?: map["startTime"], zoneId, "startTime")
            val end = parseAnyToInstant(map["endTimeMillis"] ?: map["endTime"], zoneId, "endTime")
            require(end.isAfter(start)) { "endTime must be after startTime" }

            // 3) 解析 stages
            val stagesInput = map["stages"]
            val stages: List<Stage> = parseStages(stagesInput, zoneId, start, end)

            // 4) 解析 metadata
            val meta: Metadata = buildMetadata(anyToMap(map["metadata"]), start)

            // 5) 构造并写入
            runBlocking {
                val record = SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = zoneId.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zoneId.rules.getOffset(end),
                    title = coerceString(map["title"] ?: "AutoJs6 Sleep Record"),
                    notes = coerceString(map["notes"] ?: ""),
                    stages = stages,
                    metadata = meta
                )
                client.insertRecords(listOf(record))
                Log.d(TAG, "Sleep record written successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep record", e)
            throw RuntimeException("写入睡眠数据失败: ${e.message}", e)
        }
    }

    /**
     * 删除睡眠记录（按 ID）
     */
    @ScriptInterface
    fun deleteSleepRecord(recordId: String): Boolean {
        if (!isAvailable()) error("Health Connect is not available")
        if (!hasPermissions()) error("Health Connect permissions not granted")
        val client = healthConnectClient ?: error("Health Connect client not initialized")

        return try {
            runBlocking {
                client.deleteRecords(
                    recordType = SleepSessionRecord::class,
                    recordIdsList = listOf(recordId),
                    clientRecordIdsList = emptyList<String>()
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
     * 获取睡眠统计信息（保留原逻辑，duration 改为使用毫秒时间戳计算更稳妥）
     */
    @ScriptInterface
    fun getSleepStats(days: Int = 7): NativeObject {
        val records = getSleepRecords(mapOf("days" to days))
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
            val dur = (record["endTimeMillis"] as? Long ?: 0L) - (record["startTimeMillis"] as? Long ?: 0L)
            totalDuration += max(0L, dur)
            minDuration = minOf(minDuration, max(0L, dur))
            maxDuration = maxOf(maxDuration, max(0L, dur))
        }
        stats["totalRecords"] = records.size
        stats["totalDuration"] = totalDuration / 1000 // 秒
        stats["averageDuration"] = (totalDuration / records.size) / 1000
        stats["minDuration"] = minDuration / 1000
        stats["maxDuration"] = maxDuration / 1000
        stats["averageHours"] = (totalDuration / records.size) / 1000.0 / 3600.0
        return stats.toNativeObject()
    }

    // -------------------- 工具方法 --------------------

    private fun parseReadWindow(options: Any?): Pair<Instant, Instant> {
        val map = anyToMap(options)
        val now = Instant.now()
        val end = (map["endTimeMillis"] as? Number)?.toLong()?.let { Instant.ofEpochMilli(it) } ?: now
        val start = (map["startTimeMillis"] as? Number)?.toLong()?.let { Instant.ofEpochMilli(it) }
            ?: run {
                val days = (map["days"] as? Number)?.toInt() ?: 7
                end.minusSeconds(days * 24 * 3600L)
            }
        return start to end
    }

    private fun formatInstant(instant: Instant?): String {
        if (instant == null) return ""
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun parseAnyToInstant(value: Any?, zoneId: ZoneId, name: String): Instant {
        require(value != null) { "$name is required" }
        return when (value) {
            is Number -> Instant.ofEpochMilli(value.toLong())
            is String -> {
                val v = value.trim()
                // 纯数字字符串，支持 13 位毫秒或 10 位秒
                if (v.matches(Regex("\\d{13}"))) {
                    Instant.ofEpochMilli(v.toLong())
                } else if (v.matches(Regex("\\d{10}"))) {
                    Instant.ofEpochSecond(v.toLong())
                } else {
                    parseDateTime(v, zoneId) // 兼容旧格式
                }
            }
            is NativeObject -> parseAnyToInstant(coerceString(value), zoneId, name)
            else -> error("Unsupported $name format: $value")
        }
    }

    private fun parseDateTime(dateTimeStr: String, zoneId: ZoneId): Instant {
        return try {
            when {
                dateTimeStr.contains("T") -> java.time.OffsetDateTime.parse(dateTimeStr).toInstant()
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) ->
                    LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(zoneId).toInstant()
                dateTimeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) ->
                    LocalDateTime.parse("$dateTimeStr:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(zoneId).toInstant()
                else -> error("Unsupported date format: $dateTimeStr")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid date format: $dateTimeStr. Use epochMillis(13位) 或 'yyyy-MM-dd HH:mm:ss' / ISO 格式", e)
        }
    }

    private fun parseStages(
        input: Any?,
        zoneId: ZoneId,
        sessionStart: Instant,
        sessionEnd: Instant
    ): List<Stage> {
        if (input == null) return emptyList()
        val list = when (input) {
            is NativeArray -> (0 until input.length).mapNotNull { input[it] }.toList()
            is List<*> -> input
            else -> error("stages must be an array")
        }

        return list.mapIndexed { idx, it ->
            val m = anyToMap(it)
            val s = parseAnyToInstant(m["startTimeMillis"] ?: m["startTime"], zoneId, "stages[$idx].startTime")
            val e = parseAnyToInstant(m["endTimeMillis"] ?: m["endTime"], zoneId, "stages[$idx].endTime")
            require(e.isAfter(s)) { "stages[$idx] endTime must be after startTime" }

            // 阶段支持字符串或数值常量
            val stageConst = when (val st = m["stage"]) {
                is Number -> st.toInt()
                is String -> {
                    val key = st.trim().uppercase()
                    STAGE_NAME_TO_CONST[key] ?: error("Unknown stage: $st")
                }
                else -> error("stages[$idx].stage is required")
            }

            // 可选：校验在会话范围内
            require(!s.isBefore(sessionStart) && !e.isAfter(sessionEnd)) {
                "stages[$idx] must be within session window"
            }

            Stage(
                startTime = s,
                endTime = e,
                stage = stageConst
            )
        }
    }

    private fun buildMetadata(meta: Map<String, Any?>, sessionStart: Instant): Metadata {
        if (meta.isEmpty()) {
            // 默认：手动输入
            return Metadata.manualEntry()
        }

        val method = meta["method"]?.toString()?.trim()?.lowercase() ?: "manual"
        val deviceTypeName = meta["deviceType"]?.toString()?.trim()?.uppercase()
        val device = deviceTypeName?.let { name ->
            val type = DEVICE_NAME_TO_TYPE[name]
                ?: error("Unknown deviceType: $name")
            Device(type = type)
        }

        val clientRecordId = meta["clientRecordId"]?.toString()
        val clientRecordVersion = (meta["clientRecordVersion"] as? Number)?.toLong() ?: 0L
        val fixedId = meta["id"]?.toString()

        return when (method) {
            "active", "actively_recorded" -> {
                if (fixedId != null) {
                    requireNotNull(device) { "activelyRecordedWithId requires deviceType" }
                    Metadata.activelyRecordedWithId(id = fixedId, device = device)
                } else if (clientRecordId != null) {
                    requireNotNull(device) { "activelyRecorded(clientRecordId..) requires deviceType" }
                    Metadata.activelyRecorded(clientRecordId, clientRecordVersion, device)
                } else {
                    requireNotNull(device) { "activelyRecorded(device) requires deviceType" }
                    Metadata.activelyRecorded(device)
                }
            }

            "auto", "automatically_recorded", "auto_recorded" -> {
                if (fixedId != null) {
                    requireNotNull(device) { "autoRecordedWithId requires deviceType" }
                    Metadata.autoRecordedWithId(id = fixedId, device = device)
                } else if (clientRecordId != null) {
                    requireNotNull(device) { "autoRecorded(clientRecordId..) requires deviceType" }
                    Metadata.autoRecorded(clientRecordId, clientRecordVersion, device)
                } else {
                    requireNotNull(device) { "autoRecorded(device) requires deviceType" }
                    Metadata.autoRecorded(device)
                }
            }

            "unknown", "unknown_recording_method" -> {
                if (fixedId != null) {
                    Metadata.unknownRecordingMethodWithId(id = fixedId, device = device)
                } else if (clientRecordId != null) {
                    Metadata.unknownRecordingMethod(clientRecordId, clientRecordVersion, device)
                } else {
                    Metadata.unknownRecordingMethod(device)
                }
            }

            "manual", "manual_entry" -> {
                if (fixedId != null) {
                    Metadata.manualEntryWithId(id = fixedId, device = device)
                } else if (clientRecordId != null) {
                    Metadata.manualEntry(clientRecordId, clientRecordVersion, device)
                } else {
                    Metadata.manualEntry(device)
                }
            }

            else -> error("Unknown metadata.method: $method")
        }
    }

    private fun deviceTypeName(type: Int?): String {
        return when (type) {
            Device.TYPE_UNKNOWN -> "UNKNOWN"
            Device.TYPE_WATCH -> "WATCH"
            Device.TYPE_PHONE -> "PHONE"
            Device.TYPE_SCALE -> "SCALE"
            Device.TYPE_RING -> "RING"
            Device.TYPE_HEAD_MOUNTED -> "HEAD_MOUNTED"
            Device.TYPE_FITNESS_BAND -> "FITNESS_BAND"
            Device.TYPE_CHEST_STRAP -> "CHEST_STRAP"
            Device.TYPE_SMART_DISPLAY -> "SMART_DISPLAY"
            else -> ""
        }
    }

    private fun recordingMethodName(metadata: Metadata): String {
        // SDK 并未直接提供字符串，统一返回用户设置的工厂方法语义
        return when {
            metadata.isManualEntry -> "MANUAL_ENTRY"
            metadata.isAutoRecorded -> "AUTOMATICALLY_RECORDED"
            metadata.isActivelyRecorded -> "ACTIVELY_RECORDED"
            else -> "UNKNOWN"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyToMap(any: Any?): Map<String, Any?> {
        if (any == null) return emptyMap()
        return when (any) {
            is Map<*, *> -> any as Map<String, Any?>
            is NativeObject -> {
                val map = mutableMapOf<String, Any?>()
                any.ids.forEach { id ->
                    map[id.toString()] = any.get(id.toString(), any)
                }
                map
            }
            else -> emptyMap()
        }
    }
}
