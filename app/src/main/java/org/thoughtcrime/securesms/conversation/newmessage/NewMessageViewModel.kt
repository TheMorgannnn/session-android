package org.thoughtcrime.securesms.conversation.newmessage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.snode.SnodeAPI
import org.session.libsignal.utilities.PublicKeyValidation
import org.session.libsignal.utilities.asFlow
import org.thoughtcrime.securesms.ui.GetString
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class NewMessageViewModel @Inject constructor(
    private val application: Application
): AndroidViewModel(application), Callbacks {

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val _event = Channel<Event>()
    val event: Flow<Event> get() = _event.receiveAsFlow()

    private val _qrErrors = Channel<String>()
    val qrErrors: Flow<String> = _qrErrors.receiveAsFlow()

    private var loadOnsJob: Job? = null

    override fun onChange(value: String) {
        loadOnsJob?.cancel()
        loadOnsJob = null

        _state.update { State(newMessageIdOrOns = value) }
    }

    override fun onContinue() {
        createPrivateChatIfPossible(state.value.newMessageIdOrOns)
    }

    override fun onScanQrCode(value: String) {
        if (PublicKeyValidation.isValid(value, isPrefixRequired = false) && PublicKeyValidation.hasValidPrefix(value)) {
            onPublicKey(value)
        } else {
            _qrErrors.trySend(application.getString(R.string.this_qr_code_does_not_contain_an_account_id))
        }
    }

    @OptIn(FlowPreview::class)
    private fun createPrivateChatIfPossible(onsNameOrPublicKey: String) {
        if (loadOnsJob?.isActive == true) return

        if (PublicKeyValidation.isValid(onsNameOrPublicKey, isPrefixRequired = false)) {
            if (PublicKeyValidation.hasValidPrefix(onsNameOrPublicKey)) {
                onPublicKey(onsNameOrPublicKey)
            } else {
                _state.update { it.copy(error = GetString(R.string.accountIdErrorInvalid), loading = false) }
            }
        } else {
            // This could be an ONS name
            _state.update { it.copy(error = null, loading = true) }

            loadOnsJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    // TODO move timeout to SnodeAPI#getSessionID
                    SnodeAPI.getSessionID(onsNameOrPublicKey).asFlow()
                        .timeout(30.seconds)
                        .collectLatest {
                            _state.update { it.copy(loading = false) }
                            onPublicKey(onsNameOrPublicKey)
                        }
                } catch (e: TimeoutCancellationException) {
                    onError(e)
                } catch (e: CancellationException) {
                    // Ignore JobCancellationException, which is called when we cancel the job and
                    // is handled where the job is canceled.
                    // Can't reference JobCancellationException directly, it is internal.
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    private fun onError(e: Exception) {
        _state.update { it.copy(loading = false, error = GetString(e) { it.toMessage() }) }
    }

    private fun onPublicKey(onsNameOrPublicKey: String) {
        viewModelScope.launch { _event.send(Event.Success(onsNameOrPublicKey)) }
    }

    private fun Exception.toMessage() = when (this) {
        is SnodeAPI.Error.Generic -> application.getString(R.string.onsErrorNotRecognized)
        else -> localizedMessage ?: application.getString(R.string.fragment_enter_public_key_error_message)
    }
}

data class State(
    val newMessageIdOrOns: String = "",
    val error: GetString? = null,
    val loading: Boolean = false
) {
    val isNextButtonEnabled: Boolean get() = newMessageIdOrOns.isNotBlank()
}

sealed interface Event {
    data class Success(val key: String): Event
}
