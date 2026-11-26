package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityUserMainBinding
import neth.iecal.curbox.ui.components.FixedBrandColors
import neth.iecal.curbox.ui.fragments.user.UserActivityFragment
import neth.iecal.curbox.ui.fragments.user.UserHomeFragment
import neth.iecal.curbox.ui.fragments.user.UserProfileFragment
import neth.iecal.curbox.ui.fragments.user.UserRestrictionsFragment
import neth.iecal.curbox.ui.fragments.user.UserUsageFragment

/**
 * Home of a phone signed in to Curbox for PC: Home, Usage, Restrictions,
 * Activity and Profile. Only the full flavor declares it.
 */
class UserMainActivity : AppCompatActivity(), FixedBrandColors {

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_RESTRICTIONS = "restrictions"
    }

    // Each tab keeps its fragment alive across switches (hide and show, not replace).
    private val tabTags = mapOf(
        R.id.nav_user_home to "user_home",
        R.id.nav_user_usage to "user_usage",
        R.id.nav_user_restrictions to "user_restrictions",
        R.id.nav_user_activity to "user_activity",
        R.id.nav_user_profile to "user_profile",
    )

    private lateinit var binding: ActivityUserMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SUPPORTS_ADMIN_LINK) {
            startActivity(Intent(this, FragmentActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityUserMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The bottom navigation pads itself for the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.userFragmentHolder) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }

        if (savedInstanceState == null) {
            // The admin block screen opens Restrictions with "See details".
            val first = if (intent.getStringExtra(EXTRA_OPEN_TAB) == TAB_RESTRICTIONS) R.id.nav_user_restrictions else R.id.nav_user_home
            binding.userBottomNav.selectedItemId = first
            showTab(first, commitNow = true)
        }
        binding.userBottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == binding.userBottomNav.selectedItemId) return@setOnItemSelectedListener false
            showTab(item.itemId)
            true
        }
    }

    /** Lets a screen jump to another tab, for example "See all" on Home. */
    fun openTab(itemId: Int) {
        binding.userBottomNav.selectedItemId = itemId
    }

    private fun createTab(itemId: Int): Fragment = when (itemId) {
        R.id.nav_user_usage -> UserUsageFragment()
        R.id.nav_user_restrictions -> UserRestrictionsFragment()
        R.id.nav_user_activity -> UserActivityFragment()
        R.id.nav_user_profile -> UserProfileFragment()
        else -> UserHomeFragment()
    }

    private fun showTab(itemId: Int, commitNow: Boolean = false) {
        val tag = tabTags[itemId] ?: return
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction().setReorderingAllowed(true)
        for ((id, otherTag) in tabTags) {
            if (id != itemId) fm.findFragmentByTag(otherTag)?.let { transaction.hide(it) }
        }
        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            transaction.add(R.id.user_fragment_holder, createTab(itemId), tag)
        } else {
            transaction.show(target)
        }
        if (commitNow) transaction.commitNow() else transaction.commit()
    }
}
