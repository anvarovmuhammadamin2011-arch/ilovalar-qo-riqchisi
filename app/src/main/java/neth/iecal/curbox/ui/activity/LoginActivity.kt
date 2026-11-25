package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityLoginBinding
import neth.iecal.curbox.utils.AntiUninstallManager

/**
 * First screen shown when the app is opened from the launcher.
 *
 * On a fresh install no password exists yet, so this registers one (set a
 * password once). On every later open it asks for that password before the
 * main UI is reachable. The hash is stored locally with the same hashing the
 * anti uninstall feature already uses, so there is one place that defines it.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With the admin link, people sign in with accounts from Curbox Admin instead.
        if (BuildConfig.SUPPORTS_ADMIN_LINK) {
            proceed()
            return
        }
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.loginRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val storedHash = prefs.getString(KEY_LOGIN_HASH, "").orEmpty()
        val isRegister = storedHash.isEmpty()

        if (isRegister) {
            binding.tvSubtitle.setText(R.string.login_register_subtitle)
            binding.inputLayoutName.visibility = View.VISIBLE
            binding.inputLayoutConfirm.visibility = View.VISIBLE
            binding.btnSubmit.setText(R.string.login_register_button)
        } else {
            binding.tvSubtitle.setText(R.string.login_login_subtitle)
            binding.inputLayoutName.visibility = View.GONE
            binding.inputLayoutConfirm.visibility = View.GONE
            binding.btnSubmit.setText(R.string.login_login_button)
        }

        binding.btnSubmit.setOnClickListener {
            if (isRegister) handleRegister(prefs) else handleLogin(storedHash)
        }
    }

    private fun handleRegister(prefs: SharedPreferences) {
        val password = binding.inputPassword.text?.toString().orEmpty()
        val confirm = binding.inputConfirm.text?.toString().orEmpty()
        binding.inputLayoutPassword.error = null
        binding.inputLayoutConfirm.error = null
        when {
            password.length < MIN_PASSWORD_LENGTH ->
                binding.inputLayoutPassword.error = getString(R.string.login_password_too_short)
            password != confirm ->
                binding.inputLayoutConfirm.error = getString(R.string.login_password_mismatch)
            else -> {
                val name = binding.inputName.text?.toString()?.trim().orEmpty()
                prefs.edit()
                    .putString(KEY_LOGIN_HASH, AntiUninstallManager.hashPassword(password))
                    .putString(KEY_LOGIN_NAME, name)
                    .apply()
                proceed()
            }
        }
    }

    private fun handleLogin(storedHash: String) {
        val password = binding.inputPassword.text?.toString().orEmpty()
        binding.inputLayoutPassword.error = null
        if (AntiUninstallManager.hashPassword(password) == storedHash) {
            proceed()
        } else {
            binding.inputLayoutPassword.error = getString(R.string.login_wrong_password)
        }
    }

    private fun proceed() {
        val next = if (BuildConfig.SUPPORTS_ADMIN_LINK) RoleSelectActivity::class.java else FragmentActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    companion object {
        private const val PREFS = "AppPreferences"
        private const val KEY_LOGIN_HASH = "appLoginHash"
        private const val KEY_LOGIN_NAME = "appLoginName"
        private const val MIN_PASSWORD_LENGTH = 4
    }
}
