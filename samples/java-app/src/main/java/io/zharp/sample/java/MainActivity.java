package io.zharp.sample.java;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import io.zharp.logs.ZharpLogger;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        // Clear of the status and title bars, which newer Android draws over the content.
        layout.setFitsSystemWindows(true);
        layout.setPadding(48, 48, 48, 48);

        TextView status = new TextView(this);
        status.setText(ZharpLogger.isActive() ? "Logging to Zharp" : "Not logging — set zharp.apiKey in local.properties");
        layout.addView(status);

        layout.addView(button("Send info", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, Object> metadata = new HashMap<String, Object>();
                metadata.put("cartItems", 3);
                metadata.put("currency", "GHS");
                ZharpLogger.info("Checkout opened", metadata);
            }
        }));
        layout.addView(button("Send warning", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, Object> metadata = new HashMap<String, Object>();
                metadata.put("latencyMs", 3200);
                ZharpLogger.warn("Slow network detected", metadata);
            }
        }));
        layout.addView(button("Send caught error", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    throw new IllegalStateException("Card declined");
                } catch (IllegalStateException e) {
                    ZharpLogger.error("Payment failed", e);
                }
            }
        }));
        layout.addView(button("Crash the app", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                throw new IllegalStateException("Sample crash from Java");
            }
        }));
        setContentView(layout);
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(onClick);
        return button;
    }
}
