/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

/**
 * Common base class for server settings fragments, so they can be more easily manipulated by
 * AccountSettingsXL.  Provides the following common functionality:
 *
 * Activity-provided callbacks
 * Activity callback during onAttach
 * Present "Next" button and respond to its clicks
 */
public abstract class AccountServerBaseFragment extends Fragment
        implements AccountCheckSettingsFragment.Callbacks, OnClickListener {

    private final static String BUNDLE_KEY_SETTINGS = "AccountServerBaseFragment.settings";

    protected Context mContext;
    protected Callback mCallback = EmptyCallback.INSTANCE;
    protected boolean mSettingsMode;
    // This is null in the setup wizard screens, and non-null in AccountSettings mode
    public Button mProceedButton;
    // This is used to debounce multiple clicks on the proceed button (which does async work)
    public boolean mProceedButtonPressed;

    public static Bundle sSetupModeArgs = null;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        /**
         * Called each time the user-entered input transitions between valid and invalid
         * @param enable true to enable proceed/next button, false to disable
         */
        public void onEnableProceedButtons(boolean enable);

        /**
         * Called when user clicks "next".  Starts account checker.
         * @param checkMode values from {@link SetupData}
         * @param target the fragment that requested the check
         */
        public void onProceedNext(int checkMode, AccountServerBaseFragment target);

        /**
         * Called when account checker completes.  Fragments are responsible for saving
         * own edited data;  This is primarily for the activity to do post-check navigation.
         * @param result check settings result code - success is CHECK_SETTINGS_OK
         * @param setupMode signals if we were editing or creating
         */
        public void onCheckSettingsComplete(int result, int setupMode);
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onEnableProceedButtons(boolean enable) { }
        @Override public void onProceedNext(int checkMode, AccountServerBaseFragment target) { }
        @Override public void onCheckSettingsComplete(int result, int setupMode) { }
    }

    /**
     * Get the static arguments bundle that forces a server settings fragment into "settings" mode
     * (If not included, you'll be in "setup" mode which behaves slightly differently.)
     */
    public static synchronized Bundle getSettingsModeArgs() {
        if (sSetupModeArgs == null) {
            sSetupModeArgs = new Bundle();
            sSetupModeArgs.putBoolean(BUNDLE_KEY_SETTINGS, true);
        }
        return sSetupModeArgs;
    }

    /**
     * At onCreate time, read the fragment arguments
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get arguments, which modally switch us into "settings" mode (different appearance)
        mSettingsMode = false;
        if (getArguments() != null) {
            mSettingsMode = getArguments().getBoolean(BUNDLE_KEY_SETTINGS);
        }

        mProceedButtonPressed = false;
    }

    /**
     * Called from onCreateView, to do settings mode configuration
     */
    protected void onCreateViewSettingsMode(View view) {
        if (mSettingsMode) {
            view.findViewById(R.id.cancel).setOnClickListener(this);
            mProceedButton = (Button) view.findViewById(R.id.done);
            mProceedButton.setOnClickListener(this);
            mProceedButton.setEnabled(false);
        }
    }

    /**
     * Called when a fragment is first attached to its activity.
     * {@link #onCreate(Bundle)} will be called after this.
     */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;

        // Notify the activity that we're here.
        if (activity instanceof AccountSettingsXL) {
            ((AccountSettingsXL)activity).onAttach(this);
        }
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancel:
                getActivity().onBackPressed();
                break;
            case R.id.done:
                // Simple debounce - just ignore while checks are underway
                if (mProceedButtonPressed) {
                    return;
                }
                mProceedButtonPressed = true;
                onNext();
                break;
        }
    }

    /**
     * Activity provides callbacks here.
     */
    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        mContext = getActivity();
    }

    /**
     * Enable/disable the "next" button
     */
    public void enableNextButton(boolean enable) {
        // If we are in settings "mode" we may be showing our own next button, and we'll
        // enable it directly, here
        if (mProceedButton != null) {
            mProceedButton.setEnabled(enable);
        }

        // TODO: This supports the phone UX activities and will be removed
        mCallback.onEnableProceedButtons(enable);
    }

    /**
     * Performs async operations as part of saving changes to the settings.
     *      Check for duplicate account
     *      Display dialog if necessary
     *      Else, proceed via mCallback.onProceedNext
     */
    protected void startDuplicateTaskCheck(long accountId, String checkHost, String checkLogin,
            int checkSettingsMode) {
        new DuplicateCheckTask(accountId, checkHost, checkLogin, checkSettingsMode).execute();
    }

    private class DuplicateCheckTask extends AsyncTask<Void, Void, Account> {

        private final long mAccountId;
        private final String mCheckHost;
        private final String mCheckLogin;
        private final int mCheckSettingsMode;

        public DuplicateCheckTask(long accountId, String checkHost, String checkLogin,
                int checkSettingsMode) {
            mAccountId = accountId;
            mCheckHost = checkHost;
            mCheckLogin = checkLogin;
            mCheckSettingsMode = checkSettingsMode;
        }

        @Override
        protected Account doInBackground(Void... params) {
            EmailContent.Account account = Utility.findExistingAccount(mContext, mAccountId,
                    mCheckHost, mCheckLogin);
            return account;
        }

        @Override
        protected void onPostExecute(Account duplicateAccount) {
            AccountServerBaseFragment fragment = AccountServerBaseFragment.this;
            if (duplicateAccount != null) {
                // Show duplicate account warning
                DuplicateAccountDialogFragment dialogFragment =
                    DuplicateAccountDialogFragment.newInstance(duplicateAccount.mDisplayName);
                dialogFragment.show(fragment.getFragmentManager(),
                        DuplicateAccountDialogFragment.TAG);
            } else {
                // Otherwise, proceed with the save/check
                mCallback.onProceedNext(mCheckSettingsMode, fragment);
            }
            mProceedButtonPressed = false;
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * Handle OK or error result from check settings.  Save settings (async), and then
     * exit to previous fragment.
     */
    @Override
    public void onCheckSettingsComplete(final int settingsResult) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                if (settingsResult == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
                    if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
                        saveSettingsAfterEdit();
                    } else {
                        saveSettingsAfterSetup();
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                // Signal to owning activity that a settings check completed
                mCallback.onCheckSettingsComplete(settingsResult, SetupData.getFlowMode());
            }
        }.execute();
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     * This is overridden only by AccountSetupExchange
     */
    @Override
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        throw new IllegalStateException();
    }

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     * This is called from a worker thread and is allowed to perform DB operations.
     */
    public abstract void saveSettingsAfterEdit();

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     * This is called from a worker thread and is allowed to perform DB operations.
     */
    public abstract void saveSettingsAfterSetup();

    /**
     * Respond to a click of the "Next" button.  Concrete classes must implement.
     */
    public abstract void onNext();
}
