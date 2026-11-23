package com.debtsapp.ledger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_CONTACT_PICK = 1002;
    private static final int REQ_SAVE_FILE = 1003;

    private byte[] pendingSaveBytes;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0D6A57);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false", val -> {
            if (!"true".equals(val)) {
                finish();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
        } else if (requestCode == REQ_CONTACT_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String name = "";
            String phone = "";
            try (Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                    phone = cursor.getString(1);
                }
            } catch (Exception e) {
                Toast.makeText(this, "تعذر قراءة جهة الاتصال", Toast.LENGTH_SHORT).show();
            }
            String js = "if(window.onNativeContactPicked){window.onNativeContactPicked("
                    + JSONObject.quote(name != null ? name : "") + ","
                    + JSONObject.quote(phone != null ? phone : "") + ");}";
            webView.evaluateJavascript(js, null);
        } else if (requestCode == REQ_SAVE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os != null) {
                    os.write(pendingSaveBytes);
                    Toast.makeText(this, "تم حفظ الملف بنجاح", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "خطأ في الحفظ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            pendingSaveBytes = null;
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public class AndroidBridge {

        @JavascriptInterface
        public void sendToWhatsApp(String phone, String base64Png, String caption, String preferredPkg) {
            runOnUiThread(() -> {
                try {
                    byte[] decoded = Base64.decode(base64Png, Base64.DEFAULT);
                    File cachePath = new File(getCacheDir(), "receipts");
                    if (!cachePath.exists()) cachePath.mkdirs();

                    File file = new File(cachePath, "receipt_" + System.currentTimeMillis() + ".png");
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(decoded);
                    }

                    Uri contentUri = FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            file
                    );

                    String cleanPhone = phone.replaceAll("[^\\d]", "");
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    intent.putExtra(Intent.EXTRA_TEXT, caption);
                    if (!cleanPhone.isEmpty()) {
                        intent.putExtra("jid", cleanPhone + "@s.whatsapp.net");
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    String targetPkg = null;
                    if ("com.whatsapp".equals(preferredPkg) && isPackageInstalled("com.whatsapp")) {
                        targetPkg = "com.whatsapp";
                    } else if ("com.whatsapp.w4b".equals(preferredPkg) && isPackageInstalled("com.whatsapp.w4b")) {
                        targetPkg = "com.whatsapp.w4b";
                    } else if (isPackageInstalled("com.whatsapp")) {
                        targetPkg = "com.whatsapp";
                    } else if (isPackageInstalled("com.whatsapp.w4b")) {
                        targetPkg = "com.whatsapp.w4b";
                    }

                    if (targetPkg != null) {
                        intent.setPackage(targetPkg);
                        startActivity(intent);
                    } else {
                        startActivity(Intent.createChooser(intent, "إرسال السند عبر"));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح واتساب: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void pickContact() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                    startActivityForResult(intent, REQ_CONTACT_PICK);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح جهات الاتصال", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String filename, String mimeType, String data, boolean isBase64) {
            runOnUiThread(() -> {
                try {
                    pendingSaveBytes = isBase64
                            ? Base64.decode(data, Base64.DEFAULT)
                            : data.getBytes(StandardCharsets.UTF_8);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, filename);
                    startActivityForResult(intent, REQ_SAVE_FILE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر الحفظ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
  }
