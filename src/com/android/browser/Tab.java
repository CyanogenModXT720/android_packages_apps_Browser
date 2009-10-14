/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.browser;

import java.io.File;
import java.util.LinkedList;
import java.util.Vector;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Message;
import android.provider.Browser;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.webkit.CookieSyncManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebIconDatabase;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Class for maintaining Tabs with a main WebView and a subwindow.
 */
class Tab {
    // Log Tag
    private static final String LOGTAG = "Tab";
    // The Geolocation permissions prompt
    private GeolocationPermissionsPrompt mGeolocationPermissionsPrompt;
    // Main WebView wrapper
    private View mContainer;
    // Main WebView
    private WebView mMainView;
    // Subwindow container
    private View mSubViewContainer;
    // Subwindow WebView
    private WebView mSubView;
    // Saved bundle for when we are running low on memory. It contains the
    // information needed to restore the WebView if the user goes back to the
    // tab.
    private Bundle mSavedState;
    // Data used when displaying the tab in the picker.
    private PickerData mPickerData;
    // Parent Tab. This is the Tab that created this Tab, or null if the Tab was
    // created by the UI
    private Tab mParentTab;
    // Tab that constructed by this Tab. This is used when this Tab is
    // destroyed, it clears all mParentTab values in the children.
    private Vector<Tab> mChildTabs;
    // If true, the tab will be removed when back out of the first page.
    private boolean mCloseOnExit;
    // If true, the tab is in the foreground of the current activity.
    private boolean mInForeground;
    // If true, the tab is in loading state.
    private boolean mInLoad;
    // Application identifier used to find tabs that another application wants
    // to reuse.
    private String mAppId;
    // Keep the original url around to avoid killing the old WebView if the url
    // has not changed.
    private String mOriginalUrl;
    // Error console for the tab
    private ErrorConsoleView mErrorConsole;
    // the lock icon type and previous lock icon type for the tab
    private int mLockIconType;
    private int mPrevLockIconType;
    // Inflation service for making subwindows.
    private final LayoutInflater mInflateService;
    // The BrowserActivity which owners the Tab
    private final BrowserActivity mActivity;

    // AsyncTask for downloading touch icons
    DownloadTouchIcon mTouchIconLoader;

    // Extra saved information for displaying the tab in the picker.
    private static class PickerData {
        String  mUrl;
        String  mTitle;
        Bitmap  mFavicon;
    }

    // Used for saving and restoring each Tab
    static final String WEBVIEW = "webview";
    static final String NUMTABS = "numTabs";
    static final String CURRTAB = "currentTab";
    static final String CURRURL = "currentUrl";
    static final String CURRTITLE = "currentTitle";
    static final String CURRPICTURE = "currentPicture";
    static final String CLOSEONEXIT = "closeonexit";
    static final String PARENTTAB = "parentTab";
    static final String APPID = "appid";
    static final String ORIGINALURL = "originalUrl";

    // -------------------------------------------------------------------------

    // Container class for the next error dialog that needs to be displayed
    private class ErrorDialog {
        public final int mTitle;
        public final String mDescription;
        public final int mError;
        ErrorDialog(int title, String desc, int error) {
            mTitle = title;
            mDescription = desc;
            mError = error;
        }
    };

    private void processNextError() {
        if (mQueuedErrors == null) {
            return;
        }
        // The first one is currently displayed so just remove it.
        mQueuedErrors.removeFirst();
        if (mQueuedErrors.size() == 0) {
            mQueuedErrors = null;
            return;
        }
        showError(mQueuedErrors.getFirst());
    }

