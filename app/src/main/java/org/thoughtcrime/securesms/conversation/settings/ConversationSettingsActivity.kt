package org.thoughtcrime.securesms.conversation.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.squareup.phrase.Phrase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityConversationSettingsBinding
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.StringSubstitutionConstants.GROUP_NAME_KEY
import org.session.libsession.utilities.StringSubstitutionConstants.NAME_KEY
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.database.GroupDatabase
import org.thoughtcrime.securesms.database.LokiThreadDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.groups.EditGroupActivity
import org.thoughtcrime.securesms.groups.EditLegacyGroupActivity
import org.thoughtcrime.securesms.media.MediaOverviewActivity
import org.thoughtcrime.securesms.showSessionDialog
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class ConversationSettingsActivity: PassphraseRequiredActionBarActivity(), View.OnClickListener {

    companion object {
        // used to trigger displaying conversation search in calling parent activity
        const val RESULT_SEARCH = 22
    }

    lateinit var binding: ActivityConversationSettingsBinding

    private val groupOptions: List<View>
    get() = with(binding) {
        listOf(
            groupMembers,
            groupMembersDivider.root,
            editGroup,
            editGroupDivider.root,
            leaveGroup,
            leaveGroupDivider.root
        )
    }

    @Inject lateinit var threadDb: ThreadDatabase
    @Inject lateinit var groupDb: GroupDatabase
    @Inject lateinit var lokiThreadDb: LokiThreadDatabase
    @Inject lateinit var viewModelFactory: ConversationSettingsViewModel.AssistedFactory
    val viewModel: ConversationSettingsViewModel by viewModels {
        val threadId = intent.getLongExtra(ConversationActivityV2.THREAD_ID, -1L)
        if (threadId == -1L) {
            finish()
        }
        viewModelFactory.create(threadId)
    }

    private val notificationActivityCallback = registerForActivityResult(ConversationNotificationSettingsActivityContract()) {
        updateRecipientDisplay()
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        binding = ActivityConversationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateRecipientDisplay()
        binding.searchConversation.setOnClickListener(this)
        binding.clearMessages.setOnClickListener(this)
        binding.allMedia.setOnClickListener(this)
        binding.pinConversation.setOnClickListener(this)
        binding.notificationSettings.setOnClickListener(this)
        binding.editGroup.setOnClickListener(this)
        binding.leaveGroup.setOnClickListener(this)
        binding.back.setOnClickListener(this)
        binding.autoDownloadMediaSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoDownloadAttachments(isChecked)
            updateRecipientDisplay()
        }
    }

    private fun updateRecipientDisplay() {
        val recipient = viewModel.recipient ?: return
        // Setup profile image
        binding.profilePictureView.root.update(recipient)
        // Setup name
        binding.conversationName.text = when {
            recipient.isLocalNumber -> getString(R.string.noteToSelf)
            else -> recipient.toShortString()
        }
        // Setup group description (if group)
        binding.conversationSubtitle.isVisible = recipient.isClosedGroupV2Recipient.apply {
            binding.conversationSubtitle.text = viewModel.closedGroupInfo()?.description
        }

        // Toggle group-specific settings
        val areGroupOptionsVisible = recipient.isClosedGroupV2Recipient || recipient.isLegacyClosedGroupRecipient
        groupOptions.forEach { v ->
            v.isVisible = areGroupOptionsVisible
        }

        // Group admin settings
        val isUserGroupAdmin = areGroupOptionsVisible && viewModel.isUserGroupAdmin()
        with (binding) {
            groupMembersDivider.root.isVisible = areGroupOptionsVisible && !isUserGroupAdmin
            groupMembers.isVisible = !isUserGroupAdmin
            adminControlsGroup.isVisible = isUserGroupAdmin
            deleteGroup.isVisible = isUserGroupAdmin
            clearMessages.isVisible = isUserGroupAdmin
            clearMessagesDivider.root.isVisible = isUserGroupAdmin
            leaveGroupDivider.root.isVisible = isUserGroupAdmin
        }

        // Set pinned state
        binding.pinConversation.setText(
            if (viewModel.isPinned()) R.string.pinUnpinConversation
            else R.string.pinConversation
        )

        // Set auto-download state
        val trusted = viewModel.autoDownloadAttachments()
        binding.autoDownloadMediaSwitch.isChecked = trusted

        // Set notification type
        val notifyTypes = resources.getStringArray(R.array.notify_types)
        val summary = notifyTypes.getOrNull(recipient.notifyType)
        binding.notificationsValue.text = summary
    }

    override fun onClick(v: View?) {
        val threadRecipient = viewModel.recipient ?: return
        when {
            v === binding.searchConversation -> {
                setResult(RESULT_SEARCH)
                finish()
            }
            v === binding.allMedia -> {
                startActivity(MediaOverviewActivity.createIntent(this, threadRecipient.address))
            }
            v === binding.pinConversation -> {
                viewModel.togglePin().invokeOnCompletion { e ->
                    if (e != null) {
                        // something happened
                        Log.e("ConversationSettings", "Failed to toggle pin on thread", e)
                    } else {
                        updateRecipientDisplay()
                    }
                }
            }
            v === binding.notificationSettings -> {
                notificationActivityCallback.launch(viewModel.threadId)
            }
            v === binding.back -> onBackPressed()
            v === binding.clearMessages -> {

                showSessionDialog {
                    title(R.string.clearMessages)
                    text(Phrase.from(this@ConversationSettingsActivity, R.string.clearMessagesChatDescription)
                        .put(NAME_KEY, threadRecipient.name)
                        .format())
                    dangerButton(
                        R.string.clear,
                        R.string.clear) {
                        viewModel.clearMessages(false)
                    }
                    cancelButton()
                }
            }
            v === binding.leaveGroup -> {

                if (threadRecipient.isLegacyClosedGroupRecipient) {
                    // Send a leave group message if this is an active closed group
                    val groupString = threadRecipient.address.toGroupString()
                    val ourId = TextSecurePreferences.getLocalNumber(this)!!
                    if (groupDb.isActive(groupString)) {
                        showSessionDialog {

                            title(R.string.groupLeave)

                            val name = viewModel.recipient!!.name!!
                            val textWithArgs = if (groupDb.getGroup(groupString).get().admins.map(Address::serialize).contains(ourId)) {
                                Phrase.from(context, R.string.groupLeaveDescriptionAdmin)
                                    .put(GROUP_NAME_KEY, name)
                                    .format()
                            } else {
                                Phrase.from(context, R.string.groupLeaveDescription)
                                    .put(GROUP_NAME_KEY, name)
                                    .format()
                            }
                            text(textWithArgs)
                            dangerButton(
                                R.string.groupLeave,
                                R.string.groupLeave
                            ) {
                                lifecycleScope.launch {
                                    GroupUtil.doubleDecodeGroupID(threadRecipient.address.toString())
                                        .toHexString()
                                        .let { MessageSender.explicitLeave(it, true, deleteThread = true) }
                                    finish()
                                }
                            }
                            cancelButton()
                        }
                        try {

                        } catch (e: IOException) {
                            Log.e("Loki", e)
                        }
                    }
                } else if (threadRecipient.isClosedGroupV2Recipient) {
                    val groupInfo = viewModel.closedGroupInfo()
                    showSessionDialog {

                        title(R.string.groupLeave)

                        val name = viewModel.recipient!!.name!!
                        val textWithArgs = if (groupInfo?.isUserAdmin == true) {
                            Phrase.from(context, R.string.groupLeaveDescription)
                                .put(GROUP_NAME_KEY, name)
                                .format()
                        } else {
                            Phrase.from(context, R.string.groupLeaveDescription)
                                .put(GROUP_NAME_KEY, name)
                                .format()
                        }
                        text(textWithArgs)
                        dangerButton(
                            R.string.groupLeave,
                            R.string.groupLeave
                        ) {
                            lifecycleScope.launch {
                                viewModel.leaveGroup()
                                finish()
                            }
                        }
                        cancelButton()
                    }
                }
            }
            v === binding.editGroup -> {
                val recipient = viewModel.recipient ?: return

                val intent = when {
                    recipient.isLegacyClosedGroupRecipient -> Intent(this, EditLegacyGroupActivity::class.java).apply {
                        val groupID: String = recipient.address.toGroupString()
                        putExtra(EditLegacyGroupActivity.groupIDKey, groupID)
                    }

                    recipient.isClosedGroupV2Recipient -> EditGroupActivity.createIntent(
                        context = this,
                        groupSessionId = recipient.address.serialize()
                    )

                    else -> return
                }
                startActivity(intent)
            }
        }
    }
}