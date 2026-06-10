package com.thl.book;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.thl.book.base.BaseActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class DonateActivity extends BaseActivity {

    @Override
    protected int initLayout() {
        return R.layout.activity_donate;
    }

    @Override
    protected void initView() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save_qr).setOnClickListener(v -> saveQrCode());
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}

    private void saveQrCode() {
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.payme);
        if (bitmap == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：通过 MediaStore 写入，无需存储权限
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "tomato_reader_donate.jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("MediaStore insert 失败");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
                }
            } else {
                // Android 9 及以下
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File file = new File(dir, "tomato_reader_donate.jpg");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                }
                // 通知系统相册刷新
                sendBroadcast(new android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(file)));
            }
            Toast.makeText(this, "二维码已保存到相册", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
