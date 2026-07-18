/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.topPadding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            windowInsets
        }
        setContentView(binding.root)
        // always show toolbar back arrow icon
        // https://android.googlesource.com/platform/frameworks/support/+/32e643112d0217619237a0d7101b50919c6caf51/navigation/navigation-ui/src/main/java/androidx/navigation/ui/AbstractAppBarOnDestinationChangedListener.kt#80
        binding.toolbar.navigationIcon = DrawerArrowDrawable(this).apply { progress = 1f }
        // show menu icon and other action icons on toolbar
        // don't use `setSupportActionBar(binding.toolbar)` here,
        // because navController would change toolbar title, we need to control it by ourselves
        setupToolbarMenu(binding.toolbar.menu)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = SettingsRoute.createGraph(navController)
        binding.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
        viewModel.toolbarTitle.observe(this) {
            binding.toolbar.title = it
        }
        viewModel.toolbarShadow.observe(this) {
            binding.toolbar.elevation = dp(if (it) 4f else 0f)
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            if (dest.hasRoute<SettingsRoute.Theme>()) {
                viewModel.disableToolbarShadow()
            } else {
                viewModel.enableToolbarShadow()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.oauthEvents.collect { event ->
                    when (event) {
                        MainViewModel.OAuthEvent.Success -> Toast.makeText(
                            this@MainActivity,
                            R.string.subscription_auth_success,
                            Toast.LENGTH_SHORT,
                        ).show()
                        is MainViewModel.OAuthEvent.Failure -> Toast.makeText(
                            this@MainActivity,
                            getString(
                                R.string.subscription_auth_failed,
                                event.message.ifEmpty { getString(R.string.voice_input_error) },
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        processIntent(intent)
        checkNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "fcitx5" && data.host == "oauth" && data.path?.startsWith("/callback") == true) {
                    handleOAuthCallback(data)
                    intent.action = null
                    intent.data = null
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.pinyin_dict)
                        .setMessage(R.string.whether_import_dict)
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            navController.popBackStack(SettingsRoute.Index, false)
                            navController.navigateWithAnim(SettingsRoute.PinyinDict(data))
                        }
                        .show()
                }
            }
            Intent.ACTION_RUN -> {
                val route = intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    private fun handleOAuthCallback(data: Uri) {
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        val error = data.getQueryParameter("error")

        if (viewModel.isOAuthExchangeInProgress(state)) return

        val prefs = AppPrefs.getInstance().voiceInput
        val savedState = prefs.oauthState.getValue()
        if (state.isNullOrEmpty() || state != savedState) {
            Toast.makeText(this, R.string.subscription_auth_invalid_state, Toast.LENGTH_LONG).show()
            return
        }

        if (error != null) {
            val desc = data.getQueryParameter("error_description") ?: error
            clearOAuthState()
            Toast.makeText(
                this,
                getString(R.string.subscription_auth_failed, desc),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (code == null) {
            clearOAuthState()
            Toast.makeText(this, R.string.subscription_auth_invalid_callback, Toast.LENGTH_LONG).show()
            return
        }

        // Read PKCE code_verifier saved before login
        val codeVerifier = prefs.oauthCodeVerifier.getValue()
        if (codeVerifier.isEmpty()) {
            clearOAuthState()
            Toast.makeText(this, R.string.subscription_auth_invalid_callback, Toast.LENGTH_LONG).show()
            return
        }

        // Exchange code for tokens via gateway
        val gatewayUrl = prefs.subscriptionGatewayUrl.getValue().trimEnd('/')
        val gatewayUri = Uri.parse(gatewayUrl)
        if (gatewayUri.scheme != "https" || gatewayUri.host.isNullOrEmpty()) {
            clearOAuthState()
            Toast.makeText(this, R.string.subscription_gateway_invalid, Toast.LENGTH_LONG).show()
            return
        }

        if (viewModel.exchangeOAuthCode(state, gatewayUrl, code, codeVerifier)) {
            clearOAuthState()
        }
    }

    private fun clearOAuthState() {
        val prefs = AppPrefs.getInstance().voiceInput
        prefs.oauthCodeVerifier.setValue("")
        prefs.oauthState.setValue("")
    }

    private fun setupToolbarMenu(menu: Menu) {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_save_24, iconTint, true) {
            viewModel.toolbarSaveButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarSaveButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        val aboutMenuItems = listOf(
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            },
            menu.item(R.string.developer) {
                navController.navigateWithAnim(SettingsRoute.Developer)
            },
            menu.item(R.string.about) {
                navController.navigateWithAnim(SettingsRoute.About)
            }
        )
        viewModel.aboutButton.observe(this@MainActivity) { enabled ->
            aboutMenuItems.forEach { menu -> menu.isVisible = enabled }
        }
        menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint, true) {
            viewModel.toolbarEditButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarEditButtonVisible.observe(this@MainActivity) { isVisible = it }
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint, true) {
            viewModel.toolbarDeleteButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarDeleteButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        // all menus should be invisible and enabled on demand
        menu.forEach { it.isVisible = false }
    }

    private var needNotifications by AppPrefs.getInstance().internal.needNotifications

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                needNotifications = true
                return
            }
            // do not ask again if user denied the request
            if (!needNotifications) return
            // always show a dialog to explain why we need notification permission,
            // regardless of `shouldShowRequestPermissionRationale(...)`
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.i_do_not_need_it) { _, _ ->
                    // do not ask again if user denied the request
                    needNotifications = false
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 0) return
        // do not ask again if user denied the request
        needNotifications = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }

}
