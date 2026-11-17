package eg.sbs.benchmark;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class DetectionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF area = new RectF();
    private YoloEngine.Prediction prediction;
    private AlertPolicy.Selection selected;

    DetectionView(Context context) {
        super(context);
        setBackgroundColor(0xff080d13);
        paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                13, getResources().getDisplayMetrics()));
        paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        setContentDescription("Detection preview");
    }

    void show(YoloEngine.Prediction value) {
        if (prediction != null && prediction != value) prediction.image().recycle();
        prediction = value;
        selected = null;
        invalidate();
    }

    void select(AlertPolicy.Selection value) {
        selected = value;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        show(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (prediction == null) return;
        var image = prediction.image();
        float scale = Math.min((float) getWidth() / image.getWidth(), (float) getHeight() / image.getHeight());
        float width = image.getWidth() * scale, height = image.getHeight() * scale;
        float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
        area.set(left, top, left + width, top + height);
        canvas.drawBitmap(image, null, area, paint);
        canvas.save();
        canvas.clipRect(area);
        for (var box : prediction.boxes()) {
            float x1 = left + box.x1() * width, y1 = top + box.y1() * height;
            float x2 = left + box.x2() * width, y2 = top + box.y2() * height;
            paint.setColor(selected != null && selected.classId() == box.classId() && selected.score() == box.score()
                    ? 0xffffd166 : 0xff75e2c2);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(x1, y1, x2, y2, paint);
            paint.setStyle(Paint.Style.FILL);
            String label = box.label() + " " + Math.round(box.score() * 100) + "%";
            float textHeight = -paint.ascent() + paint.descent() + 4;
            float textWidth = paint.measureText(label) + 8;
            float labelX = Math.max(left, Math.min(x1, left + width - textWidth));
            float labelY = y1 - textHeight < top ? y1 : y1 - textHeight;
            canvas.drawRect(labelX, labelY, labelX + textWidth, labelY + textHeight, paint);
            paint.setColor(Color.BLACK);
            canvas.drawText(label, labelX + 4, labelY - paint.ascent() + 2, paint);
        }
        canvas.restore();
    }
}
