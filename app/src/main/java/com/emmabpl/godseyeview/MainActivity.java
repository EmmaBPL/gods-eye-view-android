package com.emmabpl.godseyeview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.net.http.SslError;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Plein écran pour God's Eye View.
 * L'appli affiche un serveur God's Eye View (par exemple hébergé sur o2switch)
 * dans une WebView, gère l'identifiant / mot de passe du site et les
 * autorisations micro et GPS.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gev";
    private static final String KEY_URL = "server_url";
    private static final String KEY_USER = "auth_user";
    private static final String KEY_PASS = "auth_pass";
    private static final String KEY_UI_WIDTH = "ui_width";

    // Largeur virtuelle de la page (points CSS). L'interface de God's Eye View
    // est prévue pour des écrans plus larges que celui d'un téléphone : à la
    // largeur réelle (~400 pt) ses panneaux se chevauchent. 0 = largeur réelle.
    private static final int DEFAULT_UI_WIDTH = 640;
    private static final int[] UI_WIDTHS = {0, 520, 640, 800};
    private static final String[] UI_WIDTH_LABELS = {
            "Normale (taille réelle du téléphone)",
            "Un peu plus compacte",
            "Compacte (recommandée)",
            "Très compacte (tout petit)"};

    private static final int REQ_MEDIA = 2;
    private static final int REQ_GEO = 3;
    private static final int REQ_FILE = 4;

    private static final int ACCENT = 0xFF7CFFB2;
    private static final int BACKGROUND = 0xFF05080D;
    private static final int TEXT = 0xFFD0D8E0;

    private SharedPreferences prefs;

    private WebView webView;
    private View statusPanel;
    private TextView statusText;
    private ProgressBar progress;
    private LinearLayout statusButtons;
    private View setupPanel;
    private EditText urlInput;

    private boolean webShown = false;
    private int authAttempts = 0;

    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Utilise tout l'écran, y compris autour de la caméra frontale ;
            // la page reçoit les marges de sécurité (viewport-fit=cover).
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        webView = buildWebView();
        root.addView(webView, matchParent());

        statusPanel = buildStatusPanel();
        root.addView(statusPanel, matchParent());

        setupPanel = buildSetupPanel();
        root.addView(setupPanel, matchParent());

        setContentView(root);
        hideSystemBars();

        if (serverUrl().isEmpty()) {
            showSetup();
        } else {
            loadServer();
        }
    }

    private String serverUrl() {
        return prefs.getString(KEY_URL, "");
    }

    private String serverHost() {
        String url = serverUrl();
        return url.isEmpty() ? "" : String.valueOf(Uri.parse(url).getHost());
    }

    // ---------------------------------------------------------------- WebView

    private WebView buildWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(BACKGROUND);
        view.setVisibility(View.GONE);

        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " GodsEyeViewAndroid/1.0");
        // Ignore le réglage « taille du texte » d'Android : il grossit tous les
        // textes de la page et fait déborder les panneaux les uns sur les autres.
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isServer(uri)) return false;
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Les liens externes s'ouvrent dans le navigateur.
                    openExternal(uri);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                applyViewport(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (webShown) authAttempts = 0;
                applyViewport(v);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    webShown = false;
                    showStatus("Impossible de joindre le serveur.\n\n"
                            + serverUrl() + "\n\n"
                            + "Vérifie ta connexion Internet, ou que l'appli tourne bien dans cPanel.",
                            false);
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                    webShown = false;
                    showStatus("Le serveur a répondu par une erreur ("
                            + response.getStatusCode() + ").\n\n"
                            + "Il est peut-être en train de redémarrer : réessaie dans une minute.",
                            false);
                }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();
                webShown = false;
                showStatus("Le certificat HTTPS du serveur n'est pas valide.\n\n"
                        + "Dans cPanel, ouvre « SSL/TLS Status » et lance AutoSSL pour cette adresse.",
                        false);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler handler, String host, String realm) {
                String user = prefs.getString(KEY_USER, "");
                String pass = prefs.getString(KEY_PASS, "");
                if (authAttempts == 0 && !user.isEmpty()) {
                    authAttempts++;
                    handler.proceed(user, pass);
                } else {
                    askCredentials(handler, authAttempts > 0);
                }
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!isServer(Uri.parse(origin))) {
                    callback.invoke(origin, false, false);
                } else if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            Uri uri = Uri.parse(url);
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) openExternal(uri);
        });

        return view;
    }

    private int uiWidth() {
        return prefs.getInt(KEY_UI_WIDTH, DEFAULT_UI_WIDTH);
    }

    /** Fixe la largeur virtuelle de la page et active les zones sûres (encoche). */
    private void applyViewport(WebView v) {
        if (v == null || !isServer(Uri.parse(String.valueOf(v.getUrl())))) return;
        int width = uiWidth();
        String content = (width > 0 ? "width=" + width : "width=device-width, initial-scale=1")
                + ", viewport-fit=cover, user-scalable=no";
        String js = "(function(c){var m=document.querySelector('meta[name=viewport]');"
                + "if(!m){m=document.createElement('meta');m.name='viewport';"
                + "(document.head||document.documentElement).appendChild(m);}"
                + "if(m.content!==c){m.content=c;window.dispatchEvent(new Event('resize'));}"
                + "})('" + content + "');";
        v.evaluateJavascript(js, null);
    }

    private void chooseUiWidth() {
        int current = uiWidth();
        int checked = 2;
        for (int i = 0; i < UI_WIDTHS.length; i++) if (UI_WIDTHS[i] == current) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("Taille de l'interface")
                .setSingleChoiceItems(UI_WIDTH_LABELS, checked, (d, which) -> {
                    prefs.edit().putInt(KEY_UI_WIDTH, UI_WIDTHS[which]).apply();
                    d.dismiss();
                    loadServer();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private boolean isServer(Uri uri) {
        String host = uri.getHost();
        return host != null && host.equalsIgnoreCase(serverHost());
    }

    private void handleWebPermission(PermissionRequest request) {
        if (!isServer(request.getOrigin())) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (missing.isEmpty()) {
            grantAllowed(request);
        } else {
            if (pendingWebPermission != null) pendingWebPermission.deny();
            pendingWebPermission = request;
            requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
        }
    }

    private void grantAllowed(PermissionRequest request) {
        List<String> allowed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                allowed.add(resource);
            } else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                allowed.add(resource);
            }
        }
        if (allowed.isEmpty()) request.deny();
        else request.grant(allowed.toArray(new String[0]));
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------- Mot de passe

    private void askCredentials(HttpAuthHandler handler, boolean failed) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText user = new EditText(this);
        user.setHint("Identifiant");
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user.setText(prefs.getString(KEY_USER, ""));
        form.addView(user);

        EditText pass = new EditText(this);
        pass.setHint("Mot de passe");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle(failed ? "Identifiant ou mot de passe incorrect" : "Connexion au serveur")
                .setMessage("Ces codes seront mémorisés sur ce téléphone.")
                .setView(form)
                .setCancelable(false)
                .setPositiveButton("Se connecter", (d, w) -> {
                    String u = user.getText().toString().trim();
                    String p = pass.getText().toString();
                    prefs.edit().putString(KEY_USER, u).putString(KEY_PASS, p).apply();
                    authAttempts++;
                    handler.proceed(u, p);
                })
                .setNegativeButton("Annuler", (d, w) -> {
                    handler.cancel();
                    webShown = false;
                    showStatus("Connexion annulée.", false);
                })
                .show();
    }

    // ----------------------------------------------------------- Écrans

    private View buildSetupPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setVisibility(View.GONE);

        LinearLayout column = column();
        column.addView(title(), wrap());

        TextView intro = new TextView(this);
        intro.setText("Adresse de ton serveur God's Eye View :");
        intro.setTextColor(TEXT);
        intro.setTextSize(15);
        intro.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams introParams = wrap();
        introParams.topMargin = dp(28);
        column.addView(intro, introParams);

        urlInput = new EditText(this);
        urlInput.setHint("https://…");
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(0xFF6B7785);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(12);
        column.addView(urlInput, inputParams);

        addButton(column, "Enregistrer et ouvrir", v -> saveUrlAndLoad(), true);

        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void saveUrlAndLoad() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        if (!url.endsWith("/")) url = url + "/";
        String oldHost = serverHost();
        prefs.edit().putString(KEY_URL, url).apply();
        if (!oldHost.equalsIgnoreCase(serverHost())) {
            prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply();
        }
        loadServer();
    }

    private void showSetup() {
        urlInput.setText(serverUrl());
        setupPanel.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        webShown = false;
    }

    private View buildStatusPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        LinearLayout column = column();
        column.addView(title(), wrap());

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = wrap();
        progressParams.topMargin = dp(28);
        column.addView(progress, progressParams);

        statusText = new TextView(this);
        statusText.setTextColor(TEXT);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams textParams = wrap();
        textParams.topMargin = dp(24);
        textParams.bottomMargin = dp(16);
        column.addView(statusText, textParams);

        statusButtons = new LinearLayout(this);
        statusButtons.setOrientation(LinearLayout.VERTICAL);
        statusButtons.setGravity(Gravity.CENTER_HORIZONTAL);
        addButton(statusButtons, "Réessayer", v -> loadServer(), true);
        addButton(statusButtons, "Changer l'adresse du serveur", v -> showSetup(), false);
        addButton(statusButtons, "Effacer l'identifiant mémorisé", v -> {
            prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply();
            webView.clearCache(false);
            loadServer();
        }, false);
        column.addView(statusButtons, wrap());

        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = dp(32);
        column.setPadding(pad, pad, pad, pad);
        return column;
    }

    private TextView title() {
        TextView title = new TextView(this);
        title.setText("GOD'S EYE VIEW");
        title.setTextColor(ACCENT);
        title.setTextSize(24);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.15f);
        return title;
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? BACKGROUND : ACCENT);
        b.setBackgroundColor(primary ? ACCENT : 0xFF14202B);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(300), dp(52));
        p.topMargin = dp(12);
        parent.addView(b, p);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showStatus(String message, boolean busy) {
        statusText.setText(message);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusButtons.setVisibility(busy ? View.GONE : View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);
        setupPanel.setVisibility(View.GONE);
        if (!webShown) webView.setVisibility(View.GONE);
    }

    private void loadServer() {
        authAttempts = 0;
        webShown = true;
        setupPanel.setVisibility(View.GONE);
        statusPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(serverUrl());
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ------------------------------------------------------------ Callbacks

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MEDIA) {
            if (pendingWebPermission != null) {
                grantAllowed(pendingWebPermission);
                pendingWebPermission = null;
            }
        } else if (requestCode == REQ_GEO) {
            if (pendingGeoCallback != null) {
                pendingGeoCallback.invoke(pendingGeoOrigin, hasLocationPermission(), false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webShown && webView.getVisibility() == View.VISIBLE) {
            // Ferme d'abord une caméra agrandie, s'il y en a une.
            webView.evaluateJavascript(
                    "(function(){var o=document.querySelector('.gev-cctv-zoom');"
                            + "if(o){o.click();return 1;}return 0;})()",
                    result -> {
                        if (!"1".equals(result)) handleBack();
                    });
            return;
        }
        handleBack();
    }

    private void handleBack() {
        if (webShown && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (setupPanel.getVisibility() == View.VISIBLE && !serverUrl().isEmpty()) {
            loadServer();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("God's Eye View")
                .setItems(new String[]{"Quitter", "Recharger", "Taille de l'interface",
                        "Changer l'adresse du serveur"}, (d, which) -> {
                    if (which == 0) finish();
                    else if (which == 1) loadServer();
                    else if (which == 2) chooseUiWidth();
                    else showSetup();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
    }
}
