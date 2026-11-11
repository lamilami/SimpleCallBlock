package com.datecs.simplecallblocker

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Optional Device Admin / Device Owner component. The app works fully without it.
 *
 * Device Owner does NOT grant ROLE_CALL_SCREENING - the role must still be granted through
 * RoleManager (dialog) or by `adb shell cmd role add-role-holder`.
 * Only documented DevicePolicyManager APIs are used here.
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)

        private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

        fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

        fun isUninstallBlocked(context: Context) =
            isDeviceOwner(context) && dpm(context).isUninstallBlocked(component(context), context.packageName)

        /** Device Owner only: prevents the user from uninstalling the blocker. */
        fun setUninstallBlocked(context: Context, blocked: Boolean) {
            if (isDeviceOwner(context)) dpm(context).setUninstallBlocked(component(context), context.packageName, blocked)
        }

        /** Device Owner only: grants READ_CONTACTS (for "Block Unknown") without a user prompt. */
        fun grantContactsPermission(context: Context): Boolean =
            isDeviceOwner(context) && dpm(context).setPermissionGrantState(
                component(context), context.packageName, Manifest.permission.READ_CONTACTS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )

        /** Gives up Device Owner status (also lifts the uninstall block). */
        @Suppress("DEPRECATION") // still the documented way for a DO to remove itself
        fun clearDeviceOwner(context: Context) {
            if (!isDeviceOwner(context)) return
            setUninstallBlocked(context, false)
            dpm(context).clearDeviceOwnerApp(context.packageName)
        }
    }
}
