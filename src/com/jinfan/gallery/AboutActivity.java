package com.jinfan.gallery;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

/** 关于页：介绍 + 长按图标看彩蛋。 */
public class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_about);

        TextView ver = findViewById(R.id.tv_about_version);
        try {
            ver.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) { }

        ImageView icon = findViewById(R.id.iv_app_icon);
        icon.setOnLongClickListener(v -> {
            startActivity(new Intent(this, EggActivity.class));
            return true;
        });
    }
}
