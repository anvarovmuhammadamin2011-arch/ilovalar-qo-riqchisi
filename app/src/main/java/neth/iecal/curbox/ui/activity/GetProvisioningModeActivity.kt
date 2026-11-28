package neth.iecal.curbox.ui.activity

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Android 12+ requires a DPC to answer this during QR/NFC device-owner provisioning,
 * or setup fails with "Couldn't set up device". Curbox only ever supports one mode
 * (fully managed / device owner), so there is nothing to ask the user — this answers
 * immediately and finishes with no UI.
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
