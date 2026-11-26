package com.dosecompanion.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

/** Server pairing only; the provider key belongs exclusively on the backend. */
public final class CloudConnectionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,48,24,24); root.setFitsSystemWindows(true); setContentView(root);
        TextView title = new TextView(this); title.setText("Cloud connection"); title.setTextSize(25); root.addView(title);
        TextView info = new TextView(this); info.setText("For independent phone use, enter the hosted HTTPS address and your pilot pairing token. Your OpenAI API key stays on the server. Set this up before capturing photos; leaving the camera clears them. Reminders and history remain available offline."); root.addView(info);
        var settings = getSharedPreferences("cloud_bridge", MODE_PRIVATE);
        EditText address = new EditText(this); address.setHint("HTTPS server address or localhost USB bridge"); address.setSingleLine(true);
        address.setText(settings.getString("endpoint", "http://127.0.0.1:8787")); root.addView(address);
        EditText token = new EditText(this); token.setHint("Server pairing token (not your OpenAI key)"); token.setSaveEnabled(false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setText(settings.getString("token", "")); root.addView(token);
        TextView error = new TextView(this); root.addView(error);
        Button save = new Button(this); save.setText("Save connection"); root.addView(save);
        save.setOnClickListener(v -> {
            try {
                CloudReader.validateEndpoint(address.getText().toString().trim());
                String secret = token.getText().toString().trim();
                if (secret.length() < 32 || secret.contains("\n") || secret.startsWith("sk-")) throw new java.io.IOException("Use the server pairing token, not an OpenAI API key.");
                settings.edit().putString("endpoint", address.getText().toString().trim()).putString("token", secret).apply(); finish();
            } catch (java.io.IOException invalid) { error.setText(invalid.getMessage()); }
        });
        Button cancel = new Button(this); cancel.setText("Return"); cancel.setOnClickListener(v -> finish()); root.addView(cancel);
    }
}
