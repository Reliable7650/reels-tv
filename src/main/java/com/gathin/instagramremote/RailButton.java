package com.gathin.instagramremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.Button;

final class RailButton extends Button {
    private static final int[] ICONS = {R.drawable.rail_previous, R.drawable.rail_next,
            R.drawable.rail_like, R.drawable.rail_save, R.drawable.rail_rotate,
            R.drawable.rail_not_interested, R.drawable.rail_zoom};
    private final int index;
    private final Drawable icon;
    private final Drawable alternative;

    RailButton(Context context, int index) {
        super(context);
        this.index = index;
        icon = context.getDrawable(ICONS[index]).mutate();
        alternative = index == 2 ? context.getDrawable(R.drawable.rail_liked).mutate()
                : index == 6 ? context.getDrawable(R.drawable.rail_fit).mutate() : null;
        setContentDescription(context.getResources().getStringArray(R.array.rail_descriptions)[index]);
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean liked = index == 2 && getCurrentTextColor() == 0xFF81F0A5;
        boolean original = index == 6 && getText().toString().contains("原始比例");
        Drawable current = liked || original ? alternative : icon;
        if (index != 3) current.setTint(liked ? 0xFFFF6681 : 0xFFF7F9FC);
        int size = Math.round(32 * getResources().getDisplayMetrics().density);
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;
        current.setBounds(left, top, left + size, top + size);
        current.draw(canvas);
    }
}
