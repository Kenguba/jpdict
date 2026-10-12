package com.yuen.jpdict;

import android.graphics.*;

/** Bounded text crop, optional grayscale contrast/polarity, and exact inverse geometry. */
final class OcrImagePreparation implements AutoCloseable {
    static final int MAX_PIXELS = 1024 * 1024;
    final Bitmap image;
    final Rect crop;
    private final float scaleX, scaleY;
    private OcrImagePreparation(Bitmap image, Rect crop) {
        this.image = image; this.crop = new Rect(crop);
        scaleX = (float)image.getWidth()/crop.width(); scaleY = (float)image.getHeight()/crop.height();
    }
    static OcrImagePreparation prepare(Bitmap screen, Rect target, boolean enhance) {
        return prepare(screen, target, enhance, target.height());
    }
    static OcrImagePreparation prepare(Bitmap screen, Rect target, boolean enhance, int textHeight) {
        Rect crop = new Rect(target);
        int padding = Math.max(4, target.height()/4);
        crop.inset(-padding, -padding);
        if (!crop.intersect(0, 0, screen.getWidth(), screen.getHeight())) throw new IllegalArgumentException("OCR 选区不在截图内");
        float scale = Math.min(3f, Math.max(1f, 32f/Math.max(1, textHeight)));
        scale = Math.min(scale, (float)Math.sqrt((double)MAX_PIXELS/crop.width()/crop.height()));
        int width = Math.max(1, (int)(crop.width()*scale)), height = Math.max(1, (int)(crop.height()*scale));
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (enhance) paint.setColorFilter(contrast(screen, crop));
        new Canvas(image).drawBitmap(screen, crop, new Rect(0, 0, width, height), paint);
        return new OcrImagePreparation(image, crop);
    }
    private static ColorMatrixColorFilter contrast(Bitmap screen, Rect crop) {
        int[] histogram = new int[256], border = new int[256];
        int step = Math.max(1, (int)Math.sqrt((double)crop.width()*crop.height()/65536));
        int count = 0;
        for (int y = crop.top; y < crop.bottom; y += step) for (int x = crop.left; x < crop.right; x += step) {
            int color = screen.getPixel(x, y);
            int value = (77*Color.red(color) + 150*Color.green(color) + 29*Color.blue(color)) >> 8;
            histogram[value]++; count++;
            if (y < crop.top+step || y >= crop.bottom-step || x < crop.left+step || x >= crop.right-step) border[value]++;
        }
        int low = percentile(histogram, Math.max(1, count/100));
        int high = percentile(histogram, Math.max(1, count-count/100));
        int background = 0;
        for (int i = 1; i < 256; i++) if (border[i] > border[background]) background = i;
        float gain = Math.min(3f, 255f/Math.max(16, high-low));
        float offset = -low*gain;
        if (background < 128) { gain = -gain; offset = 255-offset; }
        ColorMatrix gray = new ColorMatrix(); gray.setSaturation(0);
        ColorMatrix levels = new ColorMatrix(new float[]{gain,0,0,0,offset, 0,gain,0,0,offset, 0,0,gain,0,offset, 0,0,0,1,0});
        levels.postConcat(gray);
        return new ColorMatrixColorFilter(levels);
    }
    private static int percentile(int[] counts, int target) {
        int sum = 0;
        for (int i = 0; i < counts.length; i++) { sum += counts[i]; if (sum >= target) return i; }
        return 255;
    }
    Rect toScreen(Rect box) {
        return new Rect(Math.max(crop.left, crop.left+(int)Math.floor(box.left/scaleX)),
                Math.max(crop.top, crop.top+(int)Math.floor(box.top/scaleY)),
                Math.min(crop.right, crop.left+(int)Math.ceil(box.right/scaleX)),
                Math.min(crop.bottom, crop.top+(int)Math.ceil(box.bottom/scaleY)));
    }
    @Override public void close() { image.recycle(); }
}
