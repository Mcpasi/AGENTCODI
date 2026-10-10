package de.agentcodi.app;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Observes gestures even when selectable transcript text handles the touch. */
final class TranscriptScrollView extends ScrollView {
    interface GestureListener {
        void onGesture(MotionEvent event);
    }

    private GestureListener gestureListener;
    private boolean layingOut;

    TranscriptScrollView(Context context) {
        super(context);
    }

    void setGestureListener(GestureListener listener) {
        gestureListener = listener;
    }

    boolean isLayingOut() {
        return layingOut;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestureListener != null) {
            gestureListener.onGesture(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layingOut = true;
        try {
            super.onLayout(changed, left, top, right, bottom);
        } finally {
            layingOut = false;
        }
    }
}
