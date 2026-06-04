package li.vinkent.seniorspendingguard;

import android.app.Application;
import android.content.Context;

public class SeniorSpendingGuardApp extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    static Context context() {
        return appContext;
    }
}
