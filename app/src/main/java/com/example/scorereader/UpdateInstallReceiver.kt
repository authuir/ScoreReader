package com.example.scorereader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives status callbacks from [android.content.pm.PackageInstaller] sessions
 * committed by [UpdateManager].
 *
 * When the platform needs the user to confirm the install (the normal case for
 * non-system apps that hold REQUEST_INSTALL_PACKAGES), it sends
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] with an Intent extra that
 * launches the system's own confirm-install UI. We forward that intent.
 *
 * On success/failure we just surface a Toast.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Install status=$status msg=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirm == null) {
                    Toast.makeText(context, "Install confirm intent missing", Toast.LENGTH_LONG).show()
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (t: Throwable) {
                    Log.e(TAG, "Confirm intent launch failed", t)
                    Toast.makeText(
                        context,
                        "Couldn't open installer: ${t.message ?: t.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Update installed", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Toast.makeText(
                    context,
                    "Install failed (status=$status): ${message ?: ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                // Unknown / not interesting (e.g. -999 if extra missing)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallRecv"
        const val ACTION_INSTALL_STATUS =
            "com.example.scorereader.action.UPDATE_INSTALL_STATUS"
    }
}
