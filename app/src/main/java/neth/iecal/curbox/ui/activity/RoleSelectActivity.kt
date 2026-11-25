package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityRoleSelectBinding
import neth.iecal.curbox.databinding.SheetAdminInfoBinding
import neth.iecal.curbox.ui.components.FixedBrandColors
import neth.iecal.curbox.utils.DataStoreManager

/**
 * First screen of the full flavor. Admin explains that the panel lives on the
 * computer and can open it here behind the admin login. User signs in with an
 * account the admin created.
 */
class RoleSelectActivity : AppCompatActivity(), FixedBrandColors {

    private lateinit var binding: ActivityRoleSelectBinding
    private val dataStoreManager by lazy { DataStoreManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SUPPORTS_ADMIN_LINK) {
            startActivity(Intent(this, FragmentActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.roleRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.cardAdmin.setOnClickListener { showAdminInfo() }
        binding.cardUser.setOnClickListener {
            startActivity(Intent(this, AdminLinkActivity::class.java))
        }

        if (savedInstanceState == null) playIntro()
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.SUPPORTS_ADMIN_LINK) return
        lifecycleScope.launch {
            val config = dataStoreManager.settings.first().adminLinkConfig
            val signedIn = config.isLinked() && config.username.isNotEmpty()
            binding.userStatus.visibility = if (signedIn) View.VISIBLE else View.GONE
            binding.tvUserStatus.text = getString(R.string.role_user_linked, config.username)
        }
    }

    // Brand, title and the two cards rise in one after another; all done in under 600 ms.
    private fun playIntro() {
        val rise = resources.getDimension(R.dimen.curbox_space_l)
        val views = listOf(binding.brandBlock, binding.titleBlock, binding.cardAdmin, binding.cardUser)
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = rise
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 70L)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // The phone never gets admin powers by itself; the page asks for the admin login.
    private fun showAdminInfo() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = SheetAdminInfoBinding.inflate(layoutInflater)
        sheetBinding.btnOpenHere.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, AdminPanelActivity::class.java))
        }
        sheetBinding.btnNotNow.setOnClickListener { sheet.dismiss() }
        sheet.setContentView(sheetBinding.root)
        sheet.show()
    }
}
