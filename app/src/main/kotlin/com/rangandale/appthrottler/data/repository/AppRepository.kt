package com.rangandale.appthrottler.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.rangandale.appthrottler.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AppRepository(
    private val context: Context,
) {
    /** Launchable apps (other than this one), sorted by label. */
    fun getInstalledApps(): Flow<List<AppInfo>> = flow {
        val packageManager = context.packageManager
        val apps = packageManager.getInstalledApplications(0)
            .filter { app ->
                app.packageName != context.packageName &&
                    packageManager.getLaunchIntentForPackage(app.packageName) != null
            }
            .map { app -> app.toDomain(packageManager) }
            .sortedBy { it.appName.lowercase() }
        emit(apps)
    }.flowOn(Dispatchers.Default)

    private fun ApplicationInfo.toDomain(packageManager: PackageManager): AppInfo {
        return AppInfo(
            packageName = packageName,
            appName = packageManager.getApplicationLabel(this).toString(),
            isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }
}
