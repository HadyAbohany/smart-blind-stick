package eg.sbs;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SmsResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String summary = getResultCode() == Activity.RESULT_OK
            ? "SMS send accepted by the phone. Delivery unconfirmed."
            : "SMS send failed (code " + getResultCode() + "). Contact them another way.";
        context.getSharedPreferences("sbs", 0).edit().putString("smsResult", summary).apply();
    }
}
