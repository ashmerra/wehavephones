package com.wehavephones;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.wehavephones.core.AppUtils;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Shows a link that the container opened (openurl.exe) in a panel over the
 * session, so the session stays in the foreground. Opening the phone's
 * browser instead sends WeHavePhones to the background, where Android may
 * stop it before Battle.net receives its sign-in callback.
 *
 * Battle.net China signs in through the NetEase account page; its last
 * redirect goes to 127.0.0.1:&lt;port&gt;/login-callback, which reaches
 * Battle.net because Wine shares the phone's network. The panel closes by
 * itself shortly after that callback has loaded.
 */
public final class GuestBrowserDialog extends Dialog {
    /** Time for Battle.net to answer the callback request before the panel closes. */
    private static final long CLOSE_AFTER_CALLBACK_MS = 2500;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private ProgressBar progress;
    private String currentUrl;
    private boolean closeScheduled;
    private Runnable onClosed;

    private GuestBrowserDialog(Activity activity) {
        super(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        this.activity = activity;
    }

    /** Shows the panel, or null when this device has no working WebView. */
    public static GuestBrowserDialog create(Activity activity) {
        GuestBrowserDialog dialog = new GuestBrowserDialog(activity);
        try {
            dialog.build();
        }
        catch (RuntimeException e) {
            // Missing or disabled WebView provider.
            return null;
        }
        return dialog;
    }

    /** Runs after the panel closes, however it was closed. */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    public void load(String url) {
        currentUrl = url;
        handler.removeCallbacksAndMessages(null);
        closeScheduled = false;
        webView.loadUrl(url);
        if (!isShowing() && !activity.isFinishing()) show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void build() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#16181d"));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#22252c"));
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText(R.string.guest_browser_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(barButton(R.string.guest_browser_open_external, v -> openExternally()));
        bar.addView(barButton(R.string.guest_browser_close, v -> dismiss()));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(activity);
        webView = new WebView(activity);
        content.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        content.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#16181d")));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(false); // target=_blank opens here
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Some sign-in pages refuse embedded browsers marked "; wv".
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
                if (scheme.equals("http") || scheme.equals("https")) {
                    if (isCallback(uri)) scheduleClose();
                    return false;
                }
                openAppLink(uri.toString());
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Any request to the callback counts: a redirect, a frame, or the
                // success page calling it with a script. Runs on a WebView thread.
                if (isCallback(request.getUrl())) handler.post(GuestBrowserDialog.this::scheduleClose);
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (url != null && !url.startsWith("about:")) currentUrl = url;
            }
        });

        setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            if (onClosed != null) onClosed.run();
        });
    }

    private void scheduleClose() {
        if (closeScheduled || !isShowing()) return;
        closeScheduled = true;
        handler.postDelayed(() -> {
            if (isShowing()) {
                dismiss();
                AppUtils.showToast(activity, R.string.guest_browser_signed_in);
            }
        }, CLOSE_AFTER_CALLBACK_MS);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else dismiss();
    }

    /** Battle.net's local sign-in callback (localbattle.net resolves to 127.0.0.1). */
    static boolean isCallback(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("localbattle.net");
    }

    private void openExternally() {
        String url = currentUrl;
        if (url == null || url.startsWith("about:")) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            activity.startActivity(intent);
            dismiss();
        }
        catch (ActivityNotFoundException e) {
            AppUtils.showToast(activity, R.string.guest_url_no_browser);
        }
    }

    /** Links to other apps (e.g. NetEase's app for QR sign-in), handed to Android. */
    private void openAppLink(String url) {
        try {
            Intent intent = url.startsWith("intent:")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // Only let the link reach apps that accept browser links.
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            activity.startActivity(intent);
        }
        catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
            AppUtils.showToast(activity, R.string.guest_browser_no_app);
        }
    }

    private TextView barButton(int textRes, View.OnClickListener listener) {
        TextView button = new TextView(activity);
        button.setText(textRes);
        button.setTextColor(Color.parseColor("#8ab4ff"));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int p = dp(10);
        button.setPadding(p, p, p, p);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