    private DialogInterface.OnDismissListener mDialogListener =
            new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    processNextError();
                }
            };
    private LinkedList<ErrorDialog> mQueuedErrors;

    private void queueError(int err, String desc) {
        if (mQueuedErrors == null) {
            mQueuedErrors = new LinkedList<ErrorDialog>();
        }
        for (ErrorDialog d : mQueuedErrors) {
            if (d.mError == err) {
                // Already saw a similar error, ignore the new one.
                return;
            }
        }
        ErrorDialog errDialog = new ErrorDialog(
                err == WebViewClient.ERROR_FILE_NOT_FOUND ?
                R.string.browserFrameFileErrorLabel :
                R.string.browserFrameNetworkErrorLabel,
                desc, err);
        mQueuedErrors.addLast(errDialog);

        // Show the dialog now if the queue was empty and it is in foreground
        if (mQueuedErrors.size() == 1 && mInForeground) {
            showError(errDialog);
        }
    }

    private void showError(ErrorDialog errDialog) {
        if (mInForeground) {
            AlertDialog d = new AlertDialog.Builder(mActivity)
                    .setTitle(errDialog.mTitle)
                    .setMessage(errDialog.mDescription)
                    .setPositiveButton(R.string.ok, null)
                    .create();
            d.setOnDismissListener(mDialogListener);
            d.show();
        }
    }

    // -------------------------------------------------------------------------
    // WebViewClient implementation for the main WebView
    // -------------------------------------------------------------------------

    private final WebViewClient mWebViewClient = new WebViewClient() {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mInLoad = true;

            // We've started to load a new page. If there was a pending message
            // to save a screenshot then we will now take the new page and save
            // an incorrect screenshot. Therefore, remove any pending thumbnail
            // messages from the queue.
            mActivity.removeMessages(BrowserActivity.UPDATE_BOOKMARK_THUMBNAIL,
                    view);

            // If we start a touch icon load and then load a new page, we don't
            // want to cancel the current touch icon loader. But, we do want to
            // create a new one when the touch icon url is known.
            if (mTouchIconLoader != null) {
                mTouchIconLoader.mTab = null;
                mTouchIconLoader = null;
            }

            // reset the error console
            if (mErrorConsole != null) {
                mErrorConsole.clearErrorMessages();
                if (mActivity.shouldShowErrorConsole()) {
                    mErrorConsole.showConsole(ErrorConsoleView.SHOW_NONE);
                }
            }

            // update the bookmark database for favicon
            if (favicon != null) {
                BrowserBookmarksAdapter.updateBookmarkFavicon(mActivity
                        .getContentResolver(), view.getOriginalUrl(), view
                        .getUrl(), favicon);
            }

            // reset sync timer to avoid sync starts during loading a page
            CookieSyncManager.getInstance().resetSync();

            if (!mActivity.isNetworkUp()) {
                view.setNetworkAvailable(false);
            }

            // finally update the UI in the activity if it is in the foreground
            if (mInForeground) {
                mActivity.onPageStarted(view, url, favicon);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mInLoad = false;

            if (mInForeground && !mActivity.didUserStopLoading()
                    || !mInForeground) {
                // Only update the bookmark screenshot if the user did not
                // cancel the load early.
                mActivity.postMessage(
                        BrowserActivity.UPDATE_BOOKMARK_THUMBNAIL, 0, 0, view,
                        500);
            }

            // finally update the UI in the activity if it is in the foreground
            if (mInForeground) {
                mActivity.onPageFinished(view, url);
            }
        }

        // return true if want to hijack the url to let another app to handle it
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (mInForeground) {
                return mActivity.shouldOverrideUrlLoading(view, url);
            } else {
                return false;
            }
        }

        /**
         * Updates the lock icon. This method is called when we discover another
         * resource to be loaded for this page (for example, javascript). While
         * we update the icon type, we do not update the lock icon itself until
         * we are done loading, it is slightly more secure this way.
         */
        @Override
        public void onLoadResource(WebView view, String url) {
            if (url != null && url.length() > 0) {
                // It is only if the page claims to be secure that we may have
                // to update the lock:
                if (mLockIconType == BrowserActivity.LOCK_ICON_SECURE) {
                    // If NOT a 'safe' url, change the lock to mixed content!
                    if (!(URLUtil.isHttpsUrl(url) || URLUtil.isDataUrl(url)
                            || URLUtil.isAboutUrl(url))) {
                        mLockIconType = BrowserActivity.LOCK_ICON_MIXED;
                    }
                }
            }
        }

        /**
         * Show the dialog if it is in the foreground, asking the user if they
         * would like to continue after an excessive number of HTTP redirects.
         * Cancel if it is in the background.
         */
        @Override
        public void onTooManyRedirects(WebView view, final Message cancelMsg,
                final Message continueMsg) {
            if (!mInForeground) {
                cancelMsg.sendToTarget();
                return;
            }
            new AlertDialog.Builder(mActivity).setTitle(
                    R.string.browserFrameRedirect).setMessage(
                    R.string.browserFrame307Post).setPositiveButton(
                    R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            continueMsg.sendToTarget();
                        }
                    }).setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            cancelMsg.sendToTarget();
                        }
                    }).setOnCancelListener(new OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    cancelMsg.sendToTarget();
                }
            }).show();
        }

        /**
         * Show a dialog informing the user of the network error reported by
         * WebCore if it is in the foreground.
         */
        @Override
        public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
            if (errorCode != WebViewClient.ERROR_HOST_LOOKUP &&
                    errorCode != WebViewClient.ERROR_CONNECT &&
                    errorCode != WebViewClient.ERROR_BAD_URL &&
                    errorCode != WebViewClient.ERROR_UNSUPPORTED_SCHEME &&
                    errorCode != WebViewClient.ERROR_FILE) {
                queueError(errorCode, description);
            }
            Log.e(LOGTAG, "onReceivedError " + errorCode + " " + failingUrl
                    + " " + description);

            // We need to reset the title after an error if it is in foreground.
            if (mInForeground) {
                mActivity.resetTitleAndRevertLockIcon();
            }
        }

        /**
         * Check with the user if it is ok to resend POST data as the page they
         * are trying to navigate to is the result of a POST.
         */
        @Override
        public void onFormResubmission(WebView view, final Message dontResend,
                                       final Message resend) {
            if (!mInForeground) {
                dontResend.sendToTarget();
                return;
            }
            new AlertDialog.Builder(mActivity).setTitle(
                    R.string.browserFrameFormResubmitLabel).setMessage(
                    R.string.browserFrameFormResubmitMessage)
                    .setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    resend.sendToTarget();
                                }
                            }).setNegativeButton(R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dontResend.sendToTarget();
                                }
                            }).setOnCancelListener(new OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            dontResend.sendToTarget();
                        }
                    }).show();
        }

        /**
         * Insert the url into the visited history database.
         * @param url The url to be inserted.
         * @param isReload True if this url is being reloaded.
         * FIXME: Not sure what to do when reloading the page.
         */
        @Override
        public void doUpdateVisitedHistory(WebView view, String url,
                boolean isReload) {
            if (url.regionMatches(true, 0, "about:", 0, 6)) {
                return;
            }
            // remove "client" before updating it to the history so that it wont
            // show up in the auto-complete list.
            int index = url.indexOf("client=ms-");
            if (index > 0 && url.contains(".google.")) {
                int end = url.indexOf('&', index);
                if (end > 0) {
                    url = url.substring(0, index)
                            .concat(url.substring(end + 1));
                } else {
                    // the url.charAt(index-1) should be either '?' or '&'
                    url = url.substring(0, index-1);
                }
            }
            Browser.updateVisitedHistory(mActivity.getContentResolver(), url,
                    true);
            WebIconDatabase.getInstance().retainIconForPageUrl(url);
        }

        /**
         * Displays SSL error(s) dialog to the user.
         */
        @Override
        public void onReceivedSslError(final WebView view,
                final SslErrorHandler handler, final SslError error) {
            if (!mInForeground) {
                handler.cancel();
                return;
            }
            if (BrowserSettings.getInstance().showSecurityWarnings()) {
                final LayoutInflater factory =
                    LayoutInflater.from(mActivity);
                final View warningsView =
                    factory.inflate(R.layout.ssl_warnings, null);
                final LinearLayout placeholder =
                    (LinearLayout)warningsView.findViewById(R.id.placeholder);

                if (error.hasError(SslError.SSL_UNTRUSTED)) {
                    LinearLayout ll = (LinearLayout)factory
                        .inflate(R.layout.ssl_warning, null);
                    ((TextView)ll.findViewById(R.id.warning))
                        .setText(R.string.ssl_untrusted);
                    placeholder.addView(ll);
                }

                if (error.hasError(SslError.SSL_IDMISMATCH)) {
                    LinearLayout ll = (LinearLayout)factory
                        .inflate(R.layout.ssl_warning, null);
                    ((TextView)ll.findViewById(R.id.warning))
                        .setText(R.string.ssl_mismatch);
                    placeholder.addView(ll);
                }

                if (error.hasError(SslError.SSL_EXPIRED)) {
                    LinearLayout ll = (LinearLayout)factory
                        .inflate(R.layout.ssl_warning, null);
                    ((TextView)ll.findViewById(R.id.warning))
                        .setText(R.string.ssl_expired);
                    placeholder.addView(ll);
                }

                if (error.hasError(SslError.SSL_NOTYETVALID)) {
                    LinearLayout ll = (LinearLayout)factory
                        .inflate(R.layout.ssl_warning, null);
                    ((TextView)ll.findViewById(R.id.warning))
                        .setText(R.string.ssl_not_yet_valid);
                    placeholder.addView(ll);
                }

                new AlertDialog.Builder(mActivity).setTitle(
                        R.string.security_warning).setIcon(
                        android.R.drawable.ic_dialog_alert).setView(
                        warningsView).setPositiveButton(R.string.ssl_continue,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                handler.proceed();
                            }
                        }).setNeutralButton(R.string.view_certificate,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                mActivity.showSSLCertificateOnError(view,
                                        handler, error);
                            }
                        }).setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                handler.cancel();
                                mActivity.resetTitleAndRevertLockIcon();
                            }
                        }).setOnCancelListener(
                        new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface dialog) {
                                handler.cancel();
                                mActivity.resetTitleAndRevertLockIcon();
                            }
                        }).show();
            } else {
                handler.proceed();
            }
        }

        /**
         * Handles an HTTP authentication request.
         *
         * @param handler The authentication handler
         * @param host The host
         * @param realm The realm
         */
        @Override
        public void onReceivedHttpAuthRequest(WebView view,
                final HttpAuthHandler handler, final String host,
                final String realm) {
            String username = null;
            String password = null;

            boolean reuseHttpAuthUsernamePassword = handler
                    .useHttpAuthUsernamePassword();

            if (reuseHttpAuthUsernamePassword && mMainView != null) {
                String[] credentials = mMainView.getHttpAuthUsernamePassword(
                        host, realm);
                if (credentials != null && credentials.length == 2) {
                    username = credentials[0];
                    password = credentials[1];
                }
            }

            if (username != null && password != null) {
                handler.proceed(username, password);
            } else {
                if (mInForeground) {
                    mActivity.showHttpAuthentication(handler, host, realm,
                            null, null, null, 0);
                } else {
                    handler.cancel();
                }
            }
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            if (!mInForeground) {
                return false;
            }
            if (mActivity.isMenuDown()) {
                // only check shortcut key when MENU is held
                return mActivity.getWindow().isShortcutKey(event.getKeyCode(),
                        event);
            } else {
                return false;
            }
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            if (!mInForeground) {
                return;
            }
            if (event.isDown()) {
                mActivity.onKeyDown(event.getKeyCode(), event);
            } else {
                mActivity.onKeyUp(event.getKeyCode(), event);
            }
        }
    };

    // -------------------------------------------------------------------------
    // WebChromeClient implementation for the main WebView
    // -------------------------------------------------------------------------

    private final WebChromeClient mWebChromeClient = new WebChromeClient() {
        // Helper method to create a new tab or sub window.
        private void createWindow(final boolean dialog, final Message msg) {
            WebView.WebViewTransport transport =
                    (WebView.WebViewTransport) msg.obj;
            if (dialog) {
                createSubWindow();
                mActivity.attachSubWindow(Tab.this);
                transport.setWebView(mSubView);
            } else {
                final Tab newTab = mActivity.openTabAndShow(
                        BrowserActivity.EMPTY_URL_DATA, false, null);
                if (newTab != Tab.this) {
                    Tab.this.addChildTab(newTab);
                }
                transport.setWebView(newTab.getWebView());
            }
            msg.sendToTarget();
        }

        @Override
        public boolean onCreateWindow(WebView view, final boolean dialog,
                final boolean userGesture, final Message resultMsg) {
            // only allow new window or sub window for the foreground case
            if (!mInForeground) {
                return false;
            }
            // Short-circuit if we can't create any more tabs or sub windows.
            if (dialog && mSubView != null) {
                new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.too_many_subwindows_dialog_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.too_many_subwindows_dialog_message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return false;
            } else if (!mActivity.getTabControl().canCreateNewTab()) {
                new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.too_many_windows_dialog_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.too_many_windows_dialog_message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return false;
            }

            // Short-circuit if this was a user gesture.
            if (userGesture) {
                createWindow(dialog, resultMsg);
                return true;
            }

            // Allow the popup and create the appropriate window.
            final AlertDialog.OnClickListener allowListener =
                    new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface d,
                                int which) {
                            createWindow(dialog, resultMsg);
                        }
                    };

            // Block the popup by returning a null WebView.
            final AlertDialog.OnClickListener blockListener =
                    new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            resultMsg.sendToTarget();
                        }
                    };

            // Build a confirmation dialog to display to the user.
            final AlertDialog d =
                    new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.attention)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(R.string.popup_window_attempt)
                    .setPositiveButton(R.string.allow, allowListener)
                    .setNegativeButton(R.string.block, blockListener)
                    .setCancelable(false)
                    .create();

            // Show the confirmation dialog.
            d.show();
            return true;
        }

        @Override
        public void onRequestFocus(WebView view) {
            if (!mInForeground) {
                mActivity.switchToTab(mActivity.getTabControl().getTabIndex(
                        Tab.this));
            }
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (mParentTab != null) {
                // JavaScript can only close popup window.
                if (mInForeground) {
                    mActivity.switchToTab(mActivity.getTabControl()
                            .getTabIndex(mParentTab));
                }
                mActivity.closeTab(Tab.this);
            }
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress == 100) {
                // sync cookies and cache promptly here.
                CookieSyncManager.getInstance().sync();
            }
            if (mInForeground) {
                mActivity.onProgressChanged(view, newProgress);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            String url = view.getUrl();
            if (mInForeground) {
                // here, if url is null, we want to reset the title
                mActivity.setUrlTitle(url, title);
            }
            if (url == null ||
                url.length() >= SQLiteDatabase.SQLITE_MAX_LIKE_PATTERN_LENGTH) {
                return;
            }
            // See if we can find the current url in our history database and
            // add the new title to it.
            if (url.startsWith("http://www.")) {
                url = url.substring(11);
            } else if (url.startsWith("http://")) {
                url = url.substring(4);
            }
            try {
                final ContentResolver cr = mActivity.getContentResolver();
                url = "%" + url;
                String [] selArgs = new String[] { url };
                String where = Browser.BookmarkColumns.URL + " LIKE ? AND "
                        + Browser.BookmarkColumns.BOOKMARK + " = 0";
                Cursor c = cr.query(Browser.BOOKMARKS_URI,
                        Browser.HISTORY_PROJECTION, where, selArgs, null);
                if (c.moveToFirst()) {
                    // Current implementation of database only has one entry per
                    // url.
                    ContentValues map = new ContentValues();
                    map.put(Browser.BookmarkColumns.TITLE, title);
                    cr.update(Browser.BOOKMARKS_URI, map, "_id = "
                            + c.getInt(0), null);
                }
                c.close();
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "Tab onReceived title", e);
            } catch (SQLiteException ex) {
                Log.e(LOGTAG, "onReceivedTitle() caught SQLiteException: ", ex);
            }
        }

        @Override
        public void onReceivedIcon(WebView view, Bitmap icon) {
            if (icon != null) {
                BrowserBookmarksAdapter.updateBookmarkFavicon(mActivity
                        .getContentResolver(), view.getOriginalUrl(), view
                        .getUrl(), icon);
            }
            if (mInForeground) {
                mActivity.setFavicon(icon);
            }
        }

        @Override
        public void onReceivedTouchIconUrl(WebView view, String url,
                boolean precomposed) {
            final ContentResolver cr = mActivity.getContentResolver();
            final Cursor c = BrowserBookmarksAdapter.queryBookmarksForUrl(cr,
                            view.getOriginalUrl(), view.getUrl(), true);
            if (c != null) {
                if (c.getCount() > 0) {
                    // Let precomposed icons take precedence over non-composed
                    // icons.
                    if (precomposed && mTouchIconLoader != null) {
                        mTouchIconLoader.cancel(false);
                        mTouchIconLoader = null;
                    }
                    // Have only one async task at a time.
                    if (mTouchIconLoader == null) {
                        mTouchIconLoader = new DownloadTouchIcon(Tab.this, cr,
                                c, view);
                        mTouchIconLoader.execute(url);
                    }
                } else {
                    c.close();
                }
            }
        }

        @Override
        public void onShowCustomView(View view,
                WebChromeClient.CustomViewCallback callback) {
            if (mInForeground) mActivity.onShowCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            if (mInForeground) mActivity.onHideCustomView();
        }

        /**
         * The origin has exceeded its database quota.
         * @param url the URL that exceeded the quota
         * @param databaseIdentifier the identifier of the database on which the
         *            transaction that caused the quota overflow was run
         * @param currentQuota the current quota for the origin.
         * @param estimatedSize the estimated size of the database.
         * @param totalUsedQuota is the sum of all origins' quota.
         * @param quotaUpdater The callback to run when a decision to allow or
         *            deny quota has been made. Don't forget to call this!
         */
        @Override
        public void onExceededDatabaseQuota(String url,
            String databaseIdentifier, long currentQuota, long estimatedSize,
            long totalUsedQuota, WebStorage.QuotaUpdater quotaUpdater) {
            BrowserSettings.getInstance().getWebStorageSizeManager()
                    .onExceededDatabaseQuota(url, databaseIdentifier,
                            currentQuota, estimatedSize, totalUsedQuota,
                            quotaUpdater);
        }

        /**
         * The Application Cache has exceeded its max size.
         * @param spaceNeeded is the amount of disk space that would be needed
         *            in order for the last appcache operation to succeed.
         * @param totalUsedQuota is the sum of all origins' quota.
         * @param quotaUpdater A callback to inform the WebCore thread that a
         *            new app cache size is available. This callback must always
         *            be executed at some point to ensure that the sleeping
         *            WebCore thread is woken up.
         */
        @Override
        public void onReachedMaxAppCacheSize(long spaceNeeded,
                long totalUsedQuota, WebStorage.QuotaUpdater quotaUpdater) {
            BrowserSettings.getInstance().getWebStorageSizeManager()
                    .onReachedMaxAppCacheSize(spaceNeeded, totalUsedQuota,
                            quotaUpdater);
        }

        /**
         * Instructs the browser to show a prompt to ask the user to set the
         * Geolocation permission state for the specified origin.
         * @param origin The origin for which Geolocation permissions are
         *     requested.
         * @param callback The callback to call once the user has set the
         *     Geolocation permission state.
         */
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                GeolocationPermissions.Callback callback) {
            if (mInForeground) {
                mGeolocationPermissionsPrompt.show(origin, callback);
            }
        }

        /**
         * Instructs the browser to hide the Geolocation permissions prompt.
         */
        @Override
        public void onGeolocationPermissionsHidePrompt() {
            if (mInForeground) {
                mGeolocationPermissionsPrompt.hide();
            }
        }

        /* Adds a JavaScript error message to the system log.
         * @param message The error message to report.
         * @param lineNumber The line number of the error.
         * @param sourceID The name of the source file that caused the error.
         */
        @Override
        public void addMessageToConsole(String message, int lineNumber,
                String sourceID) {
            if (mInForeground) {
                // call getErrorConsole(true) so it will create one if needed
                ErrorConsoleView errorConsole = getErrorConsole(true);
                errorConsole.addErrorMessage(message, sourceID, lineNumber);
                if (mActivity.shouldShowErrorConsole()
                        && errorConsole.getShowState() != ErrorConsoleView.SHOW_MAXIMIZED) {
                    errorConsole.showConsole(ErrorConsoleView.SHOW_MINIMIZED);
                }
            }
            Log.w(LOGTAG, "Console: " + message + " " + sourceID + ":"
                    + lineNumber);
        }

        /**
         * Ask the browser for an icon to represent a <video> element.
         * This icon will be used if the Web page did not specify a poster attribute.
         * @return Bitmap The icon or null if no such icon is available.
         */
        @Override
        public Bitmap getDefaultVideoPoster() {
            if (mInForeground) {
                return mActivity.getDefaultVideoPoster();
            }
            return null;
        }

        /**
         * Ask the host application for a custom progress view to show while
         * a <video> is loading.
         * @return View The progress view.
         */
        @Override
        public View getVideoLoadingProgressView() {
            if (mInForeground) {
                return mActivity.getVideoLoadingProgressView();
            }
            return null;
        }

        @Override
        public void openFileChooser(ValueCallback<Uri> uploadMsg) {
            if (mInForeground) {
                mActivity.openFileChooser(uploadMsg);
            } else {
                uploadMsg.onReceiveValue(null);
            }
        }

        /**
         * Deliver a list of already-visited URLs
         */
        @Override
        public void getVisitedHistory(final ValueCallback<String[]> callback) {
            AsyncTask<Void, Void, String[]> task = new AsyncTask<Void, Void, String[]>() {
                public String[] doInBackground(Void... unused) {
                    return Browser.getVisitedHistory(mActivity
                            .getContentResolver());
                }
                public void onPostExecute(String[] result) {
                    callback.onReceiveValue(result);
                };
            };
            task.execute();
        };
    };

    // -------------------------------------------------------------------------
    // WebViewClient implementation for the sub window
    // -------------------------------------------------------------------------

    // Subclass of WebViewClient used in subwindows to notify the main
    // WebViewClient of certain WebView activities.
    private static class SubWindowClient extends WebViewClient {
        // The main WebViewClient.
        private final WebViewClient mClient;

        SubWindowClient(WebViewClient client) {
            mClient = client;
        }
        @Override
        public void doUpdateVisitedHistory(WebView view, String url,
                boolean isReload) {
            mClient.doUpdateVisitedHistory(view, url, isReload);
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mClient.shouldOverrideUrlLoading(view, url);
        }
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                SslError error) {
            mClient.onReceivedSslError(view, handler, error);
        }
        @Override
        public void onReceivedHttpAuthRequest(WebView view,
                HttpAuthHandler handler, String host, String realm) {
            mClient.onReceivedHttpAuthRequest(view, handler, host, realm);
        }
        @Override
        public void onFormResubmission(WebView view, Message dontResend,
                Message resend) {
            mClient.onFormResubmission(view, dontResend, resend);
        }
        @Override
        public void onReceivedError(WebView view, int errorCode,
                String description, String failingUrl) {
            mClient.onReceivedError(view, errorCode, description, failingUrl);
        }
        @Override
        public boolean shouldOverrideKeyEvent(WebView view,
                android.view.KeyEvent event) {
            return mClient.shouldOverrideKeyEvent(view, event);
        }
        @Override
        public void onUnhandledKeyEvent(WebView view,
                android.view.KeyEvent event) {
            mClient.onUnhandledKeyEvent(view, event);
        }
    }

    // -------------------------------------------------------------------------
    // WebChromeClient implementation for the sub window
    // -------------------------------------------------------------------------

    private class SubWindowChromeClient extends WebChromeClient {
        // The main WebChromeClient.
        private final WebChromeClient mClient;

        SubWindowChromeClient(WebChromeClient client) {
            mClient = client;
        }
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            mClient.onProgressChanged(view, newProgress);
        }
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog,
                boolean userGesture, android.os.Message resultMsg) {
            return mClient.onCreateWindow(view, dialog, userGesture, resultMsg);
        }
        @Override
        public void onCloseWindow(WebView window) {
            if (window != mSubView) {
                Log.e(LOGTAG, "Can't close the window");
            }
            mActivity.dismissSubWindow(Tab.this);
        }
    }

    // -------------------------------------------------------------------------

    // Construct a new tab
    Tab(BrowserActivity activity, WebView w, boolean closeOnExit, String appId,
            String url) {
        mActivity = activity;
        mCloseOnExit = closeOnExit;
        mAppId = appId;
        mOriginalUrl = url;
        mLockIconType = BrowserActivity.LOCK_ICON_UNSECURE;
        mPrevLockIconType = BrowserActivity.LOCK_ICON_UNSECURE;
        mInLoad = false;
        mInForeground = false;

        mInflateService = LayoutInflater.from(activity);

        // The tab consists of a container view, which contains the main
        // WebView, as well as any other UI elements associated with the tab.
        mContainer = mInflateService.inflate(R.layout.tab, null);

        mGeolocationPermissionsPrompt =
            (GeolocationPermissionsPrompt) mContainer.findViewById(
                R.id.geolocation_permissions_prompt);

        setWebView(w);
    }

    /**
     * Sets the WebView for this tab, correctly removing the old WebView from
     * the container view.
     */
    void setWebView(WebView w) {
        if (mMainView == w) {
            return;
        }
        // If the WebView is changing, the page will be reloaded, so any ongoing
        // Geolocation permission requests are void.
        mGeolocationPermissionsPrompt.hide();

        // Just remove the old one.
        FrameLayout wrapper =
                (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
        wrapper.removeView(mMainView);

        // set the new one
        mMainView = w;
        // attached the WebViewClient and WebChromeClient
        if (mMainView != null) {
            mMainView.setWebViewClient(mWebViewClient);
            mMainView.setWebChromeClient(mWebChromeClient);
        }
    }

    /**
     * Destroy the tab's main WebView and subWindow if any
     */
    void destroy() {
        if (mMainView != null) {
            dismissSubWindow();
            BrowserSettings.getInstance().deleteObserver(mMainView.getSettings());
            // save the WebView to call destroy() after detach it from the tab
            WebView webView = mMainView;
            setWebView(null);
            webView.destroy();
        }
    }

    /**
     * Remove the tab from the parent
     */
    void removeFromTree() {
        // detach the children
        if (mChildTabs != null) {
            for(Tab t : mChildTabs) {
                t.setParentTab(null);
            }
        }
        // remove itself from the parent list
        if (mParentTab != null) {
            mParentTab.mChildTabs.remove(this);
        }
    }

    /**
     * Create a new subwindow unless a subwindow already exists.
     * @return True if a new subwindow was created. False if one already exists.
     */
    boolean createSubWindow() {
        if (mSubView == null) {
            mSubViewContainer = mInflateService.inflate(
                    R.layout.browser_subwindow, null);
            mSubView = (WebView) mSubViewContainer.findViewById(R.id.webview);
            // use trackball directly
            mSubView.setMapTrackballToArrowKeys(false);
            mSubView.setWebViewClient(new SubWindowClient(mWebViewClient));
            mSubView.setWebChromeClient(new SubWindowChromeClient(
                    mWebChromeClient));
            mSubView.setDownloadListener(mActivity);
            mSubView.setOnCreateContextMenuListener(mActivity);
            final BrowserSettings s = BrowserSettings.getInstance();
            s.addObserver(mSubView.getSettings()).update(s, null);
            final ImageButton cancel = (ImageButton) mSubViewContainer
                    .findViewById(R.id.subwindow_close);
            cancel.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mSubView.getWebChromeClient().onCloseWindow(mSubView);
                }
            });
            return true;
        }
        return false;
    }

    /**
     * Dismiss the subWindow for the tab.
     */
    void dismissSubWindow() {
        if (mSubView != null) {
            BrowserSettings.getInstance().deleteObserver(
                    mSubView.getSettings());
            mSubView.destroy();
            mSubView = null;
            mSubViewContainer = null;
        }
    }

    /**
     * Attach the sub window to the content view.
     */
    void attachSubWindow(ViewGroup content) {
        if (mSubView != null) {
            content.addView(mSubViewContainer,
                    BrowserActivity.COVER_SCREEN_PARAMS);
        }
    }

    /**
     * Remove the sub window from the content view.
     */
    void removeSubWindow(ViewGroup content) {
        if (mSubView != null) {
            content.removeView(mSubViewContainer);
        }
    }

    /**
     * This method attaches both the WebView and any sub window to the
     * given content view.
     */
    void attachTabToContentView(ViewGroup content) {
        if (mMainView == null) {
            return;
        }

        // Attach the WebView to the container and then attach the
        // container to the content view.
        FrameLayout wrapper =
                (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
        wrapper.addView(mMainView);
        content.addView(mContainer, BrowserActivity.COVER_SCREEN_PARAMS);
        attachSubWindow(content);
    }

    /**
     * Remove the WebView and any sub window from the given content view.
     */
    void removeTabFromContentView(ViewGroup content) {
        if (mMainView == null) {
            return;
        }

        // Remove the container from the content and then remove the
        // WebView from the container. This will trigger a focus change
        // needed by WebView.
        FrameLayout wrapper =
                (FrameLayout) mContainer.findViewById(R.id.webview_wrapper);
        wrapper.removeView(mMainView);
        content.removeView(mContainer);
        removeSubWindow(content);
    }

    /**
     * Set the parent tab of this tab.
     */
    void setParentTab(Tab parent) {
        mParentTab = parent;
        // This tab may have been freed due to low memory. If that is the case,
        // the parent tab index is already saved. If we are changing that index
        // (most likely due to removing the parent tab) we must update the
        // parent tab index in the saved Bundle.
        if (mSavedState != null) {
            if (parent == null) {
                mSavedState.remove(PARENTTAB);
            } else {
                mSavedState.putInt(PARENTTAB, mActivity.getTabControl()
                        .getTabIndex(parent));
            }
        }
    }

    /**
     * When a Tab is created through the content of another Tab, then we
     * associate the Tabs.
     * @param child the Tab that was created from this Tab
     */
    void addChildTab(Tab child) {
        if (mChildTabs == null) {
            mChildTabs = new Vector<Tab>();
        }
        mChildTabs.add(child);
        child.setParentTab(this);
    }

    Vector<Tab> getChildTabs() {
        return mChildTabs;
    }

    void resume() {
        if (mMainView != null) {
            mMainView.onResume();
            if (mSubView != null) {
                mSubView.onResume();
            }
        }
    }

    void pause() {
        if (mMainView != null) {
            mMainView.onPause();
            if (mSubView != null) {
                mSubView.onPause();
            }
        }
    }

    void putInForeground() {
        mInForeground = true;
        resume();
        mMainView.setOnCreateContextMenuListener(mActivity);
        if (mSubView != null) {
            mSubView.setOnCreateContextMenuListener(mActivity);
        }
        // Show the pending error dialog if the queue is not empty
        if (mQueuedErrors != null && mQueuedErrors.size() >  0) {
            showError(mQueuedErrors.getFirst());
        }
    }

    void putInBackground() {
        mInForeground = false;
        pause();
        mMainView.setOnCreateContextMenuListener(null);
        if (mSubView != null) {
            mSubView.setOnCreateContextMenuListener(null);
        }
    }

    /**
     * Return the top window of this tab; either the subwindow if it is not
     * null or the main window.
     * @return The top window of this tab.
     */
    WebView getTopWindow() {
        if (mSubView != null) {
            return mSubView;
        }
        return mMainView;
    }

    /**
     * Return the main window of this tab. Note: if a tab is freed in the
     * background, this can return null. It is only guaranteed to be
     * non-null for the current tab.
     * @return The main WebView of this tab.
     */
    WebView getWebView() {
        return mMainView;
    }

    /**
     * Return the subwindow of this tab or null if there is no subwindow.
     * @return The subwindow of this tab or null.
     */
    WebView getSubWebView() {
        return mSubView;
    }

    /**
     * @return The geolocation permissions prompt for this tab.
     */
    GeolocationPermissionsPrompt getGeolocationPermissionsPrompt() {
        return mGeolocationPermissionsPrompt;
    }

    /**
     * @return The application id string
     */
    String getAppId() {
        return mAppId;
    }

    /**
     * Set the application id string
     * @param id
     */
    void setAppId(String id) {
        mAppId = id;
    }

    /**
     * @return The original url associated with this Tab
     */
    String getOriginalUrl() {
        return mOriginalUrl;
    }

    /**
     * Set the original url associated with this tab
     */
    void setOriginalUrl(String url) {
        mOriginalUrl = url;
    }

    /**
     * Get the url of this tab. Valid after calling populatePickerData, but
     * before calling wipePickerData, or if the webview has been destroyed.
     * @return The WebView's url or null.
     */
    String getUrl() {
        if (mPickerData != null) {
            return mPickerData.mUrl;
        }
        return null;
    }

    /**
     * Get the title of this tab. Valid after calling populatePickerData, but
     * before calling wipePickerData, or if the webview has been destroyed. If
     * the url has no title, use the url instead.
     * @return The WebView's title (or url) or null.
     */
    String getTitle() {
        if (mPickerData != null) {
            return mPickerData.mTitle;
        }
        return null;
    }

    /**
     * Get the favicon of this tab. Valid after calling populatePickerData, but
     * before calling wipePickerData, or if the webview has been destroyed.
     * @return The WebView's favicon or null.
     */
    Bitmap getFavicon() {
        if (mPickerData != null) {
            return mPickerData.mFavicon;
        }
        return null;
    }

    /**
     * Return the tab's error console. Creates the console if createIfNEcessary
     * is true and we haven't already created the console.
     * @param createIfNecessary Flag to indicate if the console should be
     *            created if it has not been already.
     * @return The tab's error console, or null if one has not been created and
     *         createIfNecessary is false.
     */
    ErrorConsoleView getErrorConsole(boolean createIfNecessary) {
        if (createIfNecessary && mErrorConsole == null) {
            mErrorConsole = new ErrorConsoleView(mActivity);
            mErrorConsole.setWebView(mMainView);
        }
        return mErrorConsole;
    }

    /**
     * If this Tab was created through another Tab, then this method returns
     * that Tab.
     * @return the Tab parent or null
     */
    public Tab getParentTab() {
        return mParentTab;
    }

    /**
     * Return whether this tab should be closed when it is backing out of the
     * first page.
     * @return TRUE if this tab should be closed when exit.
     */
    boolean closeOnExit() {
        return mCloseOnExit;
    }

    /**
     * Saves the current lock-icon state before resetting the lock icon. If we
     * have an error, we may need to roll back to the previous state.
     */
    void resetLockIcon(String url) {
        mPrevLockIconType = mLockIconType;
        mLockIconType = BrowserActivity.LOCK_ICON_UNSECURE;
        if (URLUtil.isHttpsUrl(url)) {
            mLockIconType = BrowserActivity.LOCK_ICON_SECURE;
        }
    }

    /**
     * Reverts the lock-icon state to the last saved state, for example, if we
     * had an error, and need to cancel the load.
     */
    void revertLockIcon() {
        mLockIconType = mPrevLockIconType;
    }

    /**
     * @return The tab's lock icon type.
     */
    int getLockIconType() {
        return mLockIconType;
    }

    /**
     * @return TRUE if onPageStarted is called while onPageFinished is not
     *         called yet.
     */
    boolean inLoad() {
        return mInLoad;
    }

    // force mInLoad to be false. This should only be called before closing the
    // tab to ensure BrowserActivity's pauseWebViewTimers() is called correctly.
    void clearInLoad() {
        mInLoad = false;
    }

    void populatePickerData() {
        if (mMainView == null) {
            populatePickerDataFromSavedState();
            return;
        }

        // FIXME: The only place we cared about subwindow was for
        // bookmarking (i.e. not when saving state). Was this deliberate?
        final WebBackForwardList list = mMainView.copyBackForwardList();
        final WebHistoryItem item = list != null ? list.getCurrentItem() : null;
        populatePickerData(item);
    }

    // Populate the picker data using the given history item and the current top
    // WebView.
    private void populatePickerData(WebHistoryItem item) {
        mPickerData = new PickerData();
        if (item != null) {
            mPickerData.mUrl = item.getUrl();
            mPickerData.mTitle = item.getTitle();
            mPickerData.mFavicon = item.getFavicon();
            if (mPickerData.mTitle == null) {
                mPickerData.mTitle = mPickerData.mUrl;
            }
        }
    }

    // Create the PickerData and populate it using the saved state of the tab.
    void populatePickerDataFromSavedState() {
        if (mSavedState == null) {
            return;
        }
        mPickerData = new PickerData();
        mPickerData.mUrl = mSavedState.getString(CURRURL);
        mPickerData.mTitle = mSavedState.getString(CURRTITLE);
    }

    void clearPickerData() {
        mPickerData = null;
    }

    /**
     * Get the saved state bundle.
     * @return
     */
    Bundle getSavedState() {
        return mSavedState;
    }

    /**
     * Set the saved state.
     */
    void setSavedState(Bundle state) {
        mSavedState = state;
    }

    /**
     * @return TRUE if succeed in saving the state.
     */
    boolean saveState() {
        // If the WebView is null it means we ran low on memory and we already
        // stored the saved state in mSavedState.
        if (mMainView == null) {
            return mSavedState != null;
        }

        mSavedState = new Bundle();
        final WebBackForwardList list = mMainView.saveState(mSavedState);
        if (list != null) {
            final File f = new File(mActivity.getTabControl().getThumbnailDir(),
                    mMainView.hashCode() + "_pic.save");
            if (mMainView.savePicture(mSavedState, f)) {
                mSavedState.putString(CURRPICTURE, f.getPath());
            }
        }

        // Store some extra info for displaying the tab in the picker.
        final WebHistoryItem item = list != null ? list.getCurrentItem() : null;
        populatePickerData(item);

        if (mPickerData.mUrl != null) {
            mSavedState.putString(CURRURL, mPickerData.mUrl);
        }
        if (mPickerData.mTitle != null) {
            mSavedState.putString(CURRTITLE, mPickerData.mTitle);
        }
        mSavedState.putBoolean(CLOSEONEXIT, mCloseOnExit);
        if (mAppId != null) {
            mSavedState.putString(APPID, mAppId);
        }
        if (mOriginalUrl != null) {
            mSavedState.putString(ORIGINALURL, mOriginalUrl);
        }
        // Remember the parent tab so the relationship can be restored.
        if (mParentTab != null) {
            mSavedState.putInt(PARENTTAB, mActivity.getTabControl().getTabIndex(
                    mParentTab));
        }
        return true;
    }

    /*
     * Restore the state of the tab.
     */
    boolean restoreState(Bundle b) {
        if (b == null) {
            return false;
        }
        // Restore the internal state even if the WebView fails to restore.
        // This will maintain the app id, original url and close-on-exit values.
        mSavedState = null;
        mPickerData = null;
        mCloseOnExit = b.getBoolean(CLOSEONEXIT);
        mAppId = b.getString(APPID);
        mOriginalUrl = b.getString(ORIGINALURL);

        final WebBackForwardList list = mMainView.restoreState(b);
        if (list == null) {
            return false;
        }
        if (b.containsKey(CURRPICTURE)) {
            final File f = new File(b.getString(CURRPICTURE));
            mMainView.restorePicture(b, f);
            f.delete();
        }
        return true;
    }
}