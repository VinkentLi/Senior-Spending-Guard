package com.example.seniorpaymentguard;

import android.app.Application;
import android.content.Context;

public class SeniorPaymentGuardApp extends Application {
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
