package com.jinfan.gallery;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;

/** 彩蛋页：长按关于页图标后展示的图，点一下或返回退出。 */
public class EggActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_egg);
        findViewById(R.id.iv_egg).setOnClickListener(v -> finish());
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) { finish(); return true; }
        return super.onTouchEvent(e);
    }
}
