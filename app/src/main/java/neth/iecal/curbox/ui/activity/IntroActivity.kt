package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityIntroBinding
import neth.iecal.curbox.databinding.ItemIntroPageBinding
import neth.iecal.curbox.ui.components.FixedBrandColors

/**
 * Four short pages before the first permission step. Then the Protection screen
 * explains each Android permission before it opens Android's own setting.
 */
class IntroActivity : AppCompatActivity(), FixedBrandColors {

    companion object {
        const val PREF_INTRO_SEEN = "userIntroSeen"
    }

    private data class Page(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

    private val pages = listOf(
        Page(R.drawable.ic_admin_shield, R.string.intro_welcome_title, R.string.intro_welcome_body),
        Page(R.drawable.ic_curbox_bar_chart, R.string.intro_time_title, R.string.intro_time_body),
        Page(R.drawable.ic_curbox_block, R.string.intro_rules_title, R.string.intro_rules_body),
        Page(R.drawable.ic_curbox_check, R.string.intro_permission_title, R.string.intro_permission_body)
    )

    private lateinit var binding: ActivityIntroBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.introRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.pager.adapter = PageAdapter()
        TabLayoutMediator(binding.dots, binding.pager) { tab, position ->
            tab.contentDescription = getString(R.string.intro_page, position + 1, pages.size)
        }.attach()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val last = position == pages.lastIndex
                binding.btnNext.setText(if (last) R.string.intro_start else R.string.intro_next)
                binding.btnSkip.visibility = if (last) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        })
        binding.btnNext.setOnClickListener {
            val position = binding.pager.currentItem
            if (position < pages.lastIndex) binding.pager.currentItem = position + 1 else finishIntro()
        }
        binding.btnSkip.setOnClickListener { finishIntro() }
    }

    private fun finishIntro() {
        getSharedPreferences(AdminPanelActivity.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_INTRO_SEEN, true).apply()
        startActivity(ProtectionActivity.setupIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(val view: ItemIntroPageBinding) : RecyclerView.ViewHolder(view.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemIntroPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            holder.view.ivIcon.setImageResource(page.icon)
            holder.view.tvTitle.setText(page.title)
            holder.view.tvBody.setText(page.body)
        }
    }
}
