package neth.iecal.curbox.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import neth.iecal.curbox.databinding.ActivityAdminPolicyComplianceBinding
import neth.iecal.curbox.ui.components.FixedBrandColors

/**
 * The last screen of QR/zero-touch device-owner provisioning (Android 12+ requires a DPC
 * to handle android.app.action.ADMIN_POLICY_COMPLIANCE before control returns to the user).
 * v1 scope: explain that MBSI manages this phone, then finish. No forced admin sign-in here —
 * the user signs in normally afterward through AdminLinkActivity once the home screen loads.
 */
class AdminPolicyComplianceActivity : AppCompatActivity(), FixedBrandColors {

    private lateinit var binding: ActivityAdminPolicyComplianceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAdminPolicyComplianceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.complianceRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnContinue.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }
}
