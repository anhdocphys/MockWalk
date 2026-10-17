package com.mockwalk.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dual-Layer Persistence Manager for saving and restoring the last configured walking route.
 *
 * Layer 1: Internal Storage (`context.filesDir/last_route_state.json`) + `SharedPreferences`.
 * Layer 2: External App Documents + Public `Documents/StravaMockWalker/strava_mock_last_route.json`
 *          via `MediaStore.Files` so route data survives ADB uninstall/reinstall cycles.
 */
class RouteStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "RouteStorageManager"

        private const val PREFS_NAME = "mock_walk_route_prefs"
        private const val KEY_SAVED_ROUTE_JSON = "last_route_state_json"

        private const val INTERNAL_FILE_NAME = "last_route_state.json"
        private const val PUBLIC_BACKUP_FOLDER = "StravaMockWalker"
        private const val PUBLIC_BACKUP_FILE_NAME = "strava_mock_last_route.json"
    }

    /**
     * Complete snapshot of user's custom multi-waypoint route and speed/distance configuration.
     */
    data class SavedRouteState(
        val waypoints: List<LatLng>,
        val routePoints: List<LatLng>,
        val singleLapDistanceMeters: Double,
        val closeLoop: Boolean,
        val minSpeedKmh: Float,
        val maxSpeedKmh: Float,
        val targetDistanceKm: Float,
        val savedAtMillis: Long = System.currentTimeMillis()
    )

    /**
     * Persists [state] to both Layer 1 (Internal File + SharedPreferences)
     * and Layer 2 (External Documents + MediaStore Public Documents).
     */
    fun saveRouteState(state: SavedRouteState) {
        if (state.waypoints.isEmpty()) return

        try {
            val jsonString = serializeToJson(state)

            // 1. Save to Internal File (context.filesDir/last_route_state.json)
            try {
                val internalFile = File(context.filesDir, INTERNAL_FILE_NAME)
                internalFile.writeText(jsonString, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing internal file: ${e.message}")
            }

            // 2. Save to SharedPreferences
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SAVED_ROUTE_JSON, jsonString)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing SharedPreferences: ${e.message}")
            }

            // 3. Save to App-Specific External Documents Dir
            try {
                val extDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (extDocsDir != null) {
                    if (!extDocsDir.exists()) extDocsDir.mkdirs()
                    val extFile = File(extDocsDir, PUBLIC_BACKUP_FILE_NAME)
                    extFile.writeText(jsonString, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing external app documents file: ${e.message}")
            }

            // 4. Save to Public Documents/StravaMockWalker via MediaStore (Android 10+ / API 29-34)
            saveToPublicDocumentsMediaStore(jsonString)

            Log.d(
                TAG,
                "Saved route state: ${state.waypoints.size} waypoints, ${state.routePoints.size} routePoints, 1-lap=${state.singleLapDistanceMeters}m"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving route state", e)
        }
    }

    /**
     * Loads the last saved route state.
     * Priority:
     * 1. Internal File (`context.filesDir/last_route_state.json`)
     * 2. SharedPreferences
     * 3. External App Documents (`getExternalFilesDir(DIRECTORY_DOCUMENTS)`)
     * 4. Public `Documents/StravaMockWalker/strava_mock_last_route.json` via MediaStore / File
     */
    fun loadLastRoute(): SavedRouteState? {
        // 1. Try Internal File
        try {
            val internalFile = File(context.filesDir, INTERNAL_FILE_NAME)
            if (internalFile.exists() && internalFile.canRead()) {
                val text = internalFile.readText(Charsets.UTF_8)
                deserializeFromJson(text)?.let {
                    Log.d(TAG, "Loaded route state from Internal File (${it.waypoints.size} waypoints)")
                    return it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading internal file: ${e.message}")
        }

        // 2. Try SharedPreferences
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val text = prefs.getString(KEY_SAVED_ROUTE_JSON, null)
            if (!text.isNullOrBlank()) {
                deserializeFromJson(text)?.let {
                    Log.d(TAG, "Loaded route state from SharedPreferences (${it.waypoints.size} waypoints)")
                    return it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading SharedPreferences: ${e.message}")
        }

        // 3. Try App-Specific External Documents
        try {
            val extDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (extDocsDir != null) {
                val extFile = File(extDocsDir, PUBLIC_BACKUP_FILE_NAME)
                if (extFile.exists() && extFile.canRead()) {
                    val text = extFile.readText(Charsets.UTF_8)
                    deserializeFromJson(text)?.let {
                        Log.d(TAG, "Restored route state from External App Documents")
                        restoreToLayer1(text)
                        return it
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading external app documents: ${e.message}")
        }

        // 4. Try Public Documents/StravaMockWalker via MediaStore or direct file fallback
        val publicJson = loadFromPublicDocumentsMediaStore()
        if (!publicJson.isNullOrBlank()) {
            deserializeFromJson(publicJson)?.let {
                Log.d(TAG, "Restored route state from Public Documents/StravaMockWalker backup")
                restoreToLayer1(publicJson)
                return it
            }
        }

        return null
    }

    /**
     * Clears saved route state across all storage layers when user explicitly presses Clear.
     */
    fun clearSavedRoute() {
        try {
            val internalFile = File(context.filesDir, INTERNAL_FILE_NAME)
            if (internalFile.exists()) internalFile.delete()
        } catch (ignored: Exception) {}

        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SAVED_ROUTE_JSON)
                .apply()
        } catch (ignored: Exception) {}

        try {
            val extDocsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (extDocsDir != null) {
                val extFile = File(extDocsDir, PUBLIC_BACKUP_FILE_NAME)
                if (extFile.exists()) extFile.delete()
            }
        } catch (ignored: Exception) {}

        deleteFromPublicDocumentsMediaStore()
    }

    private fun restoreToLayer1(jsonString: String) {
        try {
            File(context.filesDir, INTERNAL_FILE_NAME).writeText(jsonString, Charsets.UTF_8)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_ROUTE_JSON, jsonString)
                .apply()
        } catch (ignored: Exception) {}
    }

    private fun saveToPublicDocumentsMediaStore(jsonString: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val resolver = context.contentResolver
            val collectionUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_BACKUP_FOLDER/"

            // Check if backup file already exists in MediaStore
            val existingUri = findPublicBackupUri(collectionUri)
            if (existingUri != null) {
                try {
                    resolver.openOutputStream(existingUri, "wt")?.use { out ->
                        out.write(jsonString.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    return
                } catch (e: Exception) {
                    // If overwrite failed (e.g., owned by previous UID before reinstall), try deleting first
                    try {
                        resolver.delete(existingUri, null, null)
                    } catch (ignored: Exception) {}
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_BACKUP_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collectionUri, values)
            if (itemUri != null) {
                resolver.openOutputStream(itemUri, "wt")?.use { out ->
                    out.write(jsonString.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val completeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, completeValues, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore backup write skipped: ${e.message}")
        }
    }

    private fun loadFromPublicDocumentsMediaStore(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val collectionUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val existingUri = findPublicBackupUri(collectionUri)
                if (existingUri != null) {
                    resolver.openInputStream(existingUri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val content = reader.readText()
                        if (content.isNotBlank()) return content
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore backup read failed: ${e.message}")
            }
        }

        // Fallback: Try direct public Documents path if readable
        try {
            val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val backupFile = File(File(publicDocs, PUBLIC_BACKUP_FOLDER), PUBLIC_BACKUP_FILE_NAME)
            if (backupFile.exists() && backupFile.canRead()) {
                return backupFile.readText(Charsets.UTF_8)
            }
        } catch (ignored: Exception) {}

        return null
    }

    private fun deleteFromPublicDocumentsMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collectionUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existingUri = findPublicBackupUri(collectionUri)
            if (existingUri != null) {
                resolver.delete(existingUri, null, null)
            }
        } catch (ignored: Exception) {}
    }

    private fun findPublicBackupUri(collectionUri: Uri): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("strava_mock_last_route%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            collectionUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idCol)
                return ContentUris.withAppendedId(collectionUri, id)
            }
        }
        return null
    }

    private fun serializeToJson(state: SavedRouteState): String {
        val root = JSONObject()
        root.put("singleLapDistanceMeters", state.singleLapDistanceMeters)
        root.put("closeLoop", state.closeLoop)
        root.put("minSpeedKmh", state.minSpeedKmh.toDouble())
        root.put("maxSpeedKmh", state.maxSpeedKmh.toDouble())
        root.put("targetDistanceKm", state.targetDistanceKm.toDouble())
        root.put("savedAtMillis", state.savedAtMillis)

        val waypointsArray = JSONArray()
        for (wp in state.waypoints) {
            val obj = JSONObject()
            obj.put("lat", wp.latitude)
            obj.put("lng", wp.longitude)
            waypointsArray.put(obj)
        }
        root.put("waypoints", waypointsArray)

        val routePointsArray = JSONArray()
        for (rp in state.routePoints) {
            val obj = JSONObject()
            obj.put("lat", rp.latitude)
            obj.put("lng", rp.longitude)
            routePointsArray.put(obj)
        }
        root.put("routePoints", routePointsArray)

        return root.toString()
    }

    private fun deserializeFromJson(jsonString: String): SavedRouteState? {
        return try {
            val root = JSONObject(jsonString)

            val waypointsArray = root.optJSONArray("waypoints") ?: return null
            val waypoints = ArrayList<LatLng>(waypointsArray.length())
            for (i in 0 until waypointsArray.length()) {
                val obj = waypointsArray.getJSONObject(i)
                waypoints.add(LatLng(obj.getDouble("lat"), obj.getDouble("lng")))
            }
            if (waypoints.isEmpty()) return null

            val routePointsArray = root.optJSONArray("routePoints")
            val routePoints = ArrayList<LatLng>(routePointsArray?.length() ?: 0)
            if (routePointsArray != null) {
                for (i in 0 until routePointsArray.length()) {
                    val obj = routePointsArray.getJSONObject(i)
                    routePoints.add(LatLng(obj.getDouble("lat"), obj.getDouble("lng")))
                }
            }

            SavedRouteState(
                waypoints = waypoints,
                routePoints = routePoints,
                singleLapDistanceMeters = root.optDouble("singleLapDistanceMeters", 0.0),
                closeLoop = root.optBoolean("closeLoop", true),
                minSpeedKmh = root.optDouble("minSpeedKmh", 4.0).toFloat(),
                maxSpeedKmh = root.optDouble("maxSpeedKmh", 6.0).toFloat(),
                targetDistanceKm = root.optDouble("targetDistanceKm", 3.0).toFloat(),
                savedAtMillis = root.optLong("savedAtMillis", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing SavedRouteState JSON: ${e.message}")
            null
        }
    }
}
