package org.thoughtcrime.securesms;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.annotation.AnimRes;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import org.thoughtcrime.securesms.components.ContactFilterToolbar;
import org.thoughtcrime.securesms.components.ContactFilterToolbar.OnFilterChangedListener;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.loki.fragments.ContactSelectionListFragment;
import org.thoughtcrime.securesms.loki.fragments.ContactSelectionListLoader.DisplayMode;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture.Listener;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;

import java.util.concurrent.ExecutionException;

import network.loki.messenger.R;

public class InviteActivity extends PassphraseRequiredActionBarActivity {

  private ContactSelectionListFragment contactsFragment;
  private EditText                     inviteText;
  private ViewGroup                    smsSendFrame;
  private Button                       smsSendButton;
  private Animation                    slideInAnimation;
  private Animation                    slideOutAnimation;
  private ImageView                    heart;

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    getIntent().putExtra(ContactSelectionListFragment.DISPLAY_MODE, DisplayMode.FLAG_FRIENDS);
    getIntent().putExtra(ContactSelectionListFragment.MULTI_SELECT, true);
    getIntent().putExtra(ContactSelectionListFragment.REFRESHABLE, false);

    setContentView(R.layout.invite_activity);
    assert getSupportActionBar() != null;
    getSupportActionBar().setTitle(R.string.AndroidManifest__invite_friends);

