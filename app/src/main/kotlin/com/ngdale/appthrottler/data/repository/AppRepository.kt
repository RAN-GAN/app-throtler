package com.ngdale.appthrottler.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.ngdale.appthrottler.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AppRepository(
    private val context: Context,
) {
    fun getInstalledApps(): Flow<List<AppInfo>> = flow {
        val packageManager = context.packageManager
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app -> packageManager.getLaunchIntentForPackage(app.packageName) != null }
            .sortedBy { app -> packageManager.getApplicationLabel(app).toString().lowercase() }
            .map { app -> app.toDomain(packageManager) }
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