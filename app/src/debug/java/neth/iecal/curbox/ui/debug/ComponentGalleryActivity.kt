package neth.iecal.curbox.ui.debug

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityComponentGalleryBinding
import neth.iecal.curbox.ui.components.AppRowView
import neth.iecal.curbox.ui.components.ConnectionChip
import neth.iecal.curbox.ui.components.FixedBrandColors
import neth.iecal.curbox.ui.components.StatusPill

/** Debug only: every design system component in every state, in light or dark. */
class ComponentGalleryActivity : AppCompatActivity(), FixedBrandColors {

    private lateinit var binding: ActivityComponentGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityComponentGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.galleryRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnToggleDark.setOnClickListener {
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            delegate.localNightMode =
                if (isDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        }

        for (state in ConnectionChip.State.values()) {
            binding.chips.addView(ConnectionChip(this).apply { setState(state) })
        }

        binding.section.setOnActionClickListener {
            Toast.makeText(this, "See all", Toast.LENGTH_SHORT).show()
        }

        val appIcon = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        fun row(block: AppRowView.() -> Unit) = binding.rows.addView(AppRowView(this).apply(block))
        row {
            setAppIcon(appIcon); title = "TikTok"; subtitle = "com.zhiliaoapp.musically"
            showPill(StatusPill.State.BLOCKED)
        }
        row {
            setAppIcon(appIcon); title = "YouTube"; subtitle = "Set by your admin"
            showPill(StatusPill.State.TIME_LIMITED, "30m a day")
        }
        row {
            setAppIcon(appIcon); title = "Telegram"
            showPill(StatusPill.State.ALLOWED)
        }
        row {
            setSymbol(R.drawable.ic_curbox_event); title = "Study mode"; subtitle = "Until 15:00"
            showPill(StatusPill.State.SCHEDULED)
        }
        row {
            setAppIcon(appIcon); title = "Chrome"; setBar(0.45f)
            showValue("18m")
        }
        row {
            setSymbol(R.drawable.ic_curbox_globe); title = "Blocked websites"; subtitle = "youtube.com, tiktok.com"
            showChevron()
        }

        binding.empty.setAction("Try again") {
            binding.ringLimit.progress = (0..100).random() / 100f
        }
    }
}
