package io.bbui.toolfixture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.TextWatcher;
import android.text.Editable;
import org.json.JSONObject;

/** Disposable target for package/permission/notification tests; contains no user data. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("BBUI 工具测试\n仅用于本地系统工具验收");
        text.setTextSize(24);
        text.setPadding(24, 80, 24, 24);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(text);
        EditText editor = new EditText(this);
        editor.setHint("输入测试内容");
        editor.setMinHeight(180);
        layout.addView(editor);
        setContentView(layout);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try (java.io.FileOutputStream output = openFileOutput("input.txt", MODE_PRIVATE)) {
                    output.write(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception error) { throw new RuntimeException(error); }
            }
            public void afterTextChanged(Editable text) {}
        });
        editor.post(() -> {
            int[] at = new int[2]; editor.getLocationOnScreen(at);
            try (java.io.FileOutputStream output = openFileOutput("geometry.json", MODE_PRIVATE)) {
                output.write(new JSONObject().put("x", at[0] + editor.getWidth() / 2).put("y", at[1] + editor.getHeight() / 2)
                    .put("displayId", getDisplay().getDisplayId()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        getPreferences(MODE_PRIVATE).edit().putBoolean("started", true).apply();
        if (getIntent().getBooleanExtra("postNotification", false)) {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("fixture", "工具测试", NotificationManager.IMPORTANCE_DEFAULT));
            notifications.notify("bbui-tool-fixture", 1, new Notification.Builder(this, "fixture")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("BBUI notification fixture")
                .setContentText("Synthetic test content only").build());
        }
    }
}
