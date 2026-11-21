package pl.kuba.radiocentrum.radio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Rysunek auta (van z przodu). Gdy światła są zapalone – świecą reflektory. */
final class CarView extends View {

    private boolean lights;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    CarView(Context c) {
        super(c);
    }

    void setLights(boolean on) {
        if (on != lights) {
            lights = on;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w / 320f, h / 220f);
        c.save();
        c.translate(w / 2f, h / 2f);
        c.scale(s, s);

        // cień pod autem
        p.setShader(null);
        p.setColor(0x55000000);
        c.drawOval(new RectF(-150, 62, 150, 88), p);

        // poświata reflektorów
        if (lights) {
            for (int side = -1; side <= 1; side += 2) {
                float cx = side * 82, cy = 8;
                p.setShader(new RadialGradient(cx, cy, 95, 0xAAFFE57F, 0x00FFE57F, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, 95, p);
            }
            p.setShader(null);
        }

        // koła
        p.setColor(0xFF202020);
        c.drawRoundRect(new RectF(-128, 40, -88, 80), 8, 8, p);
        c.drawRoundRect(new RectF(88, 40, 128, 80), 8, 8, p);

        // kabina (wysoki van)
        Path cab = new Path();
        cab.moveTo(-92, -92);
        cab.lineTo(92, -92);
        cab.lineTo(122, -18);
        cab.lineTo(-122, -18);
        cab.close();
        p.setColor(0xFF90A4AE);
        c.drawPath(cab, p);
        // szyba
        Path glass = new Path();
        glass.moveTo(-82, -84);
        glass.lineTo(82, -84);
        glass.lineTo(106, -26);
        glass.lineTo(-106, -26);
        glass.close();
        p.setColor(0xFF4FC3F7);
        p.setAlpha(200);
        c.drawPath(glass, p);
        p.setAlpha(255);
        // lusterka
        p.setColor(0xFF78909C);
        c.drawRoundRect(new RectF(-146, -34, -120, -16), 5, 5, p);
        c.drawRoundRect(new RectF(120, -34, 146, -16), 5, 5, p);

        // nadwozie
        p.setColor(0xFFB0BEC5);
        c.drawRoundRect(new RectF(-140, -20, 140, 56), 26, 26, p);
        // zderzak
        p.setColor(0xFF546E7A);
        c.drawRoundRect(new RectF(-132, 40, 132, 60), 10, 10, p);
        // atrapa
        p.setColor(0xFF263238);
        c.drawRoundRect(new RectF(-46, -2, 46, 22), 8, 8, p);
        p.setColor(0xFF455A64);
        for (int i = 0; i < 3; i++) c.drawRect(-40, 2 + i * 7, 40, 4 + i * 7, p);

        // reflektory
        p.setColor(lights ? 0xFFFFF59D : 0xFF607D8B);
        c.drawRoundRect(new RectF(-124, -6, -58, 20), 10, 10, p);
        c.drawRoundRect(new RectF(58, -6, 124, 20), 10, 10, p);
        if (lights) {
            p.setColor(0xFFFFFFFF);
            c.drawCircle(-104, 7, 6, p);
            c.drawCircle(104, 7, 6, p);
        }
        // tablica
        p.setColor(0xFFECEFF1);
        c.drawRoundRect(new RectF(-34, 42, 34, 56), 3, 3, p);

        c.restore();
    }
}
