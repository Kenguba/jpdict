package com.yuen.jpdict;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Permission-free system image picker that retains only a small private copy. */
public final class BubbleImagePickerActivity extends Activity {
    public static final int REQUEST_PICK_IMAGE = 41;
    public static final String EXTRA_IMAGE_NAME = "bubble_image_name";
    private static final int REQUEST_IMAGE = 1;
    private static final String STATE_IMAGE_URI = "image_uri";
    private Uri selectedImage;
    private boolean returning;

    public static void launch(Activity context) {
        try {
            Intent picker = new Intent(context, BubbleImagePickerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            context.startActivityForResult(picker, REQUEST_PICK_IMAGE);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        if (state != null) {
            String savedUri = state.getString(STATE_IMAGE_URI);
            if (savedUri != null) {
                selectedImage = Uri.parse(savedUri);
                importSelectedImage();
            }
            // The system restores the outstanding document-picker result.
            return;
        }
        Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(document, REQUEST_IMAGE);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "此设备没有可用的图片选择器", Toast.LENGTH_LONG).show();
            returnToAppearancePage();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (selectedImage != null) state.putString(STATE_IMAGE_URI, selectedImage.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            returnToAppearancePage();
            return;
        }
        selectedImage = data.getData();
        importSelectedImage();
    }

    private void importSelectedImage() {
        Context context = getApplicationContext();
        Uri uri = selectedImage;
        new Thread(() -> {
            String imageName = null;
            try {
                imageName = importImage(context, uri);
            } catch (IOException | RuntimeException | OutOfMemoryError exception) {
                // A cancelled permission, invalid image or failed write leaves the
                // previous appearance usable; no provider URI is persisted.
            }
            final String imported = imageName;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (imported != null) new File(context.getFilesDir(), imported).delete();
                    return;
                }
                if (imported != null) setResult(RESULT_OK,
                        new Intent().putExtra(EXTRA_IMAGE_NAME, imported));
                Toast.makeText(this, imported != null ? "图片已预览，保存设置后采用"
                        : "图片无法读取或保存，请选择另一张图片", Toast.LENGTH_LONG).show();
                returnToAppearancePage();
            });
        }, "BubbleImageImport").start();
    }

    private static String importImage(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Missing image stream");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Missing image stream");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("Image could not be decoded");
        File temporary = null;
        try {
            Bitmap oriented = orientImage(context, uri, bitmap);
            if (oriented != bitmap) {
                bitmap.recycle();
                bitmap = oriented;
            }
            int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (max > 256) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * 256f / max)),
                        Math.max(1, Math.round(bitmap.getHeight() * 256f / max)), true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }
            // Cleanup only considers published image names; never expose a partial copy.
            temporary = File.createTempFile("bubble-import-", ".tmp", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Image could not be saved");
                }
                output.flush();
                output.getFD().sync();
            }
            File published = new File(context.getFilesDir(),
                    "floating-bubble-image-" + java.util.UUID.randomUUID() + ".png");
            if (!temporary.renameTo(published)) throw new IOException("Image could not be published");
            temporary = null; // Each immutable copy belongs to its draft or saved style.
            return published.getName();
        } finally {
            bitmap.recycle();
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private static Bitmap orientImage(Context context, Uri uri, Bitmap bitmap) {
        if (Build.VERSION.SDK_INT < 24) return bitmap;
        int orientation;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return bitmap;
            orientation = new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException exception) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void returnToAppearancePage() {
        if (returning) return;
        returning = true;
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() { returnToAppearancePage(); }
}
