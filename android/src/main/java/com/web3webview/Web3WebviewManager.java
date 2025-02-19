/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.web3webview;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.scroll.OnScrollDispatchHelper;
import com.facebook.react.views.scroll.ScrollEvent;
import com.facebook.react.views.scroll.ScrollEventType;
import com.facebook.react.views.webview.WebViewConfig;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
import com.facebook.react.views.webview.events.TopMessageEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;

import static okhttp3.internal.Util.UTF_8;

/**
 * Manages instances of {@link WebView}
 *
 * Can accept following commands:
 *  - GO_BACK
 *  - GO_FORWARD
 *  - RELOAD
 *
 * {@link WebView} instances could emit following direct events:
 *  - topLoadingFinish
 *  - topLoadingStart
 *  - topLoadingError
 *
 * Each event will carry the following properties:
 *  - target - view's react tag
 *  - url - url set for the webview
 *  - loading - whether webview is in a loading state
 *  - title - title of the current page
 *  - canGoBack - boolean, whether there is anything on a history stack to go back
 *  - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = Web3WebviewManager.REACT_CLASS)
public class Web3WebviewManager extends SimpleViewManager<WebView> {

    protected static final String REACT_CLASS = "Web3Webview";

    public final static String HEADER_CONTENT_TYPE = "content-type";
    private static final String MIME_TEXT_HTML = "text/html";
    private static final String MIME_UNKNOWN = "application/octet-stream";
    protected static final String HTML_ENCODING = "UTF-8";
    protected static final String HTML_MIME_TYPE = "text/html";

    protected static final String BRIDGE_NAME = "__REACT_WEB_VIEW_BRIDGE";
    private OkHttpClient httpClient;

    protected static final String HTTP_METHOD_POST = "POST";

    public static final int COMMAND_GO_BACK = 1;
    public static final int COMMAND_GO_FORWARD = 2;
    public static final int COMMAND_RELOAD = 3;
    public static final int COMMAND_STOP_LOADING = 4;
    public static final int COMMAND_POST_MESSAGE = 5;
    public static final int COMMAND_INJECT_JAVASCRIPT = 6;
    public static final int COMMAND_LOAD_URL = 7;

    // Use `webView.loadUrl("about:blank")` to reliably reset the view
    // state and release page resources (including any running JavaScript).
    protected static final String BLANK_URL = "about:blank";

    protected WebViewConfig mWebViewConfig;
    protected WebSettings mWebviewSettings;
    private static ReactApplicationContext reactNativeContext;
    private static boolean debug;
    private Web3WebviewPackage pkg;
    protected @NonNull WebView.PictureListener mPictureListener;

    protected class Web3WebviewClient extends WebViewClient {

        protected boolean mLastLoadFailed = false;
        protected @NonNull ReadableArray mUrlPrefixesForDefaultIntent;
        protected @NonNull List<Pattern> mOriginWhitelist;


        @Override
        public void onPageFinished(WebView webView, String url) {
            super.onPageFinished(webView, url);

            if (!mLastLoadFailed) {
                Web3Webview Web3Webview = (Web3Webview) webView;
                Web3Webview.callInjectedJavaScript();
                Web3Webview.setVerticalScrollBarEnabled(true);
                Web3Webview.setHorizontalScrollBarEnabled(true);
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                emitFinishEvent(webView, url);
                Web3Webview.linkBridge();
            }
        }



        @Override
        public void onPageStarted(final WebView webView, String url, Bitmap favicon) {
            super.onPageStarted(webView, url, favicon);

            mLastLoadFailed = false;
            dispatchEvent(
                    webView,
                    new TopLoadingStartEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
            Web3Webview Web3Webview = (Web3Webview) webView;
        }


        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || view == null) {
                return false;
            }


			String url = request.getUrl().toString();
			// Disabling the URL schemes that cause problems
			String[] blacklistedUrls = { "intent:#Intent;action=com.ledger.android.u2f.bridge.AUTHENTICATE" };
			for(int i=0; i< blacklistedUrls.length; i++){
				String badUrl = blacklistedUrls[i];
				if(url.contains(badUrl)){
					return true;
				}
			}

            // This works only for API 24+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (request.isForMainFrame() && request.isRedirect()) {

					view.loadUrl(url);
					return true;
                }
            }

            return super.shouldOverrideUrlLoading(view, request);
        }



        private void launchIntent(Context context, String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                FLog.w(ReactConstants.TAG, "activity not found to handle uri scheme for: " + url, e);
            }
        }

        private boolean shouldHandleURL(List<Pattern> originWhitelist, String url) {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "";
            String authority = uri.getAuthority() != null ? uri.getAuthority() : "";
            String urlToCheck = scheme + "://" + authority;
            for (Pattern pattern : originWhitelist) {
                if (pattern.matcher(urlToCheck).matches()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onReceivedError(
                WebView webView,
                int errorCode,
                String description,
                String failingUrl) {
            super.onReceivedError(webView, errorCode, description, failingUrl);
            mLastLoadFailed = true;

            emitFinishEvent(webView, failingUrl);

            WritableMap eventData = createWebViewEvent(webView, failingUrl);
            eventData.putDouble("code", errorCode);
            eventData.putString("description", description);

            dispatchEvent(
                    webView,
                    new TopLoadingErrorEvent(webView.getId(), eventData));
        }

        @Override
        public void doUpdateVisitedHistory(WebView webView, String url, boolean isReload) {
            super.doUpdateVisitedHistory(webView, url, isReload);
            dispatchEvent(
                    webView,
                    new TopLoadingStartEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
        }

        protected void emitFinishEvent(WebView webView, String url) {
            dispatchEvent(
                    webView,
                    new TopLoadingFinishEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
        }

        protected WritableMap createWebViewEvent(WebView webView, String url) {
            WritableMap event = Arguments.createMap();
            event.putDouble("target", webView.getId());
            event.putString("url", url);
            event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
            event.putString("title", webView.getTitle());
            event.putBoolean("canGoBack", webView.canGoBack());
            event.putBoolean("canGoForward", webView.canGoForward());
            return event;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return null;
        }
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse response = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                response = Web3WebviewManager.this.shouldInterceptRequest(request, true, (Web3Webview) view);
                if (response != null) {
                    return response;
                }
            }

            return super.shouldInterceptRequest(view, request);
        }


        public void setUrlPrefixesForDefaultIntent(ReadableArray specialUrls) {
            mUrlPrefixesForDefaultIntent = specialUrls;
        }

        public void setOriginWhitelist(List<Pattern> originWhitelist) {
            mOriginWhitelist = originWhitelist;
        }
    }

    /**
     * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
     * to call {@link WebView#destroy} on activity destroy event and also to clear the client
     */
    protected static class Web3Webview extends WebView implements LifecycleEventListener {
        protected @NonNull String injectedJS;
        protected @NonNull String injectedOnStartLoadingJS;
        protected boolean messagingEnabled = false;
        protected @NonNull Web3WebviewClient mWeb3WebviewClient;
        private final OnScrollDispatchHelper mOnScrollDispatchHelper = new OnScrollDispatchHelper();

        protected class Web3WebviewBridge {
            Web3Webview mContext;

            Web3WebviewBridge(Web3Webview c) {
                mContext = c;
            }

            @JavascriptInterface
            public void postMessage(String message) {
                mContext.onMessage(message);
            }
        }

        /**
         * WebView must be created with an context of the current activity
         *
         * Activity Context is required for creation of dialogs internally by WebView
         * Reactive Native needed for access to ReactNative internal system functionality
         *
         */
        public Web3Webview(ThemedReactContext reactContext) {
            super(reactContext);
        }

        @Override
        public void onHostResume() {
            // do nothing
        }

        @Override
        public void onHostPause() {
            // do nothing
        }

        @Override
        public void onHostDestroy() {
            cleanupCallbacksAndDestroy();
        }
        

        @Override
        public void setWebViewClient(WebViewClient client) {
            super.setWebViewClient(client);
            mWeb3WebviewClient = (Web3WebviewClient)client;
        }

        public @NonNull Web3WebviewClient getWeb3WebviewClient() {
            return mWeb3WebviewClient;
        }

        public void setInjectedJavaScript(@NonNull String js) {
            injectedJS = js;
        }

        public void setInjectedOnStartLoadingJavaScript(@NonNull String js) {
            injectedOnStartLoadingJS = js;
        }

        protected Web3WebviewBridge createWeb3WebviewBridge(Web3Webview webView) {
            return new Web3WebviewBridge(webView);
        }

        public void setMessagingEnabled(boolean enabled) {
            if (messagingEnabled == enabled) {
                return;
            }

            messagingEnabled = enabled;
            if (enabled) {
                addJavascriptInterface(createWeb3WebviewBridge(this), BRIDGE_NAME);
            } else {
                removeJavascriptInterface(BRIDGE_NAME);
            }
        }

        protected void evaluateJavascriptWithFallback(String script) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                evaluateJavascript(script, null);
                return;
            }

            try {
                loadUrl("javascript:" + URLEncoder.encode(script, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // UTF-8 should always be supported
                throw new RuntimeException(e);
            }
        }


        public void callInjectedJavaScript() {
            if (getSettings().getJavaScriptEnabled() &&
                    injectedJS != null &&
                    !TextUtils.isEmpty(injectedJS)) {
                evaluateJavascriptWithFallback("(function() {\n" + injectedJS + ";\n})();");
            }
        }

        public void linkBridge() {
            if (messagingEnabled) {
                String script = "(" +
                        "window.postMessageToNative = function(data) {"+
                            BRIDGE_NAME + ".postMessage(JSON.stringify(data));"+
                        "}"+
                ")";
                evaluateJavascriptWithFallback(script);

            }
        }

        public void unlinkBridge() {
            this.loadUrl("about:blank");
        }

        public void onMessage(String message) {
            dispatchEvent(this, new TopMessageEvent(this.getId(), message));
        }



        protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            if (mOnScrollDispatchHelper.onScrollChanged(x, y)) {
                ScrollEvent event = ScrollEvent.obtain(
                this.getId(),
                ScrollEventType.SCROLL,
                x,
                y,
                mOnScrollDispatchHelper.getXFlingVelocity(),
                mOnScrollDispatchHelper.getYFlingVelocity(),
                this.computeHorizontalScrollRange(),
                this.computeVerticalScrollRange(),
                this.getWidth(),
                this.getHeight());
                dispatchEvent(this, event);
            }
        }

        protected void cleanupCallbacksAndDestroy() {
            setWebViewClient(null);
            destroy();
        }
    }

    public Web3WebviewManager(ReactApplicationContext context, com.web3webview.Web3WebviewPackage pkg) {
        this.reactNativeContext = context;
        this.pkg = pkg;
        Builder b = new Builder();
        httpClient = b
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        mWebViewConfig = new WebViewConfig() {
            public void configWebView(WebView webView) {
            }
        };
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    public static Boolean urlStringLooksInvalid(String urlString) {
        return urlString == null ||
                urlString.trim().equals("") ||
                !(urlString.startsWith("http") && !urlString.startsWith("www")) ||
                urlString.contains("|");
    }

    public static Boolean responseRequiresJSInjection(Response response) {
        if (response.isRedirect()) {
            return false;
        }
        final String contentTypeAndCharset = response.header(HEADER_CONTENT_TYPE, MIME_UNKNOWN);
        return contentTypeAndCharset.startsWith(MIME_TEXT_HTML);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request, Boolean onlyMainFrame, Web3Webview webView) {
        Uri url = request.getUrl();
        String urlStr = url.toString();
        if (onlyMainFrame && !request.isForMainFrame()) {
            return null;
        }
        if (Web3WebviewManager.urlStringLooksInvalid(urlStr)) {
            return null;
        }
        try {
            String ua = mWebviewSettings.getUserAgentString();

            Request req = new Request.Builder()
                    .header("User-Agent", ua)
                    .url(urlStr)
                    .build();
            Response response = httpClient.newCall(req).execute();
            if (!Web3WebviewManager.responseRequiresJSInjection(response)) {
                return null;
            }
            InputStream is = response.body().byteStream();
            MediaType contentType = response.body().contentType();
            Charset charset = contentType != null ? contentType.charset(UTF_8) : UTF_8;
            if (response.code() == HttpURLConnection.HTTP_OK) {
                is = new InputStreamWithInjectedJS(is, webView.injectedOnStartLoadingJS, charset, webView.getContext());
            }
            return new WebResourceResponse("text/html", charset.name(), is);
        } catch (IOException e) {
            return null;
        }
    }



    protected Web3Webview createWeb3WebviewInstance(ThemedReactContext reactContext) {
        return new Web3Webview(reactContext);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    protected WebView createViewInstance(final ThemedReactContext reactContext) {
        final Web3Webview webView = createWeb3WebviewInstance(reactContext);


        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (ReactBuildConfig.DEBUG) {
                    return super.onConsoleMessage(message);
                }
                // Ignore console logs in non debug builds.
                return true;
            }
            public void onProgressChanged(WebView view, int progress) {
                dispatchEvent(view, new ProgressEvent(view.getId(), progress));

                if(webView.getProgress() >= 10){
                    webView.linkBridge();
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            protected void openFileChooser(ValueCallback<Uri> filePathCallback, String acceptType) {
                getModule(reactContext).startPhotoPickerIntent(filePathCallback, acceptType);
            }

            protected void openFileChooser(ValueCallback<Uri> filePathCallback) {
                getModule(reactContext).startPhotoPickerIntent(filePathCallback, "");
            }

            protected void openFileChooser(ValueCallback<Uri> filePathCallback, String acceptType, String capture) {
                getModule(reactContext).startPhotoPickerIntent(filePathCallback, acceptType);
            }

            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean allowMultiple = fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
                Intent intent = fileChooserParams.createIntent();
                return getModule(reactContext).startPhotoPickerIntent(filePathCallback, intent, acceptTypes, allowMultiple);
            }


        });
        reactContext.addLifecycleEventListener(webView);
        mWebViewConfig.configWebView(webView);
        WebSettings settings = webView.getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAppCacheEnabled (true);
        settings.setLoadWithOverviewMode(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            setAllowUniversalAccessFromFileURLs(webView, false);
        }
        setMixedContentMode(webView, "never");

        // Fixes broken full-screen modals/galleries due to body height being 0.
        webView.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));

        setGeolocationEnabled(webView, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController swController = ServiceWorkerController.getInstance();
            swController.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    WebResourceResponse response = Web3WebviewManager.this.shouldInterceptRequest(request, false, webView);
                    if (response != null) {
                        return response;
                    }
                    return super.shouldInterceptRequest(request);
                }
            });
        }

        mWebviewSettings = settings;

        return webView;
    }

    @ReactProp(name = "javaScriptEnabled")
    public void setJavaScriptEnabled(WebView view, boolean enabled) {
        view.getSettings().setJavaScriptEnabled(enabled);
    }

    @ReactProp(name = "thirdPartyCookiesEnabled")
    public void setThirdPartyCookiesEnabled(WebView view, boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, enabled);
        }
    }

    @ReactProp(name = "scalesPageToFit")
    public void setScalesPageToFit(WebView view, boolean enabled) {
        view.getSettings().setUseWideViewPort(!enabled);
    }

    @ReactProp(name = "domStorageEnabled")
    public void setDomStorageEnabled(WebView view, boolean enabled) {
        view.getSettings().setDomStorageEnabled(enabled);
    }

    @ReactProp(name = "userAgent")
    public void setUserAgent(WebView view, @NonNull String userAgent) {
        if (userAgent != null) {
            view.getSettings().setUserAgentString(userAgent);
        }
    }

    @ReactProp(name = "mediaPlaybackRequiresUserAction")
    public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
        if(Build.VERSION.SDK_INT >= 17) {
            view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
        }
    }

    @ReactProp(name = "allowUniversalAccessFromFileURLs")
    public void setAllowUniversalAccessFromFileURLs(WebView view, boolean allow) {
        view.getSettings().setAllowUniversalAccessFromFileURLs(allow);
    }

    @ReactProp(name = "saveFormDataDisabled")
    public void setSaveFormDataDisabled(WebView view, boolean disable) {
        view.getSettings().setSaveFormData(!disable);
    }

    @ReactProp(name = "injectedJavaScript")
    public void setInjectedJavaScript(WebView view, @NonNull String injectedJavaScript) {
        ((Web3Webview) view).setInjectedJavaScript(injectedJavaScript);
    }

    @ReactProp(name = "injectedOnStartLoadingJavaScript")
    public void setInjectedOnStartLoadingJavaScript(WebView view, @NonNull String injectedJavaScript) {
        ((Web3Webview) view).setInjectedOnStartLoadingJavaScript(injectedJavaScript);
    }

    @ReactProp(name = "messagingEnabled")
    public void setMessagingEnabled(WebView view, boolean enabled) {
        ((Web3Webview) view).setMessagingEnabled(enabled);
    }

    @ReactProp(name = "source")
    public void setSource(WebView view, @NonNull ReadableMap source) {
        if (source != null) {
            if (source.hasKey("html")) {
                String html = source.getString("html");
                if (source.hasKey("baseUrl")) {
                    view.loadDataWithBaseURL(
                            source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
                } else {
                    view.loadData(html, HTML_MIME_TYPE, HTML_ENCODING);
                }
                return;
            }
            if (source.hasKey("uri")) {
                String url = source.getString("uri");
                String previousUrl = view.getUrl();
                if (source.hasKey("method")) {
                    String method = source.getString("method");
                    if (method.equals(HTTP_METHOD_POST)) {
                        byte[] postData = null;
                        if (source.hasKey("body")) {
                            String body = source.getString("body");
                            try {
                                postData = body.getBytes("UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                postData = body.getBytes();
                            }
                        }
                        if (postData == null) {
                            postData = new byte[0];
                        }
                        view.postUrl(url, postData);
                        return;
                    }
                }
                HashMap<String, String> headerMap = new HashMap<>();
                if (source.hasKey("headers")) {
                    ReadableMap headers = source.getMap("headers");
                    ReadableMapKeySetIterator iter = headers.keySetIterator();
                    while (iter.hasNextKey()) {
                        String key = iter.nextKey();
                        if ("user-agent".equals(key.toLowerCase(Locale.ENGLISH))) {
                            if (view.getSettings() != null) {
                                view.getSettings().setUserAgentString(headers.getString(key));
                            }
                        } else {
                            headerMap.put(key, headers.getString(key));
                        }
                    }
                }
                view.loadUrl(url, headerMap);
                return;
            }
        }
        view.loadUrl(BLANK_URL);
    }

    @ReactProp(name = "onContentSizeChange")
    public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
        if (sendContentSizeChangeEvents) {
            view.setPictureListener(getPictureListener());
        } else {
            view.setPictureListener(null);
        }
    }

    @ReactProp(name = "mixedContentMode")
    public void setMixedContentMode(WebView view, @NonNull String mixedContentMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mixedContentMode == null || "never".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            } else if ("always".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            } else if ("compatibility".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
        }
    }

    @ReactProp(name = "urlPrefixesForDefaultIntent")
    public void setUrlPrefixesForDefaultIntent(
            WebView view,
            @NonNull ReadableArray urlPrefixesForDefaultIntent) {
        Web3WebviewClient client = ((Web3Webview) view).getWeb3WebviewClient();
        if (client != null && urlPrefixesForDefaultIntent != null) {
            client.setUrlPrefixesForDefaultIntent(urlPrefixesForDefaultIntent);
        }
    }

    @ReactProp(name = "geolocationEnabled")
    public void setGeolocationEnabled(
            WebView view,
            @NonNull Boolean isGeolocationEnabled) {
        view.getSettings().setGeolocationEnabled(isGeolocationEnabled != null && isGeolocationEnabled);
    }

    @ReactProp(name = "originWhitelist")
    public void setOriginWhitelist(
            WebView view,
            @NonNull ReadableArray originWhitelist) {
        Web3WebviewClient client = ((Web3Webview) view).getWeb3WebviewClient();
        if (client != null && originWhitelist != null) {
            List<Pattern> whiteList = new LinkedList<>();
            for (int i = 0 ; i < originWhitelist.size() ; i++) {
                whiteList.add(Pattern.compile(originWhitelist.getString(i)));
            }
            client.setOriginWhitelist(whiteList);
        }
    }

    @Override
    protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
        view.setWebViewClient(new Web3WebviewClient());
    }

    @Override
    public @NonNull Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
                "goBack", COMMAND_GO_BACK,
                "goForward", COMMAND_GO_FORWARD,
                "reload", COMMAND_RELOAD,
                "stopLoading", COMMAND_STOP_LOADING,
                "postMessage", COMMAND_POST_MESSAGE,
                "injectJavaScript", COMMAND_INJECT_JAVASCRIPT
        );
    }

    @Override
    public void receiveCommand(WebView root, int commandId, @NonNull ReadableArray args) {
        switch (commandId) {
            case COMMAND_GO_BACK:
                root.goBack();
                break;
            case COMMAND_GO_FORWARD:
                root.goForward();
                break;
            case COMMAND_RELOAD:
                root.reload();
                break;
            case COMMAND_STOP_LOADING:
                root.stopLoading();
                break;
            case COMMAND_POST_MESSAGE:
                try {
                    Web3Webview webView = (Web3Webview) root;
                    JSONObject eventInitDict = new JSONObject();
                    eventInitDict.put("data", args.getString(0));
                    webView.evaluateJavascriptWithFallback("(function () {" +
                            "var event;" +
                            "var data = " + eventInitDict.toString() + ";" +
                            "try {" +
                            "event = new MessageEvent('message', data);" +
                            "} catch (e) {" +
                            "event = document.createEvent('MessageEvent');" +
                            "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
                            "}" +
                            "document.dispatchEvent(event);" +
                            "})();");
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                break;
            case COMMAND_INJECT_JAVASCRIPT:
                Web3Webview webView = (Web3Webview) root;
                webView.evaluateJavascriptWithFallback(args.getString(0));
                break;
        }
    }

    @Override
    public void onDropViewInstance(WebView webView) {
        super.onDropViewInstance(webView);
        Web3Webview w = (Web3Webview) webView;
        ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener(w);
        w.cleanupCallbacksAndDestroy();
    }

    public static Web3WebviewModule getModule(ReactContext reactContext) {
        return reactContext.getNativeModule(Web3WebviewModule.class);
    }

    protected WebView.PictureListener getPictureListener() {
        if (mPictureListener == null) {
            mPictureListener = new WebView.PictureListener() {
                @Override
                public void onNewPicture(WebView webView, Picture picture) {
                    dispatchEvent(
                            webView,
                            new ContentSizeChangeEvent(
                                    webView.getId(),
                                    webView.getWidth(),
                                    webView.getContentHeight()));
                }
            };
        }
        return mPictureListener;
    }

    protected static void dispatchEvent(WebView webView, Event event) {
        ReactContext reactContext = (ReactContext) webView.getContext();
        EventDispatcher eventDispatcher =
                reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        eventDispatcher.dispatchEvent(event);
    }

    @Override
    public @NonNull Map getExportedCustomBubblingEventTypeConstants() {
        return MapBuilder.builder()
                .put("progress",
                        MapBuilder.of(
                                "phasedRegistrationNames",
                                MapBuilder.of("bubbled", "onProgress")))
                .build();
    }


}
