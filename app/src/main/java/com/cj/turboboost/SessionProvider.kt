package com.cj.turboboost

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * Where [SessionWatcher] ends a session. Its `content call` is a synchronous binder call, which
 * HiOS's freezer lets through and thaws this app for (it holds broadcasts back), so the session
 * ends inside the call even while the app is frozen. HiOS still refreezes the app every 5 s and
 * thaws it about 1 s later while the call runs, which only slows the end down (measured on a
 * Pova 7: 12 s of work took 12-13 s).
 *
 * call() enforces no permission by itself: [SessionEnd.authorize] admits only the shell uid or
 * root holding the session's token. Exported for that reason only.
 */
class SessionProvider : ContentProvider() {

    companion object {
        private const val TAG = "TurboBooster"
        const val METHOD_END = "end"
        const val EXTRA_REASON = "reason"
        const val RESULT_OK = "ok"

        fun authority(packageName: String) = "$packageName.session"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_END) return null
        val context = context ?: return null
        val result = Bundle()
        if (!SessionEnd.authorize(Binder.getCallingUid(), arg, Stores.session(context))) {
            Log.w(TAG, "Session end refused for uid ${Binder.getCallingUid()}")
            return result.apply { putBoolean(RESULT_OK, false) }
        }
        val reason = EndReason.fromCode(extras?.getString(EXTRA_REASON)) ?: EndReason.EXITED
        val outcome = SessionEnd.finish(SessionContext.of(context), reason)
        Log.i(TAG, "Session ended by the watcher (${reason.code}): ok=${outcome.ok}")
        if (outcome.ok) GameMonitorService.stop(context)
        return result.apply { putBoolean(RESULT_OK, outcome.ok) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
