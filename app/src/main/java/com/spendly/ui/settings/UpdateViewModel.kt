package com.spendly.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.BuildConfig
import com.spendly.data.Prefs
import com.spendly.update.ApkInstaller
import com.spendly.update.InstallEvent
import com.spendly.update.InstallResultReceiver
import com.spendly.update.UpdateManifest
import com.spendly.update.UpdateRepository
import com.spendly.update.UpdateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Every state the update button can be in. Modelled explicitly rather than as a
 * handful of booleans, because "downloading" and "ready" and "failed" are
 * genuinely exclusive and the button's label depends on which one it is.
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val versionName: String) : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateUiState
    data class ReadyToInstall(val manifest: UpdateManifest, val apk: File) : UpdateUiState
    data object AwaitingConfirmation : UpdateUiState
    data object Installed : UpdateUiState
    data object NeedsInstallPermission : UpdateUiState
    data object NotConfigured : UpdateUiState
    data class Failed(val message: String) : UpdateUiState

    /**
     * A debug build can never install a release APK: the debug variant carries
     * the `.debug` applicationId suffix, so Android treats the two as different
     * apps entirely. Worth saying outright rather than letting the signature
     * check reject it with a package-name mismatch nobody can act on.
     */
    data object DebugBuild : UpdateUiState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = UpdateRepository.get(app)
    private val prefs = Prefs.get(app)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val updateSource: StateFlow<String> = prefs.updateSource
    val checkOnOpen: StateFlow<Boolean> = prefs.checkUpdatesOnOpen

    val currentVersionName: String get() = repo.currentVersionName
    val currentVersionCode: Long get() = repo.currentVersionCode

    init {
        // Install results come back through a broadcast receiver, which has no
        // way to reach this ViewModel directly.
        viewModelScope.launch {
            InstallResultReceiver.installEvents.collect { event ->
                _state.value = when (event) {
                    InstallEvent.AwaitingConfirmation -> UpdateUiState.AwaitingConfirmation
                    InstallEvent.Succeeded -> {
                        repo.clearDownloads()
                        UpdateUiState.Installed
                    }
                    InstallEvent.Cancelled -> {
                        // Keep the downloaded file: cancelling usually means
                        // "not now", and re-downloading would be wasteful.
                        val current = _state.value
                        if (current is UpdateUiState.ReadyToInstall) current else UpdateUiState.Idle
                    }
                    is InstallEvent.Failed -> UpdateUiState.Failed(event.message)
                }
            }
        }
    }

    fun setUpdateSource(value: String) {
        prefs.setUpdateSource(value)
        // A changed source invalidates whatever the last check concluded.
        _state.value = UpdateUiState.Idle
    }

    fun setCheckOnOpen(value: Boolean) = prefs.setCheckUpdatesOnOpen(value)

    /** True when the typed source is understandable, for inline validation. */
    fun isSourceValid(value: String): Boolean =
        value.isBlank() || UpdateSource.parse(value) != null

    /** Release builds only — see [UpdateUiState.DebugBuild]. */
    val canUpdate: Boolean = !BuildConfig.DEBUG

    fun check() {
        if (!canUpdate) {
            _state.value = UpdateUiState.DebugBuild
            return
        }
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            _state.value = when (val result = repo.check()) {
                is UpdateRepository.Check.Available -> UpdateUiState.Available(result.manifest)
                is UpdateRepository.Check.UpToDate -> UpdateUiState.UpToDate(result.currentVersionName)
                is UpdateRepository.Check.Failed -> UpdateUiState.Failed(result.message)
                UpdateRepository.Check.NotConfigured -> UpdateUiState.NotConfigured
            }
        }
    }

    /** Called on app open, only when the user opted in and a source is set. */
    fun checkSilentlyIfEnabled() {
        if (!canUpdate) return
        if (!prefs.checkUpdatesOnOpen.value) return
        if (prefs.updateSource.value.isBlank()) return
        if (_state.value != UpdateUiState.Idle) return
        check()
    }

    fun downloadAndInstall(manifest: UpdateManifest) {
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(manifest, 0f)
            when (val result = repo.download(manifest) { progress ->
                _state.value = UpdateUiState.Downloading(manifest, progress)
            }) {
                is UpdateRepository.Download.Ready -> {
                    _state.value = UpdateUiState.ReadyToInstall(manifest, result.apk)
                    install(result.apk)
                }
                is UpdateRepository.Download.Failed ->
                    _state.value = UpdateUiState.Failed(result.message)
            }
        }
    }

    fun install(apk: File) {
        val context = getApplication<Application>()
        if (!ApkInstaller.canInstall(context)) {
            _state.value = UpdateUiState.NeedsInstallPermission
            return
        }
        viewModelScope.launch {
            runCatching { ApkInstaller.install(context, apk) }
                .onFailure {
                    _state.value = UpdateUiState.Failed(
                        it.message ?: "Could not start the install",
                    )
                }
        }
    }

    fun openInstallPermissionSettings() {
        ApkInstaller.openInstallPermissionSettings(getApplication())
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }
}
