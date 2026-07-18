/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext

class MainViewModel : ViewModel() {

    sealed interface OAuthEvent {
        data object Success : OAuthEvent
        data class Failure(val message: String) : OAuthEvent
    }

    val toolbarTitle = MutableLiveData(appContext.getString(R.string.app_name))

    val toolbarShadow = MutableLiveData(true)

    val toolbarSaveButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    val toolbarEditButtonVisible = MutableLiveData(false)

    val toolbarEditButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    val toolbarDeleteButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    val aboutButton = MutableLiveData(false)

    val fcitx: FcitxConnection = FcitxDaemon.connect(javaClass.name)

    private val oauthClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .build()
    private val oauthEventChannel = Channel<OAuthEvent>(Channel.BUFFERED)
    val oauthEvents = oauthEventChannel.receiveAsFlow()
    private var activeOAuthState: String? = null

    fun isOAuthExchangeInProgress(state: String?) =
        state != null && state == activeOAuthState

    fun exchangeOAuthCode(
        state: String,
        gatewayUrl: String,
        code: String,
        codeVerifier: String,
    ): Boolean {
        if (activeOAuthState != null) return false
        activeOAuthState = state
        viewModelScope.launch {
            try {
                val (accessToken, refreshToken) = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$gatewayUrl/oauth/token")
                        .post(
                            FormBody.Builder()
                                .add("code", code)
                                .add("grant_type", "authorization_code")
                                .add("redirect_uri", "fcitx5://oauth/callback")
                                .add("code_verifier", codeVerifier)
                                .build(),
                        )
                        .build()

                    oauthClient.newCall(request).executeAsync().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException(response.code.toString())
                        }
                        val json = org.json.JSONObject(response.body.string())
                        val accessToken = json.getString("access_token")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?: throw IllegalStateException("Empty access token")
                        accessToken to json.optString("refresh_token", "").trim()
                    }
                }
                val prefs = AppPrefs.getInstance().voiceInput
                prefs.subscriptionAccessToken.setValue(accessToken)
                prefs.subscriptionRefreshToken.setValue(refreshToken)
                oauthEventChannel.send(OAuthEvent.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                oauthEventChannel.send(OAuthEvent.Failure(e.message.orEmpty()))
            } finally {
                activeOAuthState = null
            }
        }
        return true
    }

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    fun enableToolbarShadow() {
        toolbarShadow.value = true
    }

    fun disableToolbarShadow() {
        toolbarShadow.value = false
    }

    fun enableToolbarSaveButton(onClick: () -> Unit) {
        toolbarSaveButtonOnClickListener.value = onClick
    }

    fun disableToolbarSaveButton() {
        toolbarSaveButtonOnClickListener.value = null
    }

    fun enableToolbarEditButton(visible: Boolean = true, onClick: () -> Unit) {
        toolbarEditButtonOnClickListener.value = onClick
        toolbarEditButtonVisible.value = visible
    }

    fun disableToolbarEditButton() {
        toolbarEditButtonOnClickListener.value = null
        hideToolbarEditButton()
    }

    fun hideToolbarEditButton() {
        toolbarEditButtonVisible.value = false
    }

    fun showToolbarEditButton() {
        toolbarEditButtonVisible.value = true
    }

    fun enableToolbarDeleteButton(onClick: () -> Unit) {
        toolbarDeleteButtonOnClickListener.value = onClick
    }

    fun disableToolbarDeleteButton() {
        toolbarDeleteButtonOnClickListener.value = null
    }

    fun enableAboutButton() {
        aboutButton.value = true
    }

    fun disableAboutButton() {
        aboutButton.value = false
    }

    override fun onCleared() {
        FcitxDaemon.disconnect(javaClass.name)
    }
}
