package com.rangandale.appthrottler.data.repository

import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AppIdentity(val packageName: String?, val label: String)

/** Turns the UID a flow belongs to into something a person recognises. */
@Singleton
class AppIdentityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<Int, AppIdentity>()

    fun identify(uid: Int): AppIdentity = cache.getOrPut(uid) { lookUp(uid) }

    private fun lookUp(uid: Int): AppIdentity {
        if (uid < 0) return AppIdentity(null, "Unknown app")
        val pm = context.packageManager
        val packageName = try {
            pm.getPackagesForUid(uid)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (packageName == null) {
            // Name lookups are made by the system resolver on the app's behalf.
            return AppIdentity(null, if (uid < Process.FIRST_APPLICATION_UID) "System (DNS and services)" else "UID $uid")
        }
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
        return AppIdentity(packageName, label)
    }
}
