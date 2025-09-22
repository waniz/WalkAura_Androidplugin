package com.waniz.library

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import com.waniz.library.data.DatasetEntry


class GodotAndroidPlugin(godot: Godot): GodotPlugin(godot) {
    companion object {
        private const val RC_CODE = 989
        const val permissionRequired = "android.permission.ACTIVITY_RECOGNITION"

        // List of constants for results of permission request
        const val PERMISSION_RESULT_GRANTED = 0
        const val PERMISSION_RESULT_DENIED = 1
        const val PERMISSION_RESULT_DENIED_SHOW_RATIONALE = 2

        const val SIGNAL_PERMISSION_REQUEST_COMPLETED = "permission_request_completed"
        const val SIGNAL_TOTAL_STEPS_RETRIEVED = "total_steps_retrieved"
    }

    override fun getPluginName() = "GodotAndroidPlugin"

    private val currentActivity: Activity = activity ?: throw IllegalStateException()

    val localRecordingClient = FitnessLocal.getLocalRecordingClient(currentActivity)

    override fun getPluginSignals(): MutableSet<SignalInfo> {
        return mutableSetOf(
            SignalInfo(SIGNAL_PERMISSION_REQUEST_COMPLETED, Any::class.java, String::class.java, Any::class.java),
            SignalInfo(SIGNAL_TOTAL_STEPS_RETRIEVED, Any::class.java)
        )
    }

    @UsedByGodot
    fun checkRequiredPermissions() : Int {
        return when (currentActivity.checkSelfPermission(permissionRequired)) {
            PackageManager.PERMISSION_GRANTED -> {
                Log.v(pluginName, "Already Granted")
                PERMISSION_RESULT_GRANTED
            }
            else -> {
                Log.v(pluginName, "Already Denied")
                val showRationale = currentActivity.shouldShowRequestPermissionRationale(permissionRequired)
                if (showRationale)
                    PERMISSION_RESULT_DENIED_SHOW_RATIONALE
                else
                    PERMISSION_RESULT_DENIED
            }
        }
    }

    @UsedByGodot
    fun requestRequiredPermissions() {
        currentActivity.requestPermissions(arrayOf(permissionRequired), RC_CODE)
    }

    override fun onMainRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        super.onMainRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_CODE && permissions != null && permissions.isNotEmpty()){

            val requestedPermission = permissions.first()
            val permissionCode = 0

            if (grantResults?.first() == PackageManager.PERMISSION_GRANTED) {
                Log.v(pluginName, "Granted")
                emitSignal(
                    SIGNAL_PERMISSION_REQUEST_COMPLETED,
                    permissionCode, requestedPermission, PERMISSION_RESULT_GRANTED
                )
            } else {
                Log.v(pluginName, "Denied")
                emitSignal(
                    SIGNAL_PERMISSION_REQUEST_COMPLETED,
                    permissionCode, requestedPermission, PERMISSION_RESULT_DENIED
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    @UsedByGodot
    fun subscribeToFitnessData() {
        // Subscribe to steps data
        localRecordingClient.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .addOnSuccessListener {
                Log.i(pluginName, "Successfully subscribed!")
            }
            .addOnFailureListener { e ->
                Log.w(pluginName, "There was a problem subscribing.", e)
            }
    }

    @UsedByGodot
    fun getSteps(seconds: Long) {
        val endTime = LocalDateTime.now().atZone(ZoneId.systemDefault())
        val startTime = endTime.minusSeconds(seconds)

        val readRequest = LocalDataReadRequest.Builder()
            .bucketByTime(1, TimeUnit.DAYS)
            .read(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .setTimeRange(startTime.toEpochSecond(), endTime.toEpochSecond(), TimeUnit.SECONDS)
            .build()

        localRecordingClient.readData(readRequest)
            .addOnSuccessListener { response ->
                val entries: List<DatasetEntry> =
                    response.buckets
                        .flatMap { it.dataSets }
                        .flatMap { it.dataPoints }
                        .map { dp ->
                            DatasetEntry(
                                start = dp.getStartTime(TimeUnit.SECONDS),
                                end = dp.getEndTime(TimeUnit.SECONDS),
                                steps = dp.getValue(LocalField.FIELD_STEPS).asInt()
                            )
                        }

                val jsonArr = JSONArray()
                for (e in entries) {
                    val o = JSONObject()
                    o.put("start", e.start)
                    o.put("end", e.end)
                    o.put("steps", e.steps)
                    jsonArr.put(o)
                }

                emitSignal(
                    SIGNAL_TOTAL_STEPS_RETRIEVED,
                    jsonArr.toString()
                )
            }
            .addOnFailureListener { e ->
                emitSignal(
                    SIGNAL_TOTAL_STEPS_RETRIEVED,
                    -1
                )
            }
    }

}