    initializeResources();
  }

  private void initializeResources() {
    slideInAnimation  = loadAnimation(R.anim.slide_from_bottom);
    slideOutAnimation = loadAnimation(R.anim.slide_to_bottom);

    View                 shareButton     = ViewUtil.findById(this, R.id.share_button);
    View                 smsButton       = ViewUtil.findById(this, R.id.sms_button);
    Button               smsCancelButton = ViewUtil.findById(this, R.id.cancel_sms_button);
    ContactFilterToolbar contactFilter   = ViewUtil.findById(this, R.id.contact_filter);

    inviteText        = ViewUtil.findById(this, R.id.invite_text);
    smsSendFrame      = ViewUtil.findById(this, R.id.sms_send_frame);
    smsSendButton     = ViewUtil.findById(this, R.id.send_sms_button);
    heart             = ViewUtil.findById(this, R.id.heart);
    contactsFragment  = (ContactSelectionListFragment)getSupportFragmentManager().findFragmentById(R.id.contact_selection_list_fragment);

    inviteText.setText(getString(R.string.InviteActivity_lets_switch_to_signal, getString(R.string.install_url)));
    updateSmsButtonText();

    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
      heart.getViewTreeObserver().addOnPreDrawListener(new HeartPreDrawListener());
    }
    shareButton.setOnClickListener(new ShareClickListener());
    smsButton.setOnClickListener(new SmsClickListener());
    smsCancelButton.setOnClickListener(new SmsCancelClickListener());
    smsSendButton.setOnClickListener(new SmsSendClickListener());
    contactFilter.setOnFilterChangedListener(new ContactFilterChangedListener());
    contactFilter.setNavigationIcon(R.drawable.ic_search_white_24dp);
  }

  private Animation loadAnimation(@AnimRes int animResId) {
    final Animation animation = AnimationUtils.loadAnimation(this, animResId);
    animation.setInterpolator(new FastOutSlowInInterpolator());
    return animation;
  }

  public void onContactSelected(String number) {
    updateSmsButtonText();
  }

  public void onContactDeselected(String number) {
    updateSmsButtonText();
  }

  private void sendSmsInvites() {
    new SendSmsInvitesAsyncTask(this, inviteText.getText().toString())
        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                           contactsFragment.getSelectedContacts()
                                           .toArray(new String[contactsFragment.getSelectedContacts().size()]));
  }

  private void updateSmsButtonText() {
    smsSendButton.setText(getResources().getQuantityString(R.plurals.InviteActivity_send_sms_to_friends,
                                                           contactsFragment.getSelectedContacts().size(),
                                                           contactsFragment.getSelectedContacts().size()));
    smsSendButton.setEnabled(!contactsFragment.getSelectedContacts().isEmpty());
  }

  @Override public void onBackPressed() {
    if (smsSendFrame.getVisibility() == View.VISIBLE) {
      cancelSmsSelection();
    } else {
      super.onBackPressed();
    }
  }

  private void cancelSmsSelection() {
    updateSmsButtonText();
    ViewUtil.animateOut(smsSendFrame, slideOutAnimation, View.GONE);
  }

  private class ShareClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      Intent sendIntent = new Intent();
      sendIntent.setAction(Intent.ACTION_SEND);
      sendIntent.putExtra(Intent.EXTRA_TEXT, inviteText.getText().toString());
      sendIntent.setType("text/plain");
      if (sendIntent.resolveActivity(getPackageManager()) != null) {
        startActivity(Intent.createChooser(sendIntent, getString(R.string.InviteActivity_invite_to_signal)));
      } else {
        Toast.makeText(InviteActivity.this, R.string.InviteActivity_no_app_to_share_to, Toast.LENGTH_LONG).show();
      }
    }
  }

  private class SmsClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      ViewUtil.animateIn(smsSendFrame, slideInAnimation);
    }
  }

  private class SmsCancelClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      cancelSmsSelection();
    }
  }

  private class SmsSendClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      new AlertDialog.Builder(InviteActivity.this)
          .setTitle(getResources().getQuantityString(R.plurals.InviteActivity_send_sms_invites,
                                                     contactsFragment.getSelectedContacts().size(),
                                                     contactsFragment.getSelectedContacts().size()))
          .setMessage(inviteText.getText().toString())
          .setPositiveButton(R.string.yes, (dialog, which) -> sendSmsInvites())
          .setNegativeButton(R.string.no, (dialog, which) -> dialog.dismiss())
          .show();
    }
  }

  private class ContactFilterChangedListener implements OnFilterChangedListener {
    @Override
    public void onFilterChanged(String filter) {
      contactsFragment.setQueryFilter(filter);
    }
  }

  private class HeartPreDrawListener implements OnPreDrawListener {
    @Override
    @TargetApi(VERSION_CODES.LOLLIPOP)
    public boolean onPreDraw() {
      heart.getViewTreeObserver().removeOnPreDrawListener(this);
      final int w = heart.getWidth();
      final int h = heart.getHeight();
      Animator reveal = ViewAnimationUtils.createCircularReveal(heart,
                                                                w / 2, h,
                                                                0, (float)Math.sqrt(h*h + (w*w/4)));
      reveal.setInterpolator(new FastOutSlowInInterpolator());
      reveal.setDuration(800);
      reveal.start();
      return false;
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class SendSmsInvitesAsyncTask extends ProgressDialogAsyncTask<String,Void,Void> {
    private final String message;

    SendSmsInvitesAsyncTask(Context context, String message) {
      super(context, R.string.InviteActivity_sending, R.string.InviteActivity_sending);
      this.message = message;
    }

    @Override
    protected Void doInBackground(String... numbers) {
      final Context context = getContext();
      if (context == null) return null;

      for (String number : numbers) {
        Recipient recipient      = Recipient.from(context, Address.fromExternal(context, number), false);
        int       subscriptionId = recipient.getDefaultSubscriptionId().or(-1);

        MessageSender.send(context, new OutgoingTextMessage(recipient, message, subscriptionId), -1L, true, null);

        if (recipient.getContactUri() != null) {
          DatabaseFactory.getRecipientDatabase(context).setSeenInviteReminder(recipient, true);
        }
      }

      return null;
    }

    @Override
    protected void onPostExecute(Void aVoid) {
      super.onPostExecute(aVoid);
      final Context context = getContext();
      if (context == null) return;

      ViewUtil.animateOut(smsSendFrame, slideOutAnimation, View.GONE).addListener(new Listener<Boolean>() {
        @Override
        public void onSuccess(Boolean result) {
        }

        @Override
        public void onFailure(ExecutionException e) {}
      });
      Toast.makeText(context, R.string.InviteActivity_invitations_sent, Toast.LENGTH_LONG).show();
    }
  }
}