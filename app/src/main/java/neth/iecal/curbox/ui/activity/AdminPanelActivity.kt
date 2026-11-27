package neth.iecal.curbox.ui.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityAdminPanelBinding
import neth.iecal.curbox.utils.AdminLinkClient

/**
 * Shows the Curbox Admin web page from the admin's computer, so the admin can
 * manage phones from a phone as well. The page asks for the admin password.
 */
class AdminPanelActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "AppPreferences"
        const val PREF_ADMIN_URL = "adminServerUrl"
        /** Open the panel already signed in at this exact server (http allowed for a local computer). */
        const val EXTRA_URL = "adminPanelUrl"
    }

    private lateinit var binding: ActivityAdminPanelBinding
    private var hasLoadedPage = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.SUPPORTS_ADMIN_LINK) {
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityAdminPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.panelRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                hasLoadedPage = true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) askForAddress(showError = true)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        // Opened from the unified login: the exact server the admin just signed in to
        // (http for a local computer is fine), with the session cookie already set.
        val directUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val startUrl = when {
            directUrl.isNotEmpty() -> directUrl
            // An old school computer address (plain http) gives way to the internet server.
            else -> savedUrl().takeIf { it.startsWith("https://") } ?: AdminLinkClient.DEFAULT_SERVER_URL
        }
        binding.webView.loadUrl(startUrl)
    }

    private fun savedUrl(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ADMIN_URL, "").orEmpty()

    private fun askForAddress(showError: Boolean) {
        if (isFinishing) return
        val view = layoutInflater.inflate(R.layout.dialog_admin_address, null)
        val input = view.findViewById<TextInputEditText>(R.id.input_address)
        input.setText(savedUrl().removePrefix("http://"))
        if (showError) {
            view.findViewById<TextInputLayout>(R.id.input_layout_address).error =
                getString(R.string.admin_panel_unreachable)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_panel_address_title)
            .setMessage(R.string.admin_panel_address_message)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.admin_panel_open) { _, _ ->
                val url = AdminLinkClient.normalizeServerUrl(input.text?.toString().orEmpty())
                if (url.isEmpty()) {
                    askForAddress(showError = true)
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_ADMIN_URL, url).apply()
                    hasLoadedPage = false
                    binding.webView.loadUrl(url)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (!hasLoadedPage) finish()
            }
            .show()
    }

    override fun onDestroy() {
        if (this::binding.isInitialized) binding.webView.destroy()
        super.onDestroy()
    }
}
