package li.vinkent.seniorspendingguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    static final String PREFS = "spending_guard";
    static final String PREF_PIN = "caregiver_pin";
    static final String DEFAULT_PIN = "1234";
    static final String PREF_CARD_NAME = "card_name";
    static final String PREF_CARD_NUMBER = "card_number";
    static final String PREF_CARD_EXPIRATION = "card_expiration";
    static final String PREF_CARD_CVV = "card_cvv";
    static final String PREF_CARD_POSTAL = "card_postal";

    private static final String PREF_LANGUAGE = "language";
    private static final String PREF_MODE = "mode";
    private static final String PREF_SETUP_STEP = "setup_step";
    private static final String PREF_UNLOCKED_UNTIL = "caregiver_unlocked_until";
    private static final String PREF_PIN_FAILS = "pin_fails";
    private static final String PREF_PIN_LOCKED_UNTIL = "pin_locked_until";

    private static final long UNLOCK_WINDOW_MS = 5 * 60 * 1000;
    private static final long PIN_LOCK_MS = 5 * 60 * 1000;
    private static final int MAX_PIN_FAILS = 5;
    private static final int REQUEST_AUTOFILL = 42;
    private static final String AUTOFILL_SERVICE_SETTING = "autofill_service";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main";
    private static final String CHROME_TEST_URL = "https://vinkent.li/test-chrome-autofill/";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String[] LANGUAGE_CODES = {"en", "es", "zh", "zh-TW", "ko", "vi", "tl", "fr", "pt", "hi", "ar"};
    private static final String[] LANGUAGE_LABELS = {
            "English",
            "Espanol",
            "中文",
            "繁體中文",
            "한국어",
            "Tiếng Việt",
            "Tagalog",
            "Français",
            "Português",
            "हिन्दी",
            "العربية"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::render;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    private void render() {
        handler.removeCallbacks(refreshRunnable);
        setContentView(buildContent());
        scheduleRefresh();
    }

    private View buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(32));
        root.setBackgroundColor(Color.rgb(248, 250, 252));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText(t("app_title"));
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(15, 23, 42));
        root.addView(title, matchWrap());

        LinearLayout topActions = horizontalGroup();
        Button seniorMode = secondaryButton(t("senior_mode"));
        seniorMode.setOnClickListener(v -> setMode("senior"));
        topActions.addView(seniorMode, weightedButton());
        Button caregiverMode = secondaryButton(t("caregiver_mode"));
        caregiverMode.setOnClickListener(v -> setMode("caregiver"));
        topActions.addView(caregiverMode, weightedButton());
        root.addView(topActions, matchWrap());

        addLanguageSelector(root);

        if ("caregiver".equals(prefs().getString(PREF_MODE, "senior"))) {
            addCaregiverView(root);
        } else {
            addSeniorView(root);
        }
        return scrollView;
    }

    private void addSeniorView(LinearLayout root) {
        addSectionTitle(root, t("senior_status"));
        boolean provider = isAutofillServiceEnabled();
        boolean card = hasCardProfile();
        boolean protectedNow = provider && card;
        root.addView(statusCard(
                protectedNow ? t("protected_title") : t("needs_setup_title"),
                protectedNow ? t("protected_body") : t("needs_setup_body"),
                protectedNow ? Color.rgb(22, 101, 52) : Color.rgb(185, 28, 28)
        ), matchWrap());

        LinearLayout facts = rowContainer();
        facts.addView(fact(t("autofill"), provider ? t("on") : t("off")), matchWrap());
        facts.addView(fact(t("card"), card ? GuardStore.cardSummary(this) : t("no_card")), matchWrap());
        root.addView(facts, matchWrap());

        root.addView(statusCard(t("buying_rule"), t("buying_rule_body"), Color.rgb(30, 64, 175)), matchWrap());
        root.addView(noticeCard(t("scam_limit_title"), t("scam_limit_body"), Color.rgb(146, 64, 14)), matchWrap());
    }

    private void addCaregiverView(LinearLayout root) {
        addSectionTitle(root, t("caregiver"));
        if (!isSetupUnlocked()) {
            root.addView(statusCard(t("locked"), t("locked_body"), Color.rgb(185, 28, 28)), matchWrap());
            if (isDefaultPin()) {
                root.addView(noticeCard(t("default_pin_title"), t("default_pin_body"), Color.rgb(30, 64, 175)), matchWrap());
            }
            EditText pin = field(t("caregiver_pin"), hiddenKeyboardInputType());
            root.addView(pin, matchWrap());
            Button unlock = primaryButton(t("unlock"));
            unlock.setOnClickListener(v -> unlockCaregiver(pin.getText().toString()));
            root.addView(unlock, matchWrap());
            root.addView(body(t("forgot_pin_body")), matchWrap());
            return;
        }

        root.addView(statusCard(t("unlocked"), t("unlocked_body"), Color.rgb(22, 101, 52)), matchWrap());
        addSetupWizard(root);
    }

    private void addSetupWizard(LinearLayout root) {
        int step = prefs().getInt(PREF_SETUP_STEP, suggestedStep());
        step = Math.max(0, Math.min(5, step));
        addSectionTitle(root, t("setup_step") + " " + (step + 1) + " / 6");

        if (step == 0) {
            addPinStep(root);
        } else if (step == 1) {
            addAutofillStep(root);
        } else if (step == 2) {
            addChromeAutofillStep(root);
        } else if (step == 3) {
            addCardStep(root);
        } else if (step == 4) {
            addStoreStep(root);
        } else {
            addFinishStep(root);
        }

        final int currentStep = step;
        LinearLayout nav = horizontalGroup();
        Button back = secondaryButton(t("back"));
        back.setEnabled(currentStep > 0);
        back.setOnClickListener(v -> setStep(currentStep - 1));
        nav.addView(back, weightedButton());
        Button next = primaryButton(currentStep == 5 ? t("done") : t("next"));
        next.setOnClickListener(v -> setStep(Math.min(5, currentStep + 1)));
        nav.addView(next, weightedButton());
        root.addView(nav, matchWrap());
    }

    private void addPinStep(LinearLayout root) {
        root.addView(statusCard(t("pin_step"), t("pin_step_body"), Color.rgb(30, 64, 175)), matchWrap());
        EditText oldPin = field(t("current_pin"), hiddenKeyboardInputType());
        EditText newPin = field(t("new_pin"), hiddenKeyboardInputType());
        EditText confirm = field(t("confirm_pin"), hiddenKeyboardInputType());
        root.addView(oldPin, matchWrap());
        root.addView(newPin, matchWrap());
        root.addView(confirm, matchWrap());
        Button save = primaryButton(t("save_pin"));
        save.setOnClickListener(v -> savePin(oldPin.getText().toString(), newPin.getText().toString(), confirm.getText().toString()));
        root.addView(save, matchWrap());
    }

    private void addAutofillStep(LinearLayout root) {
        boolean enabled = isAutofillServiceEnabled();
        root.addView(statusCard(
                enabled ? t("autofill_on") : t("autofill_step"),
                enabled ? t("autofill_on_body") : t("autofill_step_body"),
                enabled ? Color.rgb(22, 101, 52) : Color.rgb(30, 64, 175)
        ), matchWrap());
        Button choose = primaryButton(enabled ? t("open_autofill") : t("replace_autofill"));
        choose.setOnClickListener(v -> openAutofillChooser());
        root.addView(choose, matchWrap());
    }

    private void addChromeAutofillStep(LinearLayout root) {
        root.addView(noticeCard(t("chrome_autofill_step"), t("chrome_autofill_body"), Color.rgb(30, 64, 175)), matchWrap());

        Button settings = primaryButton(t("open_chrome_autofill_options"));
        settings.setOnClickListener(v -> openChromeSettingsWithSteps());
        root.addView(settings, matchWrap());

        root.addView(screenshotGuide(
                withoutStepNumber(t("chrome_options_guide_title")),
                t("chrome_options_guide_body"),
                chromeAutofillOptionsScreenshotRes()
        ), matchWrap());

        Button test = secondaryButton(t("open_chrome_test"));
        test.setOnClickListener(v -> openChromeTestPage());
        root.addView(test, matchWrap());
    }

    private void addCardStep(LinearLayout root) {
        root.addView(statusCard(t("card_step"), GuardStore.cardSummary(this), Color.rgb(30, 64, 175)), matchWrap());
        EditText name = field(t("name_on_card"), safeKeyboardInputType());
        EditText number = field(t("card_number"), safeKeyboardInputType());
        EditText expiration = field(t("expiration"), safeKeyboardInputType());
        EditText cvv = field(t("cvv"), hiddenKeyboardInputType());
        EditText postal = field(t("postal"), safeKeyboardInputType());
        name.setText(GuardStore.getCardName(this));
        number.setText(GuardStore.getCardNumber(this));
        expiration.setText(GuardStore.getCardExpiration(this));
        cvv.setText(GuardStore.getCardCvv(this));
        postal.setText(GuardStore.getCardPostal(this));
        number.setFilters(new InputFilter[]{digitsFilter(), new InputFilter.LengthFilter(19)});
        expiration.setFilters(new InputFilter[]{expirationFilter(), new InputFilter.LengthFilter(5)});
        cvv.setFilters(new InputFilter[]{digitsFilter(), new InputFilter.LengthFilter(4)});
        root.addView(name, matchWrap());
        root.addView(number, matchWrap());
        root.addView(expiration, matchWrap());
        root.addView(cvv, matchWrap());
        root.addView(postal, matchWrap());

        LinearLayout actions = horizontalGroup();
        Button save = primaryButton(t("save_card"));
        save.setOnClickListener(v -> saveCard(name, number, expiration, cvv, postal));
        actions.addView(save, weightedButton());
        Button clear = secondaryButton(t("clear_card"));
        clear.setOnClickListener(v -> {
            GuardStore.clearCard(this);
            toast(t("card_cleared"));
            render();
        });
        actions.addView(clear, weightedButton());
        root.addView(actions, matchWrap());
        root.addView(body(t("test_card_hint")), matchWrap());
    }

    private void addStoreStep(LinearLayout root) {
        root.addView(statusCard(t("store_step"), t("store_step_body"), Color.rgb(30, 64, 175)), matchWrap());
        Button chrome = primaryButton(t("chrome_button"));
        chrome.setOnClickListener(v -> openChromePaymentSettings());
        root.addView(chrome, matchWrap());
        Button play = primaryButton(t("play_button"));
        play.setOnClickListener(v -> openGooglePlayPurchaseSettings());
        root.addView(play, matchWrap());
        root.addView(body(t("merchant_apps_hint")), matchWrap());
        addMerchantAppGuide(root);
    }

    private void addMerchantAppGuide(LinearLayout root) {
        addSectionTitle(root, t("merchant_guide_title"));
        root.addView(noticeCard(t("merchant_guide_general_title"), t("merchant_guide_general_body"), Color.rgb(30, 64, 175)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_amazon_title"), t("merchant_guide_amazon_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_walmart_title"), t("merchant_guide_walmart_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_target_title"), t("merchant_guide_target_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_delivery_title"), t("merchant_guide_delivery_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_rides_title"), t("merchant_guide_rides_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_money_title"), t("merchant_guide_money_body"), Color.rgb(51, 65, 85)), matchWrap());
        root.addView(noticeCard(t("merchant_guide_stuck_title"), t("merchant_guide_stuck_body"), Color.rgb(185, 28, 28)), matchWrap());
    }

    private void addFinishStep(LinearLayout root) {
        root.addView(statusCard(t("finish_step"), t("finish_step_body"), Color.rgb(30, 64, 175)), matchWrap());
        root.addView(noticeCard(t("scam_limit_title"), t("scam_limit_body"), Color.rgb(146, 64, 14)), matchWrap());
        root.addView(statusCard(t("spending_history_title"), t("spending_history_body"), Color.rgb(30, 64, 175)), matchWrap());
    }

    private void addLanguageSelector(LinearLayout root) {
        addSectionTitle(root, t("language_section"));
        String current = prefs().getString(PREF_LANGUAGE, "en");
        boolean rtlSelector = "ar".equals(current);
        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setMinimumHeight(dp(56));
        selector.setPadding(dp(8), 0, rtlSelector ? 0 : dp(8), 0);
        selector.setClickable(true);
        selector.setFocusable(true);
        selector.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        selector.setTextDirection(rtlSelector ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        selector.setGravity(Gravity.CENTER_VERTICAL);

        TextView arrow = languageSelectorArrow(rtlSelector);
        TextView label = languageSelectorLabel(languageIndex(current), rtlSelector);
        label.setPadding(dp(16), 0, rtlSelector ? 0 : dp(16), 0);

        if (rtlSelector) {
            selector.addView(arrow, fixedWidth(dp(56)));
            selector.addView(label, weightWrap());
        } else {
            selector.addView(label, weightWrap());
            selector.addView(arrow, fixedWidth(dp(56)));
        }

        selector.setOnClickListener(v -> showLanguageDialog());
        root.addView(selector, matchWrap());
    }

    private TextView languageSelectorLabel(int position, boolean rtl) {
        TextView label = new TextView(this);
        label.setText(LANGUAGE_LABELS[position]);
        label.setTextSize(18);
        label.setTextColor(Color.rgb(17, 24, 39));
        label.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMinHeight(dp(56));
        label.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        label.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        label.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        return label;
    }

    private TextView languageSelectorArrow(boolean rtl) {
        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextSize(20);
        arrow.setTextColor(Color.rgb(75, 85, 99));
        arrow.setGravity(Gravity.CENTER);
        arrow.setMinHeight(dp(56));
        arrow.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
        return arrow;
    }

    private void showLanguageDialog() {
        new AlertDialog.Builder(this)
                .setTitle(t("language_section"))
                .setSingleChoiceItems(LANGUAGE_LABELS, languageIndex(prefs().getString(PREF_LANGUAGE, "en")), (dialog, which) -> {
                    dialog.dismiss();
                    String selected = LANGUAGE_CODES[which];
                    if (!selected.equals(prefs().getString(PREF_LANGUAGE, "en"))) {
                        setLanguage(selected);
                    }
                })
                .show();
    }

    private int languageIndex(String language) {
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(language)) {
                return i;
            }
        }
        return 0;
    }

    private int chromeAutofillOptionsScreenshotRes() {
        switch (prefs().getString(PREF_LANGUAGE, "en")) {
            case "es": return R.drawable.chrome_autofill_options_actual_es;
            case "zh": return R.drawable.chrome_autofill_options_actual_zh;
            case "zh-TW": return R.drawable.chrome_autofill_options_actual_zh_tw;
            case "ko": return R.drawable.chrome_autofill_options_actual_ko;
            case "vi": return R.drawable.chrome_autofill_options_actual_vi;
            case "tl": return R.drawable.chrome_autofill_options_actual_tl;
            case "fr": return R.drawable.chrome_autofill_options_actual_fr;
            case "pt": return R.drawable.chrome_autofill_options_actual_pt;
            case "hi": return R.drawable.chrome_autofill_options_actual_hi;
            case "ar": return R.drawable.chrome_autofill_options_actual_ar;
            default: return R.drawable.chrome_autofill_options_actual_en;
        }
    }

    private void unlockCaregiver(String pin) {
        if (!canTryPin(this)) {
            toast(t("pin_locked"));
            return;
        }
        if (!isCorrectPin(this, pin)) {
            recordPinFailure(this);
            toast(t("incorrect_pin"));
            render();
            return;
        }
        clearPinFailures(this);
        prefs().edit().putLong(PREF_UNLOCKED_UNTIL, System.currentTimeMillis() + UNLOCK_WINDOW_MS).apply();
        toast(t("unlocked_toast"));
        render();
    }

    private void savePin(String oldPin, String newPin, String confirm) {
        if (!isCorrectPin(this, oldPin)) {
            toast(t("current_pin_wrong"));
            return;
        }
        if (newPin == null || newPin.trim().length() < 4 || !TextUtils.isDigitsOnly(newPin.trim())) {
            toast(t("pin_rules"));
            return;
        }
        if (!newPin.trim().equals(confirm == null ? "" : confirm.trim())) {
            toast(t("pin_mismatch"));
            return;
        }
        prefs().edit().putString(PREF_PIN, newPin.trim()).apply();
        toast(t("pin_saved"));
    }

    private void saveCard(EditText nameInput, EditText numberInput, EditText expirationInput, EditText cvvInput, EditText postalInput) {
        String name = nameInput.getText().toString().trim();
        String number = digitsOnly(numberInput.getText().toString());
        String expiration = normalizeExpiration(expirationInput.getText().toString().trim());
        String cvv = digitsOnly(cvvInput.getText().toString());
        String postal = postalInput.getText().toString().trim();
        String error = validateCardProfile(name, number, expiration, cvv, postal);
        if (error != null) {
            toast(error);
            return;
        }
        GuardStore.saveCard(this, name, number, expiration, cvv, postal);
        toast(t("card_saved"));
        render();
    }

    static boolean hasSavedCard(Context context) {
        return GuardStore.hasSavedCard(context);
    }

    static boolean canTryPin(Context context) {
        long until = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(PREF_PIN_LOCKED_UNTIL, 0);
        return System.currentTimeMillis() >= until;
    }

    static boolean isCorrectPin(Context context, String pin) {
        String saved = context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PIN, DEFAULT_PIN);
        return saved.equals(pin == null ? "" : pin.trim());
    }

    static void recordPinFailure(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        int failures = prefs.getInt(PREF_PIN_FAILS, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt(PREF_PIN_FAILS, failures);
        if (failures >= MAX_PIN_FAILS) {
            editor.putLong(PREF_PIN_LOCKED_UNTIL, System.currentTimeMillis() + PIN_LOCK_MS)
                    .putInt(PREF_PIN_FAILS, 0);
        }
        editor.apply();
    }

    static void clearPinFailures(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(PREF_PIN_FAILS)
                .remove(PREF_PIN_LOCKED_UNTIL)
                .apply();
    }

    private boolean hasCardProfile() {
        return GuardStore.hasSavedCard(this);
    }

    private int suggestedStep() {
        if (!isAutofillServiceEnabled()) {
            return 1;
        }
        if (!hasCardProfile()) {
            return 2;
        }
        return 5;
    }

    private boolean isSetupUnlocked() {
        long until = prefs().getLong(PREF_UNLOCKED_UNTIL, 0);
        long now = System.currentTimeMillis();
        if (until <= now) {
            return false;
        }
        if (until - now > UNLOCK_WINDOW_MS) {
            prefs().edit().remove(PREF_UNLOCKED_UNTIL).apply();
            return false;
        }
        return true;
    }

    private void scheduleRefresh() {
        long setupUntil = prefs().getLong(PREF_UNLOCKED_UNTIL, 0);
        long now = System.currentTimeMillis();
        if (setupUntil > now) {
            handler.postDelayed(refreshRunnable, setupUntil - now + 250);
        }
    }

    private void setMode(String mode) {
        prefs().edit().putString(PREF_MODE, mode).apply();
        render();
    }

    private void setStep(int step) {
        prefs().edit().putInt(PREF_SETUP_STEP, step).apply();
        render();
    }

    private void setLanguage(String language) {
        prefs().edit().putString(PREF_LANGUAGE, language).apply();
        render();
    }

    private boolean isDefaultPin() {
        return DEFAULT_PIN.equals(prefs().getString(PREF_PIN, DEFAULT_PIN));
    }

    private boolean isSpanish() {
        return "es".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isChinese() {
        return "zh".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isTraditionalChinese() {
        return "zh-TW".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isKorean() {
        return "ko".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isVietnamese() {
        return "vi".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isTagalog() {
        return "tl".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isFrench() {
        return "fr".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isPortuguese() {
        return "pt".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isHindi() {
        return "hi".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private boolean isArabic() {
        return "ar".equals(prefs().getString(PREF_LANGUAGE, "en"));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isAutofillServiceEnabled() {
        String expected = new ComponentName(this, SeniorSpendingGuardAutofillService.class).flattenToString();
        String enabledService = Settings.Secure.getString(getContentResolver(), AUTOFILL_SERVICE_SETTING);
        return expected.equalsIgnoreCase(enabledService);
    }

    private void openAutofillChooser() {
        Intent request = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
        request.setData(Uri.parse("package:" + getPackageName()));
        if (startActivityForResultSafely(request, REQUEST_AUTOFILL)) {
            return;
        }
        toast(t("autofill_settings_fallback"));
        startActivitySafely(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openChromePaymentSettings() {
        Intent chromePayments = new Intent(Intent.ACTION_VIEW, Uri.parse("chrome://settings/payments"));
        chromePayments.setClassName(CHROME_PACKAGE, CHROME_MAIN_ACTIVITY);
        if (startActivitySafely(chromePayments)) {
            return;
        }
        openPackageOrAppInfo(CHROME_PACKAGE, t("chrome_fallback"));
    }

    private void openChromeSettingsWithSteps() {
        Intent preferences = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
        preferences.addCategory(Intent.CATEGORY_DEFAULT);
        preferences.addCategory(Intent.CATEGORY_APP_BROWSER);
        preferences.addCategory(Intent.CATEGORY_PREFERENCE);
        preferences.setPackage(CHROME_PACKAGE);
        if (startActivitySafely(preferences)) {
            toast(t("chrome_autofill_steps"));
            return;
        }
        Intent chrome = getPackageManager().getLaunchIntentForPackage(CHROME_PACKAGE);
        if (chrome != null && startActivitySafely(chrome)) {
            toast(t("chrome_autofill_steps"));
            return;
        }
        openPackageOrAppInfo(CHROME_PACKAGE, t("chrome_autofill_steps"));
    }

    private void openChromeTestPage() {
        Intent test = new Intent(Intent.ACTION_VIEW, Uri.parse(CHROME_TEST_URL));
        test.setClassName(CHROME_PACKAGE, CHROME_MAIN_ACTIVITY);
        if (!startActivitySafely(test)) {
            toast(CHROME_TEST_URL);
        }
    }

    private void openGooglePlayPurchaseSettings() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PLAY_STORE_PACKAGE);
        if (launch != null && startActivitySafely(launch)) {
            toast(t("play_steps"));
            return;
        }
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.android.vending"));
        if (startActivitySafely(market)) {
            toast(t("play_steps"));
            return;
        }
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"));
        startActivitySafely(web);
        toast(t("play_signin"));
    }

    private void openPackageOrAppInfo(String packageName, String message) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null && startActivitySafely(launch)) {
            toast(message);
            return;
        }
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        details.setData(Uri.parse("package:" + packageName));
        startActivitySafely(details);
    }

    private boolean startActivitySafely(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private boolean startActivityForResultSafely(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private String digitsOnly(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String validateCardProfile(String name, String number, String expiration, String cvv, String postal) {
        if (name.length() < 2) {
            return t("enter_name");
        }
        if (number.length() < 12 || number.length() > 19 || !passesLuhn(number)) {
            return t("enter_card_number");
        }
        if (!isValidExpiration(expiration)) {
            return t("enter_expiration");
        }
        if (cvv.length() < 3 || cvv.length() > 4) {
            return t("enter_cvv");
        }
        if (postal.length() < 3) {
            return t("enter_postal");
        }
        return null;
    }

    private String normalizeExpiration(String expiration) {
        String digits = digitsOnly(expiration);
        if (digits.length() == 4) {
            return digits.substring(0, 2) + "/" + digits.substring(2);
        }
        return expiration;
    }

    private boolean isValidExpiration(String expiration) {
        if (expiration == null || expiration.length() != 5 || expiration.charAt(2) != '/') {
            return false;
        }
        String monthText = expiration.substring(0, 2);
        String yearText = expiration.substring(3);
        if (!TextUtils.isDigitsOnly(monthText) || !TextUtils.isDigitsOnly(yearText)) {
            return false;
        }
        int month = Integer.parseInt(monthText);
        if (month < 1 || month > 12) {
            return false;
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int currentYear = calendar.get(java.util.Calendar.YEAR) % 100;
        int currentMonth = calendar.get(java.util.Calendar.MONTH) + 1;
        int year = Integer.parseInt(yearText);
        return year > currentYear || (year == currentYear && month >= currentMonth);
    }

    private boolean passesLuhn(String number) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private TextView statusCard(String title, String detail, int color) {
        TextView view = body(title + "\n" + detail);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(color);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(Color.WHITE);
        return view;
    }

    private LinearLayout noticeCard(String title, String detail, int color) {
        LinearLayout card = rowContainer();
        TextView titleView = label(title);
        titleView.setTextColor(color);
        card.addView(titleView, matchWrap());
        TextView detailView = body(detail);
        detailView.setTextSize(15);
        card.addView(detailView, matchWrap());
        return card;
    }

    private TextView fact(String title, String value) {
        TextView view = body(title + ": " + value);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout screenshotGuide(String title, String detail, int imageResId) {
        LinearLayout outer = rowContainer();
        outer.addView(label(title), matchWrap());
        outer.addView(body(detail), matchWrap());

        ImageView image = new ImageView(this);
        image.setImageResource(imageResId);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.rgb(241, 245, 249));
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        outer.addView(image, screenshotParams());
        return outer;
    }

    private void addSectionTitle(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase(Locale.US));
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(71, 85, 105));
        view.setPadding(0, dp(20), 0, dp(2));
        root.addView(view, matchWrap());
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.rgb(51, 65, 85));
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private TextView label(String text) {
        TextView view = body(text);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(15, 23, 42));
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextSize(18);
        field.setInputType(inputType);
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setShowSoftInputOnFocus(true);
        field.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_DOWN) {
                showKeyboard(field);
            }
            return false;
        });
        field.setOnClickListener(v -> showKeyboard(field));
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard(field);
            }
        });
        return field;
    }

    private int safeKeyboardInputType() {
        return InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    private int hiddenKeyboardInputType() {
        return InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    private InputFilter digitsFilter() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder accepted = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c >= '0' && c <= '9') {
                    accepted.append(c);
                }
            }
            return accepted.length() == end - start ? null : accepted.toString();
        };
    }

    private InputFilter expirationFilter() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder accepted = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if ((c >= '0' && c <= '9') || c == '/') {
                    accepted.append(c);
                }
            }
            return accepted.length() == end - start ? null : accepted.toString();
        };
    }

    private void showKeyboard(EditText field) {
        field.postDelayed(() -> {
            field.requestFocus();
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_FORCED);
            }
        }, 80);
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(Color.rgb(15, 23, 42));
        return button;
    }

    private LinearLayout horizontalGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        return group;
    }

    private LinearLayout rowContainer() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(Color.WHITE);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private LinearLayout.LayoutParams fixedWidth(int width) {
        return new LinearLayout.LayoutParams(
                width,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams screenshotParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
        );
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String withoutStepNumber(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceFirst("^\\s*[0-9]+\\.\\s*", "");
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String merchantGuideText(String key) {
        if (!key.startsWith("merchant_guide_")) {
            return null;
        }
        String language = prefs().getString(PREF_LANGUAGE, "en");
        switch (language) {
            case "fr":
                return merchantGuideValue(key,
                        "Retirer les cartes enregistrées des applis",
                        "Règle générale",
                        "Ouvrez chaque appli avec l'aidant présent. Allez dans Compte ou Profil, puis Wallet, Portefeuille ou Moyens de paiement. Retirez chaque carte enregistrée, puis vérifiez Abonnements, Adhésion, Paiement automatique, Pass ou Plus, car ces services peuvent garder un moyen de paiement par défaut séparé.",
                        "Amazon",
                        "Ouvrez Amazon > icône de profil ou menu > Votre compte > Vos paiements ou Wallet. Touchez chaque carte, puis Modifier ou Retirer du portefeuille. Vérifiez aussi Prime, Subscribe & Save, abonnements numériques et réglages 1-Click/adresse par défaut.",
                        "Walmart",
                        "Ouvrez Walmart > Account > icône d'engrenage Settings > Wallet. Retirez les cartes ou moyens de paiement bancaires enregistrés. Si une carte ne peut pas être retirée, vérifiez d'abord Walmart+, les abonnements, la pharmacie ou les commandes en attente.",
                        "Target",
                        "Ouvrez Target > My Target > Settings ou Profile > Payment cards ou Wallet. Retirez les cartes enregistrées. Vérifiez aussi Target Circle Card, le paiement par code-barres Wallet, les abonnements, registres et réglages de livraison le jour même.",
                        "DoorDash, Uber Eats, Instacart",
                        "Ouvrez Account ou Profile > Payment ou Payment methods. Touchez la carte, balayez vers la gauche ou utilisez Edit/Delete si disponible. Vérifiez aussi DashPass, Instacart+, adhésions, solde de carte-cadeau, PayPal, Venmo, Google Pay et moyens de paiement de secours.",
                        "Uber et Lyft",
                        "Ouvrez Account ou Menu > Wallet, Payment ou Payment methods. Sélectionnez chaque carte et choisissez Remove ou Delete. Si le retrait est bloqué, changez d'abord le paiement par défaut, le profil professionnel, Lyft Cash, Uber Cash, Uber One ou le compte famille/entreprise.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Ouvrez l'appli d'argent ou de portefeuille et vérifiez Moyens de paiement, Cartes, Banques, applis liées, paiements automatiques, abonnements ou marchands autorisés. Retirer une carte d'une appli d'achat ne retire pas toujours une autorisation PayPal, Venmo, Cash App ou Google Pay.",
                        "Si une appli refuse de retirer la carte",
                        "Cherchez une commande active, adhésion, abonnement, solde impayé, profil famille/entreprise ou moyen de paiement de secours requis. Annulez le service, changez son paiement par défaut vers une carte plus sûre à faible limite ou contactez le support officiel de l'appli. Vérifiez ensuite que les alertes sont activées dans l'appli bancaire ou carte.");
            case "pt":
                return merchantGuideValue(key,
                        "Remover cartões salvos dos apps",
                        "Regra geral",
                        "Abra cada app com o cuidador presente. Vá para Conta ou Perfil, depois Wallet/Carteira ou Métodos de pagamento. Remova todos os cartões salvos e confira Assinaturas, Associação, Pagamento automático, Pass ou Plus, pois eles podem manter um pagamento padrão separado.",
                        "Amazon",
                        "Abra Amazon > ícone de perfil ou menu > Sua conta > Seus pagamentos ou Wallet. Toque em cada cartão e escolha Editar ou Remover da carteira. Confira também Prime, Subscribe & Save, assinaturas digitais e configurações de 1-Click/endereço padrão.",
                        "Walmart",
                        "Abra Walmart > Account > engrenagem Settings > Wallet. Remova cartões ou pagamentos bancários salvos. Se um cartão não puder ser removido, confira primeiro Walmart+, assinaturas, farmácia ou pedidos pendentes.",
                        "Target",
                        "Abra Target > My Target > Settings ou Profile > Payment cards ou Wallet. Remova cartões salvos. Confira também Target Circle Card, pagamento por código de barras no Wallet, assinaturas, listas e entrega no mesmo dia.",
                        "DoorDash, Uber Eats, Instacart",
                        "Abra Account ou Profile > Payment ou Payment methods. Toque no cartão, deslize para a esquerda ou use Edit/Delete se aparecer. Confira também DashPass, Instacart+, assinaturas, saldo de vale-presente, PayPal, Venmo, Google Pay e pagamentos de backup.",
                        "Uber e Lyft",
                        "Abra Account ou Menu > Wallet, Payment ou Payment methods. Selecione cada cartão e escolha Remove ou Delete. Se a remoção for bloqueada, mude primeiro o pagamento padrão, perfil comercial, Lyft Cash, Uber Cash, Uber One ou conta família/empresa.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Abra o app de dinheiro ou carteira e revise Métodos de pagamento, Cartões, Bancos, apps vinculados, pagamentos automáticos, assinaturas ou comerciantes autorizados. Remover um cartão de um app de compras pode não remover uma autorização do PayPal, Venmo, Cash App ou Google Pay.",
                        "Se um app não deixar remover o cartão",
                        "Procure pedido ativo, assinatura, associação, saldo pendente, perfil família/empresa ou pagamento de backup obrigatório. Cancele o serviço, mude o padrão para um cartão mais seguro de baixo limite ou contate o suporte oficial do app. Depois confirme que os alertas estão ligados no app do banco/cartão.");
            case "hi":
                return merchantGuideValue(key,
                        "ऐप्स से सेव कार्ड हटाएँ",
                        "सामान्य नियम",
                        "हर ऐप को caregiver के साथ खोलें. Account या Profile में जाएँ, फिर Wallet या Payment methods खोलें. हर सेव कार्ड हटाएँ, फिर Subscriptions, Membership, Autopay, Pass या Plus plans भी देखें क्योंकि उनमें अलग default payment method रह सकता है.",
                        "Amazon",
                        "Amazon खोलें > profile icon या menu > Your Account > Your Payments या Wallet. हर कार्ड पर टैप करें, फिर Edit या Remove from wallet चुनें. Prime, Subscribe & Save, digital subscriptions और 1-Click/default address settings भी देखें.",
                        "Walmart",
                        "Walmart खोलें > Account > Settings gear > Wallet. सेव कार्ड या bank payment methods हटाएँ. अगर कार्ड नहीं हटता, तो पहले Walmart+, subscriptions, pharmacy या pending orders देखें.",
                        "Target",
                        "Target खोलें > My Target > Settings या Profile > Payment cards या Wallet. सेव कार्ड हटाएँ. Target Circle Card, Wallet barcode payment, subscriptions, registries और same-day delivery settings भी देखें.",
                        "DoorDash, Uber Eats, Instacart",
                        "Account या Profile > Payment या Payment methods खोलें. कार्ड पर टैप करें, left swipe करें, या Edit/Delete दिखे तो उसे चुनें. DashPass, Instacart+, memberships, gift card balance, PayPal, Venmo, Google Pay और backup payment methods भी देखें.",
                        "Uber और Lyft",
                        "Account या Menu > Wallet, Payment या Payment methods खोलें. हर कार्ड चुनें और Remove या Delete दबाएँ. अगर हटाना block हो, तो पहले default ride payment, business profile, Lyft Cash, Uber Cash, Uber One या family/business account बदलें.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Money या wallet app खोलें और Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions या Authorized merchants देखें. Shopping app से कार्ड हटाने से PayPal, Venmo, Cash App या Google Pay authorization हमेशा नहीं हटता.",
                        "अगर ऐप कार्ड हटाने नहीं देता",
                        "Active order, membership, subscription, unpaid balance, family/business profile या required backup payment method देखें. Service cancel करें, default payment को safer low-limit card पर बदलें, या ऐप के official support से संपर्क करें. फिर bank/card app में alerts on हैं यह verify करें.");
            case "ar":
                return merchantGuideValue(key,
                        "إزالة البطاقات المحفوظة من التطبيقات",
                        "قاعدة عامة",
                        "افتح كل تطبيق بوجود مقدم الرعاية. انتقل إلى الحساب أو الملف الشخصي، ثم Wallet أو Payment methods. احذف كل بطاقة محفوظة، ثم افحص الاشتراكات والعضويات والدفع التلقائي وPass أو Plus لأنها قد تحتفظ بطريقة دفع افتراضية منفصلة.",
                        "Amazon",
                        "افتح Amazon > أيقونة الملف الشخصي أو القائمة > Your Account > Your Payments أو Wallet. اضغط كل بطاقة ثم اختر Edit أو Remove from wallet. افحص أيضا Prime وSubscribe & Save والاشتراكات الرقمية وإعدادات 1-Click/العنوان الافتراضي.",
                        "Walmart",
                        "افتح Walmart > Account > ترس Settings > Wallet. احذف البطاقات أو طرق الدفع البنكية المحفوظة. إذا تعذر حذف بطاقة، افحص أولا Walmart+ أو الاشتراكات أو الصيدلية أو الطلبات المعلقة.",
                        "Target",
                        "افتح Target > My Target > Settings أو Profile > Payment cards أو Wallet. احذف البطاقات المحفوظة. افحص أيضا Target Circle Card والدفع بباركود Wallet والاشتراكات والسجلات وإعدادات التوصيل في نفس اليوم.",
                        "DoorDash, Uber Eats, Instacart",
                        "افتح Account أو Profile > Payment أو Payment methods. اضغط البطاقة، أو اسحب لليسار، أو استخدم Edit/Delete إن ظهر. افحص أيضا DashPass وInstacart+ والعضويات ورصيد بطاقات الهدايا وPayPal وVenmo وGoogle Pay وطرق الدفع الاحتياطية.",
                        "Uber و Lyft",
                        "افتح Account أو Menu > Wallet أو Payment أو Payment methods. اختر كل بطاقة ثم Remove أو Delete. إذا كان الحذف محظورا، غيّر أولا الدفع الافتراضي للرحلات أو ملف العمل أو Lyft Cash أو Uber Cash أو Uber One أو حساب العائلة/الشركة.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "افتح تطبيق المال أو المحفظة وراجع Payment methods وCards وBanks والتطبيقات المرتبطة والمدفوعات التلقائية والاشتراكات أو التجار المصرح لهم. حذف بطاقة من تطبيق تسوق قد لا يحذف تفويض PayPal أو Venmo أو Cash App أو Google Pay.",
                        "إذا رفض التطبيق حذف البطاقة",
                        "ابحث عن طلب نشط أو عضوية أو اشتراك أو رصيد غير مدفوع أو ملف عائلة/شركة أو طريقة دفع احتياطية مطلوبة. ألغ الخدمة، أو غيّر الدفع الافتراضي إلى بطاقة أكثر أمانا بحد منخفض، أو تواصل مع الدعم الرسمي للتطبيق. ثم تحقق من تفعيل تنبيهات تطبيق البنك/البطاقة.");
            case "ko":
                return merchantGuideValue(key,
                        "앱에서 저장된 카드 제거",
                        "기본 규칙",
                        "보호자와 함께 각 앱을 여세요. Account 또는 Profile로 간 뒤 Wallet 또는 Payment methods를 찾습니다. 저장된 카드를 모두 제거하고 Subscriptions, Membership, Autopay, Pass, Plus 요금제도 확인하세요. 이런 항목에는 별도 기본 결제수단이 남아 있을 수 있습니다.",
                        "Amazon",
                        "Amazon > 프로필 아이콘 또는 메뉴 > Your Account > Your Payments 또는 Wallet을 여세요. 각 카드를 누른 뒤 Edit 또는 Remove from wallet을 선택하세요. Prime, Subscribe & Save, 디지털 구독, 1-Click/기본 주소 설정도 확인하세요.",
                        "Walmart",
                        "Walmart > Account > Settings 톱니바퀴 > Wallet을 여세요. 저장된 카드나 은행 결제수단을 제거하세요. 카드 제거가 막히면 Walmart+, 구독, 약국, 대기 중인 주문을 먼저 확인하세요.",
                        "Target",
                        "Target > My Target > Settings 또는 Profile > Payment cards 또는 Wallet을 여세요. 저장된 카드를 제거하세요. Target Circle Card, Wallet 바코드 결제, 구독, 레지스트리, 당일 배송 설정도 확인하세요.",
                        "DoorDash, Uber Eats, Instacart",
                        "Account 또는 Profile > Payment 또는 Payment methods를 여세요. 카드를 누르거나 왼쪽으로 밀거나 Edit/Delete가 보이면 사용하세요. DashPass, Instacart+, 멤버십, 기프트 카드 잔액, PayPal, Venmo, Google Pay, 백업 결제수단도 확인하세요.",
                        "Uber 및 Lyft",
                        "Account 또는 Menu > Wallet, Payment 또는 Payment methods를 여세요. 각 카드를 선택하고 Remove 또는 Delete를 누르세요. 제거가 막히면 기본 승차 결제, 비즈니스 프로필, Lyft Cash, Uber Cash, Uber One, 가족/비즈니스 계정을 먼저 바꾸세요.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "금융/지갑 앱을 열고 Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions, Authorized merchants를 확인하세요. 쇼핑 앱에서 카드를 제거해도 PayPal, Venmo, Cash App, Google Pay 승인이 남을 수 있습니다.",
                        "앱에서 카드 제거를 허용하지 않을 때",
                        "진행 중인 주문, 멤버십, 구독, 미결제 잔액, 가족/비즈니스 프로필, 필수 백업 결제수단을 확인하세요. 서비스를 취소하거나 기본 결제를 더 안전한 저한도 카드로 바꾸거나 공식 지원에 문의하세요. 그 뒤 은행/카드 앱 알림이 켜져 있는지 확인하세요.");
            case "vi":
                return merchantGuideValue(key,
                        "Gỡ thẻ đã lưu khỏi ứng dụng",
                        "Quy tắc chung",
                        "Mở từng ứng dụng khi có người chăm sóc. Vào Account hoặc Profile, rồi Wallet hoặc Payment methods. Gỡ mọi thẻ đã lưu, sau đó kiểm tra Subscriptions, Membership, Autopay, Pass hoặc Plus vì chúng có thể giữ phương thức thanh toán mặc định riêng.",
                        "Amazon",
                        "Mở Amazon > biểu tượng hồ sơ hoặc menu > Your Account > Your Payments hoặc Wallet. Chạm từng thẻ, rồi chọn Edit hoặc Remove from wallet. Kiểm tra thêm Prime, Subscribe & Save, đăng ký số và cài đặt 1-Click/địa chỉ mặc định.",
                        "Walmart",
                        "Mở Walmart > Account > biểu tượng bánh răng Settings > Wallet. Gỡ thẻ hoặc phương thức thanh toán ngân hàng đã lưu. Nếu không gỡ được thẻ, hãy kiểm tra Walmart+, đăng ký, nhà thuốc hoặc đơn hàng đang chờ trước.",
                        "Target",
                        "Mở Target > My Target > Settings hoặc Profile > Payment cards hoặc Wallet. Gỡ thẻ đã lưu. Kiểm tra thêm Target Circle Card, thanh toán bằng mã vạch Wallet, đăng ký, registry và cài đặt giao hàng trong ngày.",
                        "DoorDash, Uber Eats, Instacart",
                        "Mở Account hoặc Profile > Payment hoặc Payment methods. Chạm thẻ, vuốt trái, hoặc dùng Edit/Delete nếu có. Kiểm tra thêm DashPass, Instacart+, membership, số dư thẻ quà tặng, PayPal, Venmo, Google Pay và phương thức thanh toán dự phòng.",
                        "Uber và Lyft",
                        "Mở Account hoặc Menu > Wallet, Payment hoặc Payment methods. Chọn từng thẻ rồi chọn Remove hoặc Delete. Nếu bị chặn, hãy đổi thanh toán mặc định, hồ sơ công việc, Lyft Cash, Uber Cash, Uber One hoặc tài khoản gia đình/doanh nghiệp trước.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Mở ứng dụng tiền hoặc ví và xem Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions hoặc Authorized merchants. Gỡ thẻ khỏi ứng dụng mua sắm có thể không gỡ quyền thanh toán PayPal, Venmo, Cash App hoặc Google Pay.",
                        "Nếu ứng dụng không cho gỡ thẻ",
                        "Tìm đơn hàng đang hoạt động, membership, đăng ký, số dư chưa trả, hồ sơ gia đình/doanh nghiệp hoặc phương thức thanh toán dự phòng bắt buộc. Hủy dịch vụ, đổi mặc định sang thẻ an toàn hơn có hạn mức thấp, hoặc liên hệ hỗ trợ chính thức của ứng dụng. Sau đó xác nhận cảnh báo trong app ngân hàng/thẻ đã bật.");
            case "tl":
                return merchantGuideValue(key,
                        "Alisin ang naka-save na cards sa apps",
                        "Pangkalahatang tuntunin",
                        "Buksan ang bawat app kasama ang tagapag-alaga. Pumunta sa Account o Profile, tapos Wallet o Payment methods. Alisin ang lahat ng naka-save na card, tapos tingnan ang Subscriptions, Membership, Autopay, Pass, o Plus plans dahil maaaring may hiwalay silang default payment method.",
                        "Amazon",
                        "Buksan ang Amazon > profile icon o menu > Your Account > Your Payments o Wallet. Tapikin ang bawat card, tapos Edit o Remove from wallet. Tingnan din ang Prime, Subscribe & Save, digital subscriptions, at 1-Click/default address settings.",
                        "Walmart",
                        "Buksan ang Walmart > Account > Settings gear > Wallet. Alisin ang naka-save na cards o bank payment methods. Kung hindi maalis ang card, tingnan muna ang Walmart+, subscriptions, pharmacy, o pending orders.",
                        "Target",
                        "Buksan ang Target > My Target > Settings o Profile > Payment cards o Wallet. Alisin ang naka-save na cards. Tingnan din ang Target Circle Card, Wallet barcode payment, subscriptions, registries, at same-day delivery settings.",
                        "DoorDash, Uber Eats, Instacart",
                        "Buksan ang Account o Profile > Payment o Payment methods. Tapikin ang card, i-swipe pakaliwa, o gamitin ang Edit/Delete kung meron. Tingnan din ang DashPass, Instacart+, memberships, gift card balance, PayPal, Venmo, Google Pay, at backup payment methods.",
                        "Uber at Lyft",
                        "Buksan ang Account o Menu > Wallet, Payment, o Payment methods. Piliin ang bawat card at Remove o Delete. Kung naka-block ang pagtanggal, palitan muna ang default ride payment, business profile, Lyft Cash, Uber Cash, Uber One, o family/business account.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Buksan ang money o wallet app at tingnan ang Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions, o Authorized merchants. Ang pagtanggal ng card sa shopping app ay maaaring hindi magtanggal ng PayPal, Venmo, Cash App, o Google Pay authorization.",
                        "Kung ayaw ipatanggal ng app ang card",
                        "Hanapin kung may active order, membership, subscription, unpaid balance, family/business profile, o required backup payment method. Kanselahin ang service, palitan ang default payment sa mas ligtas na low-limit card, o kontakin ang official support ng app. Pagkatapos, siguraduhing naka-on ang alerts sa bank/card app.");
            case "zh-TW":
                return merchantGuideValue(key,
                        "從應用程式移除已儲存卡片",
                        "一般規則",
                        "請照護者在場時逐一開啟每個應用程式。進入「帳戶」或「個人資料」，再找 Wallet、錢包或付款方式。移除所有已儲存卡片，接著檢查訂閱、會員、自動付款、Pass 或 Plus 方案，因為它們可能保留另一個預設付款方式。",
                        "Amazon",
                        "開啟 Amazon > 個人資料圖示或選單 > Your Account > Your Payments 或 Wallet。點選每張卡片，然後選 Edit 或 Remove from wallet。也檢查 Prime、Subscribe & Save、數位訂閱和 1-Click/預設地址設定。",
                        "Walmart",
                        "開啟 Walmart > Account > Settings 齒輪 > Wallet。移除已儲存卡片或銀行付款方式。如果無法移除卡片，請先檢查 Walmart+、訂閱、藥局或待處理訂單。",
                        "Target",
                        "開啟 Target > My Target > Settings 或 Profile > Payment cards 或 Wallet。移除已儲存卡片。也檢查 Target Circle Card、Wallet 條碼付款、訂閱、登記清單和當日配送設定。",
                        "DoorDash, Uber Eats, Instacart",
                        "開啟 Account 或 Profile > Payment 或 Payment methods。點選卡片、向左滑動，或使用 Edit/Delete。也檢查 DashPass、Instacart+、會員、禮品卡餘額、PayPal、Venmo、Google Pay 和備用付款方式。",
                        "Uber 和 Lyft",
                        "開啟 Account 或 Menu > Wallet、Payment 或 Payment methods。選擇每張卡片並點 Remove 或 Delete。如果無法移除，請先更改預設叫車付款、商務個人資料、Lyft Cash、Uber Cash、Uber One 或家庭/商務帳戶。",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "開啟金錢或錢包應用程式，檢查 Payment methods、Cards、Banks、Linked apps、Automatic payments、Subscriptions 或 Authorized merchants。從購物 app 移除卡片不一定會移除 PayPal、Venmo、Cash App 或 Google Pay 授權。",
                        "如果應用程式不讓你移除卡片",
                        "檢查是否有進行中的訂單、會員、訂閱、未付餘額、家庭/商務個人資料或必要備用付款方式。取消服務、把預設付款改成較安全的低額度卡片，或聯絡該應用程式官方客服。然後確認銀行/信用卡 app 已開啟通知。");
            case "zh":
                return merchantGuideValue(key,
                        "从应用中移除已保存银行卡",
                        "一般规则",
                        "请照护者在场时逐一打开每个应用。进入“账户”或“个人资料”，然后找 Wallet、钱包或付款方式。移除所有已保存银行卡，再检查订阅、会员、自动付款、Pass 或 Plus 计划，因为它们可能保留单独的默认付款方式。",
                        "Amazon",
                        "打开 Amazon > 个人资料图标或菜单 > Your Account > Your Payments 或 Wallet。点每张卡，然后选择 Edit 或 Remove from wallet。也检查 Prime、Subscribe & Save、数字订阅和 1-Click/默认地址设置。",
                        "Walmart",
                        "打开 Walmart > Account > Settings 齿轮 > Wallet。移除已保存银行卡或银行付款方式。如果不能移除某张卡，请先检查 Walmart+、订阅、药房或待处理订单。",
                        "Target",
                        "打开 Target > My Target > Settings 或 Profile > Payment cards 或 Wallet。移除已保存银行卡。也检查 Target Circle Card、Wallet 条码付款、订阅、登记清单和当日配送设置。",
                        "DoorDash, Uber Eats, Instacart",
                        "打开 Account 或 Profile > Payment 或 Payment methods。点银行卡、向左滑动，或使用 Edit/Delete。也检查 DashPass、Instacart+、会员、礼品卡余额、PayPal、Venmo、Google Pay 和备用付款方式。",
                        "Uber 和 Lyft",
                        "打开 Account 或 Menu > Wallet、Payment 或 Payment methods。选择每张卡并点 Remove 或 Delete。如果无法移除，请先更改默认打车付款、商务资料、Lyft Cash、Uber Cash、Uber One 或家庭/商务账户。",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "打开钱包或转账应用，检查 Payment methods、Cards、Banks、Linked apps、Automatic payments、Subscriptions 或 Authorized merchants。从购物应用移除银行卡不一定会移除 PayPal、Venmo、Cash App 或 Google Pay 授权。",
                        "如果应用不让移除银行卡",
                        "检查是否有进行中的订单、会员、订阅、未付款余额、家庭/商务资料或必需的备用付款方式。取消服务，把默认付款改成更安全的低额度卡，或联系该应用的官方客服。然后确认银行/信用卡应用已开启提醒。");
            case "es":
                return merchantGuideValue(key,
                        "Quitar tarjetas guardadas de apps",
                        "Regla general",
                        "Abre cada app con el cuidador presente. Ve a Account o Profile, luego Wallet o Payment methods. Quita todas las tarjetas guardadas y revisa Subscriptions, Membership, Autopay, Pass o Plus, porque pueden guardar otro pago predeterminado.",
                        "Amazon",
                        "Abre Amazon > icono de perfil o menu > Your Account > Your Payments o Wallet. Toca cada tarjeta y elige Edit o Remove from wallet. Revisa tambien Prime, Subscribe & Save, suscripciones digitales y ajustes de 1-Click/direccion predeterminada.",
                        "Walmart",
                        "Abre Walmart > Account > engrane de Settings > Wallet. Quita tarjetas o pagos bancarios guardados. Si no se puede quitar una tarjeta, revisa primero Walmart+, suscripciones, farmacia o pedidos pendientes.",
                        "Target",
                        "Abre Target > My Target > Settings o Profile > Payment cards o Wallet. Quita tarjetas guardadas. Revisa tambien Target Circle Card, pago con codigo de barras de Wallet, suscripciones, registros y entrega el mismo dia.",
                        "DoorDash, Uber Eats, Instacart",
                        "Abre Account o Profile > Payment o Payment methods. Toca la tarjeta, desliza a la izquierda o usa Edit/Delete si aparece. Revisa tambien DashPass, Instacart+, membresias, saldo de gift card, PayPal, Venmo, Google Pay y pagos de respaldo.",
                        "Uber y Lyft",
                        "Abre Account o Menu > Wallet, Payment o Payment methods. Selecciona cada tarjeta y elige Remove o Delete. Si se bloquea, cambia primero el pago predeterminado, perfil de negocios, Lyft Cash, Uber Cash, Uber One o cuenta familiar/empresarial.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Abre la app de dinero o wallet y revisa Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions o Authorized merchants. Quitar una tarjeta de una app de compras no siempre quita una autorizacion de PayPal, Venmo, Cash App o Google Pay.",
                        "Si una app no deja quitar la tarjeta",
                        "Busca pedido activo, membresia, suscripcion, saldo pendiente, perfil familiar/empresarial o pago de respaldo requerido. Cancela el servicio, cambia el pago predeterminado a una tarjeta mas segura de bajo limite o contacta soporte oficial de la app. Luego verifica alertas en la app del banco/tarjeta.");
            default:
                return merchantGuideValue(key,
                        "Remove saved cards from apps",
                        "General rule",
                        "Open each app with the caregiver present. Go to Account or Profile, then Wallet or Payment methods. Remove every saved card, then check Subscriptions, Membership, Autopay, Pass, or Plus plans because those can keep a separate default payment method.",
                        "Amazon",
                        "Open Amazon > person icon or menu > Your Account > Your Payments or Wallet. Tap each card, then Edit or Remove from wallet. Also check Prime, Subscribe & Save, digital subscriptions, and 1-Click/default address settings.",
                        "Walmart",
                        "Open Walmart > Account > Settings gear > Wallet. Remove saved cards or bank payment methods. If a card cannot be removed, check Walmart+, subscriptions, pharmacy, or pending orders first.",
                        "Target",
                        "Open Target > My Target > Settings or Profile > Payment cards or Wallet. Remove saved cards. Also check Target Circle Card, Wallet barcode payment, subscriptions, registries, and same-day delivery settings.",
                        "DoorDash, Uber Eats, Instacart",
                        "Open Account or Profile > Payment or Payment methods. Tap the card, swipe left, or use Edit/Delete if shown. Also check DashPass, Instacart+, memberships, gift card balance, PayPal, Venmo, Google Pay, and backup payment methods.",
                        "Uber and Lyft",
                        "Open Account or Menu > Wallet, Payment, or Payment methods. Select each card and choose Remove or Delete. If removal is blocked, change the default ride payment, business profile, Lyft Cash, Uber Cash, Uber One, or family/business account first.",
                        "PayPal, Venmo, Cash App, Google Wallet",
                        "Open the money or wallet app and review Payment methods, Cards, Banks, Linked apps, Automatic payments, Subscriptions, or Authorized merchants. Removing a card from a shopping app may not remove a PayPal, Venmo, Cash App, or Google Pay authorization.",
                        "If an app will not let you remove the card",
                        "Look for an active order, membership, subscription, unpaid balance, family/business profile, or required backup payment method. Cancel the service, change its default payment to a safer low-limit card, or contact the app's official support. Then verify in the bank/card app that alerts are on.");
        }
    }

    private String merchantGuideValue(
            String key,
            String title,
            String generalTitle,
            String generalBody,
            String amazonTitle,
            String amazonBody,
            String walmartTitle,
            String walmartBody,
            String targetTitle,
            String targetBody,
            String deliveryTitle,
            String deliveryBody,
            String ridesTitle,
            String ridesBody,
            String moneyTitle,
            String moneyBody,
            String stuckTitle,
            String stuckBody
    ) {
        switch (key) {
            case "merchant_guide_title": return title;
            case "merchant_guide_general_title": return generalTitle;
            case "merchant_guide_general_body": return generalBody;
            case "merchant_guide_amazon_title": return amazonTitle;
            case "merchant_guide_amazon_body": return amazonBody;
            case "merchant_guide_walmart_title": return walmartTitle;
            case "merchant_guide_walmart_body": return walmartBody;
            case "merchant_guide_target_title": return targetTitle;
            case "merchant_guide_target_body": return targetBody;
            case "merchant_guide_delivery_title": return deliveryTitle;
            case "merchant_guide_delivery_body": return deliveryBody;
            case "merchant_guide_rides_title": return ridesTitle;
            case "merchant_guide_rides_body": return ridesBody;
            case "merchant_guide_money_title": return moneyTitle;
            case "merchant_guide_money_body": return moneyBody;
            case "merchant_guide_stuck_title": return stuckTitle;
            case "merchant_guide_stuck_body": return stuckBody;
            default: return null;
        }
    }

    private String t(String key) {
        String merchantGuide = merchantGuideText(key);
        if (merchantGuide != null) {
            return merchantGuide;
        }
        if (isFrench()) {
            switch (key) {
                case "language_button": return "Français";
                case "language_section": return "Langue";
                case "selected_language_label": return "Langue choisie";
                case "app_title": return "Protection des dépenses";
                case "senior_mode": return "Senior";
                case "caregiver_mode": return "Aidant";
                case "senior_status": return "État";
                case "protected_title": return "Protégé";
                case "protected_body": return "Le remplissage automatique des paiements exige l'accord de l'aidant.";
                case "needs_setup_title": return "Configuration requise";
                case "needs_setup_body": return "Demandez à l'aidant de terminer la configuration.";
                case "autofill": return "Autofill";
                case "card": return "Carte";
                case "on": return "Activé";
                case "off": return "Désactivé";
                case "no_card": return "Aucune carte";
                case "buying_rule": return "Avant d'acheter";
                case "buying_rule_body": return "Appelez l'aidant si une appli demande de payer ou d'enregistrer une carte.";
                case "scam_limit_title": return "Limite importante";
                case "scam_limit_body": return "Cette appli réduit le risque de remplissage automatique de carte et d'achats accidentels. Elle ne peut pas bloquer toutes les arnaques, appels sous pression, demandes de cartes cadeaux, virements, paiements en espèces ou achats saisis à la main.";
                case "caregiver": return "Aidant";
                case "locked": return "Verrouillé";
                case "locked_body": return "Entrez le PIN avant de modifier la configuration.";
                case "default_pin_title": return "Première utilisation";
                case "default_pin_body": return "Le PIN par défaut est 1234. Déverrouillez, puis choisissez un nouveau PIN.";
                case "unlocked": return "Déverrouillé";
                case "unlocked_body": return "Les modifications sont disponibles pendant 5 minutes.";
                case "caregiver_pin": return "PIN de l'aidant";
                case "unlock": return "Déverrouiller";
                case "forgot_pin_body": return "PIN oublié ? Effacez les données de cette appli et configurez-la à nouveau.";
                case "setup_step": return "Étape";
                case "pin_step": return "Changer le PIN";
                case "pin_step_body": return "Utilisez un PIN que le senior ne connaît pas.";
                case "current_pin": return "PIN actuel";
                case "new_pin": return "Nouveau PIN";
                case "confirm_pin": return "Confirmer le PIN";
                case "save_pin": return "Enregistrer le PIN";
                case "autofill_step": return "Activer Senior Spending Guard Autofill";
                case "autofill_step_body": return "Choisissez cette appli comme fournisseur Autofill d'Android.";
                case "autofill_on": return "Autofill est activé";
                case "autofill_on_body": return "Le remplissage des paiements passe par le PIN de l'aidant.";
                case "open_autofill": return "Ouvrir Autofill";
                case "replace_autofill": return "Changer de fournisseur";
                case "chrome_autofill_step": return "Activer Autofill dans Chrome";
                case "chrome_autofill_body": return "Faites utiliser à Chrome Senior Spending Guard Autofill, choisi dans Android, au lieu de l'Autofill par défaut de Chrome.";
                case "chrome_settings_guide_title": return "1. Ouvrir Services de saisie automatique";
                case "chrome_settings_guide_body": return "Ouvrez Chrome, touchez le menu en haut à droite, puis Paramètres. Dans Paramètres, choisissez Services de saisie automatique.";
                case "chrome_options_guide_title": return "2. Choisir Saisie automatique avec un autre service";
                case "chrome_options_guide_body": return "Sur la page Services de saisie automatique, choisissez Saisie automatique avec un autre service.";
                case "chrome_options_mock_title": return "Options de remplissage automatique";
                case "chrome_options_mock_choice": return "Saisie automatique avec un autre service";
                case "open_chrome_autofill_options": return "Ouvrir les options Chrome";
                case "open_chrome_test": return "Tester dans Chrome";
                case "card_step": return "Carte approuvée";
                case "name_on_card": return "Nom sur la carte";
                case "card_number": return "Numéro de carte";
                case "expiration": return "Expiration MM/AA";
                case "cvv": return "CVV";
                case "postal": return "Code postal";
                case "save_card": return "Enregistrer la carte";
                case "clear_card": return "Effacer la carte";
                case "test_card_hint": return "Utilisez seulement une carte de test, prépayée ou à faible limite.";
                case "store_step": return "Réglages externes";
                case "store_step_body": return "Ces écrans ne peuvent pas être changés automatiquement par l'appli.";
                case "chrome_button": return "Ouvrir Chrome";
                case "play_button": return "Ouvrir Play Store";
                case "merchant_apps_hint": return "Retirez les cartes enregistrées des applis d'achat, repas, transport, jeux et abonnements.";
                case "finish_step": return "Prêt";
                case "finish_step_body": return "Chaque remplissage de carte exige le PIN de l'aidant.";
                case "spending_history_title": return "Historique d'achat";
                case "spending_history_body": return "Utilisez l'appli de la banque ou de la carte pour les vrais frais. Cette appli ne remplace pas cet historique.";
                case "back": return "Retour";
                case "next": return "Suivant";
                case "done": return "Terminé";
                case "incorrect_pin": return "PIN incorrect.";
                case "pin_locked": return "Trop d'essais. Attendez 5 minutes.";
                case "unlocked_toast": return "Configuration déverrouillée pendant 5 minutes.";
                case "current_pin_wrong": return "Le PIN actuel est incorrect.";
                case "pin_rules": return "Utilisez au moins 4 chiffres.";
                case "pin_mismatch": return "Les PIN ne correspondent pas.";
                case "pin_saved": return "PIN enregistré.";
                case "card_saved": return "Carte enregistrée en sécurité.";
                case "card_cleared": return "Carte effacée.";
                case "enter_name": return "Entrez le nom sur la carte.";
                case "enter_card_number": return "Entrez un numéro de carte valide.";
                case "enter_expiration": return "Entrez une date future valide au format MM/AA.";
                case "enter_cvv": return "Entrez un CVV valide de 3 ou 4 chiffres.";
                case "enter_postal": return "Entrez un code postal valide.";
                case "autofill_settings_fallback": return "Choisissez Senior Spending Guard Autofill dans Passwords and autofill.";
                case "chrome_fallback": return "Ouvrez les moyens de paiement et désactivez le remplissage des cartes enregistrées.";
                case "chrome_autofill_steps": return "Chrome > Paramètres > Services de saisie automatique > Saisie automatique avec un autre service. Redémarrez Chrome après le changement.";
                case "play_steps": return "Photo de profil > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "Connectez-vous d'abord au Play Store si la vérification d'achat manque.";
            }
        }
        if (isPortuguese()) {
            switch (key) {
                case "language_button": return "Português";
                case "language_section": return "Idioma";
                case "selected_language_label": return "Selecionado";
                case "app_title": return "Proteção de gastos";
                case "senior_mode": return "Idoso";
                case "caregiver_mode": return "Cuidador";
                case "senior_status": return "Estado";
                case "protected_title": return "Protegido";
                case "protected_body": return "O autofill de pagamento precisa da aprovação do cuidador.";
                case "needs_setup_title": return "Configuração necessária";
                case "needs_setup_body": return "Peça ao cuidador para concluir a configuração.";
                case "autofill": return "Autofill";
                case "card": return "Cartão";
                case "on": return "Ativo";
                case "off": return "Inativo";
                case "no_card": return "Sem cartão salvo";
                case "buying_rule": return "Antes de comprar";
                case "buying_rule_body": return "Chame o cuidador se um app pedir pagamento ou salvar um cartão.";
                case "scam_limit_title": return "Limite importante";
                case "scam_limit_body": return "Este app reduz o risco de autofill de cartão e gastos acidentais. Ele não impede todos os golpes, ligações de pressão, pedidos de vale-presente, transferências, dinheiro vivo ou compras digitadas manualmente.";
                case "caregiver": return "Cuidador";
                case "locked": return "Bloqueado";
                case "locked_body": return "Digite o PIN antes de alterar a configuração.";
                case "default_pin_title": return "Primeira configuração";
                case "default_pin_body": return "O PIN padrão é 1234. Desbloqueie e depois escolha um novo PIN.";
                case "unlocked": return "Desbloqueado";
                case "unlocked_body": return "As alterações ficam disponíveis por 5 minutos.";
                case "caregiver_pin": return "PIN do cuidador";
                case "unlock": return "Desbloquear configuração";
                case "forgot_pin_body": return "Esqueceu o PIN? Limpe os dados deste app e configure novamente.";
                case "setup_step": return "Etapa";
                case "pin_step": return "Alterar PIN";
                case "pin_step_body": return "Use um PIN que o idoso não saiba.";
                case "current_pin": return "PIN atual";
                case "new_pin": return "Novo PIN";
                case "confirm_pin": return "Confirmar PIN";
                case "save_pin": return "Salvar PIN";
                case "autofill_step": return "Ativar Senior Spending Guard Autofill";
                case "autofill_step_body": return "Escolha este app como provedor de autofill do Android.";
                case "autofill_on": return "Autofill ativo";
                case "autofill_on_body": return "O autofill de pagamento passa pelo PIN do cuidador.";
                case "open_autofill": return "Abrir Autofill";
                case "replace_autofill": return "Trocar provedor";
                case "chrome_autofill_step": return "Ativar Autofill no Chrome";
                case "chrome_autofill_body": return "Faça o Chrome usar Senior Spending Guard Autofill, escolhido no Android, em vez do Autofill padrão do Chrome.";
                case "chrome_settings_guide_title": return "1. Abrir Preenchimento automático";
                case "chrome_settings_guide_body": return "Abra o Chrome, toque no menu no canto superior direito e depois em Configurações. Em Configurações, escolha Preenchimento automático.";
                case "chrome_options_guide_title": return "2. Escolher Preenchimento automático usando outro serviço";
                case "chrome_options_guide_body": return "Na página Preenchimento automático, escolha Preenchimento automático usando outro serviço.";
                case "chrome_options_mock_title": return "Opções de preenchimento automático";
                case "chrome_options_mock_choice": return "Preenchimento automático usando outro serviço";
                case "open_chrome_autofill_options": return "Abrir opções do Chrome";
                case "open_chrome_test": return "Testar no Chrome";
                case "card_step": return "Cartão aprovado";
                case "name_on_card": return "Nome no cartão";
                case "card_number": return "Número do cartão";
                case "expiration": return "Validade MM/AA";
                case "cvv": return "CVV";
                case "postal": return "CEP/código postal";
                case "save_card": return "Salvar cartão";
                case "clear_card": return "Limpar cartão";
                case "test_card_hint": return "Use apenas cartão de teste, pré-pago ou de baixo limite.";
                case "store_step": return "Configurações externas";
                case "store_step_body": return "Estas telas não podem ser alteradas automaticamente pelo app.";
                case "chrome_button": return "Abrir Chrome";
                case "play_button": return "Abrir Play Store";
                case "merchant_apps_hint": return "Remova cartões salvos de apps de compras, comida, transporte, jogos e assinaturas.";
                case "finish_step": return "Pronto";
                case "finish_step_body": return "Todo autofill de cartão exige o PIN do cuidador.";
                case "spending_history_title": return "Histórico de compras";
                case "spending_history_body": return "Use o app do banco ou cartão para ver cobranças reais. Este app não substitui esse histórico.";
                case "back": return "Voltar";
                case "next": return "Avançar";
                case "done": return "Concluir";
                case "incorrect_pin": return "PIN incorreto.";
                case "pin_locked": return "Muitas tentativas. Aguarde 5 minutos.";
                case "unlocked_toast": return "Configuração desbloqueada por 5 minutos.";
                case "current_pin_wrong": return "O PIN atual está incorreto.";
                case "pin_rules": return "Use pelo menos 4 dígitos.";
                case "pin_mismatch": return "Os PINs não coincidem.";
                case "pin_saved": return "PIN salvo.";
                case "card_saved": return "Cartão salvo com segurança.";
                case "card_cleared": return "Cartão limpo.";
                case "enter_name": return "Digite o nome no cartão.";
                case "enter_card_number": return "Digite um número de cartão válido.";
                case "enter_expiration": return "Digite uma validade futura válida como MM/AA.";
                case "enter_cvv": return "Digite um CVV válido de 3 ou 4 dígitos.";
                case "enter_postal": return "Digite um CEP ou código postal válido.";
                case "autofill_settings_fallback": return "Escolha Senior Spending Guard Autofill em Passwords and autofill.";
                case "chrome_fallback": return "Abra métodos de pagamento e desative o preenchimento de cartões salvos.";
                case "chrome_autofill_steps": return "Chrome > Configurações > Preenchimento automático > Preenchimento automático usando outro serviço. Reinicie o Chrome após alterar.";
                case "play_steps": return "Foto do perfil > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "Entre no Play Store primeiro se a verificação de compra não aparecer.";
            }
        }
        if (isHindi()) {
            switch (key) {
                case "language_button": return "हिन्दी";
                case "language_section": return "भाषा";
                case "selected_language_label": return "चुनी गई भाषा";
                case "app_title": return "खर्च सुरक्षा";
                case "senior_mode": return "वरिष्ठ";
                case "caregiver_mode": return "देखभालकर्ता";
                case "senior_status": return "स्थिति";
                case "protected_title": return "सुरक्षित";
                case "protected_body": return "भुगतान ऑटोफिल के लिए देखभालकर्ता की मंजूरी चाहिए.";
                case "needs_setup_title": return "सेटअप चाहिए";
                case "needs_setup_body": return "देखभालकर्ता से सेटअप पूरा करने को कहें.";
                case "autofill": return "ऑटोफिल";
                case "card": return "कार्ड";
                case "on": return "चालू";
                case "off": return "बंद";
                case "no_card": return "कोई कार्ड नहीं";
                case "buying_rule": return "खरीदने से पहले";
                case "buying_rule_body": return "अगर कोई ऐप भुगतान या कार्ड सेव करने को कहे, तो पहले देखभालकर्ता को कॉल करें.";
                case "scam_limit_title": return "महत्वपूर्ण सीमा";
                case "scam_limit_body": return "यह ऐप गलती से कार्ड ऑटोफिल और खर्च का जोखिम कम करता है. यह हर धोखाधड़ी, दबाव वाले कॉल, गिफ्ट कार्ड मांग, बैंक ट्रांसफर, नकद भुगतान या हाथ से टाइप की गई खरीदारी को नहीं रोक सकता.";
                case "caregiver": return "देखभालकर्ता";
                case "locked": return "लॉक";
                case "locked_body": return "सेटअप बदलने से पहले PIN डालें.";
                case "default_pin_title": return "पहली बार सेटअप";
                case "default_pin_body": return "डिफॉल्ट PIN 1234 है. अनलॉक करें, फिर नया PIN चुनें.";
                case "unlocked": return "अनलॉक";
                case "unlocked_body": return "सेटअप बदलाव 5 मिनट तक उपलब्ध हैं.";
                case "caregiver_pin": return "देखभालकर्ता PIN";
                case "unlock": return "सेटअप अनलॉक करें";
                case "forgot_pin_body": return "PIN भूल गए? इस ऐप का डेटा साफ करें और फिर से सेट करें.";
                case "setup_step": return "चरण";
                case "pin_step": return "देखभालकर्ता PIN बदलें";
                case "pin_step_body": return "ऐसा PIN इस्तेमाल करें जो वरिष्ठ को पता न हो.";
                case "current_pin": return "मौजूदा PIN";
                case "new_pin": return "नया PIN";
                case "confirm_pin": return "PIN पुष्टि";
                case "save_pin": return "PIN सेव करें";
                case "autofill_step": return "Senior Spending Guard Autofill चालू करें";
                case "autofill_step_body": return "इस ऐप को Android का autofill provider चुनें.";
                case "autofill_on": return "Autofill चालू है";
                case "autofill_on_body": return "भुगतान autofill अब देखभालकर्ता PIN से होकर जाएगा.";
                case "open_autofill": return "Autofill खोलें";
                case "replace_autofill": return "Provider बदलें";
                case "chrome_autofill_step": return "Chrome Autofill चालू करें";
                case "chrome_autofill_body": return "Chrome को Chrome के default Autofill की जगह Android में चुना गया Senior Spending Guard Autofill इस्तेमाल करने दें.";
                case "chrome_settings_guide_title": return "1. जानकारी अपने-आप भरने वाली सेवाएं खोलें";
                case "chrome_settings_guide_body": return "Chrome खोलें, ऊपर दाईं ओर मेन्यू दबाएँ, फिर सेटिंग खोलें. सेटिंग में जानकारी अपने-आप भरने वाली सेवाएं चुनें.";
                case "chrome_options_guide_title": return "2. जानकारी ऑटोमैटिक भरने की कोई दूसरी सेवा चुनें";
                case "chrome_options_guide_body": return "जानकारी अपने-आप भरने वाली सेवाएं पेज पर जानकारी ऑटोमैटिक भरने की कोई दूसरी सेवा चुनें.";
                case "chrome_options_mock_title": return "ऑटोफ़िल विकल्प";
                case "chrome_options_mock_choice": return "जानकारी ऑटोमैटिक भरने की कोई दूसरी सेवा";
                case "open_chrome_autofill_options": return "Chrome options खोलें";
                case "open_chrome_test": return "Chrome में टेस्ट करें";
                case "card_step": return "मंजूर कार्ड";
                case "name_on_card": return "कार्ड पर नाम";
                case "card_number": return "कार्ड नंबर";
                case "expiration": return "समाप्ति MM/YY";
                case "cvv": return "CVV";
                case "postal": return "ZIP/पोस्टल कोड";
                case "save_card": return "कार्ड सेव करें";
                case "clear_card": return "कार्ड हटाएँ";
                case "test_card_hint": return "केवल टेस्ट, प्रीपेड या कम-सीमा वाला कार्ड इस्तेमाल करें.";
                case "store_step": return "बाहरी सेटिंग्स";
                case "store_step_body": return "इन स्क्रीन को ऐप अपने आप नहीं बदल सकता.";
                case "chrome_button": return "Chrome खोलें";
                case "play_button": return "Play Store खोलें";
                case "merchant_apps_hint": return "Shopping, food, ride, game और subscription apps से सेव कार्ड हटाएँ.";
                case "finish_step": return "तैयार";
                case "finish_step_body": return "हर card autofill के लिए देखभालकर्ता PIN चाहिए.";
                case "spending_history_title": return "खरीदारी इतिहास";
                case "spending_history_body": return "असल charges देखने के लिए bank या card app इस्तेमाल करें. यह ऐप उस history की जगह नहीं लेता.";
                case "back": return "पीछे";
                case "next": return "आगे";
                case "done": return "पूर्ण";
                case "incorrect_pin": return "PIN गलत है.";
                case "pin_locked": return "बहुत कोशिशें हुईं. 5 मिनट प्रतीक्षा करें.";
                case "unlocked_toast": return "सेटअप 5 मिनट के लिए अनलॉक है.";
                case "current_pin_wrong": return "मौजूदा PIN गलत है.";
                case "pin_rules": return "कम से कम 4 अंक इस्तेमाल करें.";
                case "pin_mismatch": return "PIN मेल नहीं खाते.";
                case "pin_saved": return "PIN सेव हुआ.";
                case "card_saved": return "कार्ड सुरक्षित रूप से सेव हुआ.";
                case "card_cleared": return "कार्ड हटाया गया.";
                case "enter_name": return "कार्ड पर लिखा नाम डालें.";
                case "enter_card_number": return "मान्य कार्ड नंबर डालें.";
                case "enter_expiration": return "MM/YY में मान्य भविष्य की समाप्ति डालें.";
                case "enter_cvv": return "3 या 4 अंकों का मान्य CVV डालें.";
                case "enter_postal": return "मान्य ZIP या postal code डालें.";
                case "autofill_settings_fallback": return "Passwords and autofill में Senior Spending Guard Autofill चुनें.";
                case "chrome_fallback": return "Payment methods खोलें और saved-card filling बंद करें.";
                case "chrome_autofill_steps": return "Chrome > सेटिंग > जानकारी अपने-आप भरने वाली सेवाएं > जानकारी ऑटोमैटिक भरने की कोई दूसरी सेवा. बदलने के बाद Chrome restart करें.";
                case "play_steps": return "Profile picture > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "अगर purchase verification न दिखे तो पहले Play Store में sign in करें.";
            }
        }
        if (isArabic()) {
            switch (key) {
                case "language_button": return "العربية";
                case "language_section": return "اللغة";
                case "selected_language_label": return "اللغة المختارة";
                case "app_title": return "حماية الإنفاق";
                case "senior_mode": return "كبير السن";
                case "caregiver_mode": return "مقدم الرعاية";
                case "senior_status": return "الحالة";
                case "protected_title": return "محمي";
                case "protected_body": return "يتطلب الملء التلقائي للدفع موافقة مقدم الرعاية.";
                case "needs_setup_title": return "يلزم الإعداد";
                case "needs_setup_body": return "اطلب من مقدم الرعاية إكمال الإعداد.";
                case "autofill": return "الملء التلقائي";
                case "card": return "البطاقة";
                case "on": return "مفعّل";
                case "off": return "متوقف";
                case "no_card": return "لا توجد بطاقة";
                case "buying_rule": return "قبل الشراء";
                case "buying_rule_body": return "اتصل بمقدم الرعاية إذا طلب أي تطبيق الدفع أو حفظ بطاقة.";
                case "scam_limit_title": return "حد مهم";
                case "scam_limit_body": return "يقلل هذا التطبيق خطر ملء البطاقة والإنفاق بالخطأ. لا يمكنه إيقاف كل عمليات الاحتيال أو مكالمات الضغط أو طلبات بطاقات الهدايا أو التحويلات البنكية أو الدفع النقدي أو المشتريات المكتوبة يدويا.";
                case "caregiver": return "مقدم الرعاية";
                case "locked": return "مغلق";
                case "locked_body": return "أدخل PIN قبل تغيير الإعداد.";
                case "default_pin_title": return "الإعداد لأول مرة";
                case "default_pin_body": return "PIN الافتراضي هو 1234. افتح الإعداد ثم اختر PIN جديدا.";
                case "unlocked": return "مفتوح";
                case "unlocked_body": return "يمكن تغيير الإعداد لمدة 5 دقائق.";
                case "caregiver_pin": return "PIN مقدم الرعاية";
                case "unlock": return "فتح الإعداد";
                case "forgot_pin_body": return "نسيت PIN؟ امسح بيانات هذا التطبيق وأعد إعداده.";
                case "setup_step": return "الخطوة";
                case "pin_step": return "تغيير PIN";
                case "pin_step_body": return "استخدم PIN لا يعرفه كبير السن.";
                case "current_pin": return "PIN الحالي";
                case "new_pin": return "PIN جديد";
                case "confirm_pin": return "تأكيد PIN";
                case "save_pin": return "حفظ PIN";
                case "autofill_step": return "تشغيل Senior Spending Guard Autofill";
                case "autofill_step_body": return "اختر هذا التطبيق كمزود الملء التلقائي في Android.";
                case "autofill_on": return "Autofill مفعّل";
                case "autofill_on_body": return "يمر ملء الدفع التلقائي الآن عبر PIN مقدم الرعاية.";
                case "open_autofill": return "فتح Autofill";
                case "replace_autofill": return "تغيير المزود";
                case "chrome_autofill_step": return "تشغيل Autofill في Chrome";
                case "chrome_autofill_body": return "اجعل Chrome يستخدم Senior Spending Guard Autofill المختار في Android بدلا من Autofill الافتراضي في Chrome.";
                case "chrome_settings_guide_title": return "1. افتح خدمات الملء التلقائي";
                case "chrome_settings_guide_body": return "افتح Chrome واضغط القائمة في أعلى اليسار ثم الإعدادات. في الإعدادات، اختر خدمات الملء التلقائي.";
                case "chrome_options_guide_title": return "2. اختر الملء التلقائي باستخدام خدمة أخرى";
                case "chrome_options_guide_body": return "في صفحة خدمات الملء التلقائي، اختر الملء التلقائي باستخدام خدمة أخرى.";
                case "chrome_options_mock_title": return "خيارات الملء التلقائي";
                case "chrome_options_mock_choice": return "الملء التلقائي باستخدام خدمة أخرى";
                case "open_chrome_autofill_options": return "فتح خيارات Chrome";
                case "open_chrome_test": return "اختبار في Chrome";
                case "card_step": return "البطاقة المعتمدة";
                case "name_on_card": return "الاسم على البطاقة";
                case "card_number": return "رقم البطاقة";
                case "expiration": return "الانتهاء MM/YY";
                case "cvv": return "CVV";
                case "postal": return "الرمز البريدي";
                case "save_card": return "حفظ البطاقة";
                case "clear_card": return "مسح البطاقة";
                case "test_card_hint": return "استخدم فقط بطاقة اختبار أو بطاقة مسبقة الدفع أو بطاقة بحد منخفض.";
                case "store_step": return "إعدادات خارجية";
                case "store_step_body": return "لا يستطيع التطبيق تغيير هذه الشاشات تلقائيا.";
                case "chrome_button": return "فتح Chrome";
                case "play_button": return "فتح Play Store";
                case "merchant_apps_hint": return "أزل البطاقات المحفوظة من تطبيقات التسوق والطعام والتنقل والألعاب والاشتراكات.";
                case "finish_step": return "جاهز";
                case "finish_step_body": return "كل ملء تلقائي للبطاقة يتطلب PIN مقدم الرعاية.";
                case "spending_history_title": return "سجل المشتريات";
                case "spending_history_body": return "استخدم تطبيق البنك أو البطاقة لرؤية الرسوم الحقيقية. هذا التطبيق لا يحل محل ذلك السجل.";
                case "back": return "رجوع";
                case "next": return "التالي";
                case "done": return "تم";
                case "incorrect_pin": return "PIN غير صحيح.";
                case "pin_locked": return "محاولات كثيرة. انتظر 5 دقائق.";
                case "unlocked_toast": return "تم فتح الإعداد لمدة 5 دقائق.";
                case "current_pin_wrong": return "PIN الحالي غير صحيح.";
                case "pin_rules": return "استخدم 4 أرقام على الأقل.";
                case "pin_mismatch": return "أرقام PIN غير متطابقة.";
                case "pin_saved": return "تم حفظ PIN.";
                case "card_saved": return "تم حفظ البطاقة بأمان.";
                case "card_cleared": return "تم مسح البطاقة.";
                case "enter_name": return "أدخل الاسم على البطاقة.";
                case "enter_card_number": return "أدخل رقم بطاقة صالحا.";
                case "enter_expiration": return "أدخل تاريخ انتهاء مستقبلي صالحا بصيغة MM/YY.";
                case "enter_cvv": return "أدخل CVV صالحا من 3 أو 4 أرقام.";
                case "enter_postal": return "أدخل رمزا بريديا صالحا.";
                case "autofill_settings_fallback": return "اختر Senior Spending Guard Autofill ضمن Passwords and autofill.";
                case "chrome_fallback": return "افتح طرق الدفع وأوقف ملء البطاقات المحفوظة.";
                case "chrome_autofill_steps": return "Chrome > الإعدادات > خدمات الملء التلقائي > الملء التلقائي باستخدام خدمة أخرى. أعد تشغيل Chrome بعد التغيير.";
                case "play_steps": return "Profile picture > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "سجل الدخول إلى Play Store أولا إذا لم تظهر ميزة التحقق من الشراء.";
            }
        }
        if (isKorean()) {
            switch (key) {
                case "language_button": return "한국어";
                case "language_section": return "언어";
                case "selected_language_label": return "선택한 언어";
                case "app_title": return "어르신 지출 보호";
                case "senior_mode": return "어르신";
                case "caregiver_mode": return "보호자";
                case "senior_status": return "상태";
                case "protected_title": return "보호 중";
                case "protected_body": return "결제 자동 입력에는 보호자 승인이 필요합니다.";
                case "needs_setup_title": return "설정 필요";
                case "needs_setup_body": return "보호자에게 설정을 완료해 달라고 요청하세요.";
                case "autofill": return "자동 입력";
                case "card": return "카드";
                case "on": return "켜짐";
                case "off": return "꺼짐";
                case "no_card": return "저장된 카드 없음";
                case "buying_rule": return "구매 전";
                case "buying_rule_body": return "앱에서 결제하거나 카드를 저장하라고 하면 먼저 보호자에게 연락하세요.";
                case "scam_limit_title": return "중요한 제한";
                case "scam_limit_body": return "이 앱은 실수로 카드 자동 입력이나 결제를 하는 위험을 낮춥니다. 모든 사기, 압박 전화, 기프트카드 요구, 계좌 이체, 현금 결제, 직접 입력한 결제를 막을 수는 없습니다.";
                case "caregiver": return "보호자";
                case "locked": return "잠김";
                case "locked_body": return "설정을 변경하려면 PIN을 입력하세요.";
                case "default_pin_title": return "처음 설정";
                case "default_pin_body": return "기본 PIN은 1234입니다. 잠금을 해제한 뒤 새 PIN을 선택하세요.";
                case "unlocked": return "잠금 해제됨";
                case "unlocked_body": return "5분 동안 설정을 변경할 수 있습니다.";
                case "caregiver_pin": return "보호자 PIN";
                case "unlock": return "설정 잠금 해제";
                case "forgot_pin_body": return "PIN을 잊었나요? 이 앱의 데이터를 지우고 다시 설정하세요.";
                case "setup_step": return "단계";
                case "pin_step": return "보호자 PIN 변경";
                case "pin_step_body": return "어르신이 모르는 PIN을 사용하세요.";
                case "current_pin": return "현재 PIN";
                case "new_pin": return "새 PIN";
                case "confirm_pin": return "PIN 확인";
                case "save_pin": return "PIN 저장";
                case "autofill_step": return "보호자 자동 입력 켜기";
                case "autofill_step_body": return "Android의 자동 입력 제공자로 이 앱을 선택하세요.";
                case "autofill_on": return "자동 입력 켜짐";
                case "autofill_on_body": return "결제 자동 입력은 이제 보호자 PIN을 거칩니다.";
                case "open_autofill": return "자동 입력 열기";
                case "replace_autofill": return "제공자 바꾸기";
                case "chrome_autofill_step": return "Chrome 자동 입력 켜기";
                case "chrome_autofill_body": return "Chrome 기본 자동 입력 대신 Android에서 선택한 Senior Spending Guard Autofill을 사용하게 합니다.";
                case "chrome_settings_guide_title": return "1. 자동 완성 서비스 열기";
                case "chrome_settings_guide_body": return "Chrome을 열고 오른쪽 위 메뉴를 누른 다음 설정을 여세요. 설정에서 자동 완성 서비스를 선택하세요.";
                case "chrome_options_guide_title": return "2. 다른 서비스로 자동 완성 선택";
                case "chrome_options_guide_body": return "자동 완성 서비스 페이지에서 다른 서비스로 자동 완성을 선택하세요.";
                case "chrome_options_mock_title": return "자동 입력 옵션";
                case "chrome_options_mock_choice": return "다른 서비스로 자동 완성";
                case "open_chrome_autofill_options": return "Chrome 옵션 열기";
                case "open_chrome_test": return "Chrome에서 테스트";
                case "card_step": return "승인된 카드";
                case "name_on_card": return "카드 이름";
                case "card_number": return "카드 번호";
                case "expiration": return "만료일 MM/YY";
                case "cvv": return "CVV";
                case "postal": return "우편번호";
                case "save_card": return "카드 저장";
                case "clear_card": return "카드 지우기";
                case "test_card_hint": return "테스트 카드, 선불 카드 또는 한도가 낮은 카드만 사용하세요.";
                case "store_step": return "외부 설정";
                case "store_step_body": return "이 화면들은 앱에서 자동으로 바꿀 수 없습니다.";
                case "chrome_button": return "Chrome 열기";
                case "play_button": return "Play 스토어 열기";
                case "merchant_apps_hint": return "쇼핑, 음식 배달, 차량 호출, 게임, 구독 앱에서 저장된 카드를 제거하세요.";
                case "finish_step": return "준비 완료";
                case "finish_step_body": return "카드 자동 입력은 매번 보호자 PIN이 필요합니다.";
                case "spending_history_title": return "구매 내역";
                case "spending_history_body": return "실제 청구 내역은 은행 또는 카드 앱에서 확인하세요. 이 앱은 그 내역을 대신하지 않습니다.";
                case "back": return "뒤로";
                case "next": return "다음";
                case "done": return "완료";
                case "incorrect_pin": return "PIN이 올바르지 않습니다.";
                case "pin_locked": return "시도 횟수가 너무 많습니다. 5분 기다리세요.";
                case "unlocked_toast": return "설정이 5분 동안 잠금 해제되었습니다.";
                case "current_pin_wrong": return "현재 PIN이 틀렸습니다.";
                case "pin_rules": return "숫자 4자리 이상을 사용하세요.";
                case "pin_mismatch": return "PIN이 일치하지 않습니다.";
                case "pin_saved": return "PIN이 저장되었습니다.";
                case "card_saved": return "카드가 안전하게 저장되었습니다.";
                case "card_cleared": return "카드가 지워졌습니다.";
                case "enter_name": return "카드 이름을 입력하세요.";
                case "enter_card_number": return "유효한 카드 번호를 입력하세요.";
                case "enter_expiration": return "MM/YY 형식의 유효한 미래 만료일을 입력하세요.";
                case "enter_cvv": return "유효한 3자리 또는 4자리 CVV를 입력하세요.";
                case "enter_postal": return "유효한 우편번호를 입력하세요.";
                case "autofill_settings_fallback": return "Passwords and autofill에서 Senior Spending Guard Autofill을 선택하세요.";
                case "chrome_fallback": return "결제 수단을 열고 저장된 카드 입력을 끄세요.";
                case "chrome_autofill_steps": return "Chrome > 설정 > 자동 완성 서비스 > 다른 서비스로 자동 완성. 변경 후 Chrome을 다시 시작하세요.";
                case "play_steps": return "프로필 사진 > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "구매 인증이 보이지 않으면 먼저 Play 스토어에 로그인하세요.";
            }
        }
        if (isVietnamese()) {
            switch (key) {
                case "language_button": return "Tiếng Việt";
                case "language_section": return "Ngôn ngữ";
                case "selected_language_label": return "Đã chọn";
                case "app_title": return "Bảo vệ chi tiêu";
                case "senior_mode": return "Người lớn tuổi";
                case "caregiver_mode": return "Người chăm sóc";
                case "senior_status": return "Trạng thái";
                case "protected_title": return "Đang được bảo vệ";
                case "protected_body": return "Tự động điền thanh toán cần người chăm sóc chấp thuận.";
                case "needs_setup_title": return "Cần thiết lập";
                case "needs_setup_body": return "Nhờ người chăm sóc hoàn tất thiết lập.";
                case "autofill": return "Tự động điền";
                case "card": return "Thẻ";
                case "on": return "Bật";
                case "off": return "Tắt";
                case "no_card": return "Chưa lưu thẻ";
                case "buying_rule": return "Trước khi mua";
                case "buying_rule_body": return "Gọi người chăm sóc nếu ứng dụng yêu cầu thanh toán hoặc lưu thẻ.";
                case "scam_limit_title": return "Giới hạn quan trọng";
                case "scam_limit_body": return "Ứng dụng này giảm nguy cơ tự động điền thẻ và chi tiêu ngoài ý muốn. Ứng dụng không thể chặn mọi lừa đảo, cuộc gọi gây áp lực, yêu cầu thẻ quà tặng, chuyển khoản, tiền mặt hoặc giao dịch nhập tay.";
                case "caregiver": return "Người chăm sóc";
                case "locked": return "Đã khóa";
                case "locked_body": return "Nhập PIN trước khi đổi thiết lập.";
                case "default_pin_title": return "Thiết lập lần đầu";
                case "default_pin_body": return "PIN mặc định là 1234. Mở khóa rồi chọn PIN mới.";
                case "unlocked": return "Đã mở khóa";
                case "unlocked_body": return "Có thể đổi thiết lập trong 5 phút.";
                case "caregiver_pin": return "PIN người chăm sóc";
                case "unlock": return "Mở khóa thiết lập";
                case "forgot_pin_body": return "Quên PIN? Xóa dữ liệu ứng dụng này rồi thiết lập lại.";
                case "setup_step": return "Bước";
                case "pin_step": return "Đổi PIN người chăm sóc";
                case "pin_step_body": return "Dùng PIN mà người lớn tuổi không biết.";
                case "current_pin": return "PIN hiện tại";
                case "new_pin": return "PIN mới";
                case "confirm_pin": return "Xác nhận PIN";
                case "save_pin": return "Lưu PIN";
                case "autofill_step": return "Bật Senior Spending Guard Autofill";
                case "autofill_step_body": return "Chọn ứng dụng này làm nhà cung cấp tự động điền của Android.";
                case "autofill_on": return "Tự động điền đã bật";
                case "autofill_on_body": return "Tự động điền thanh toán sẽ đi qua PIN người chăm sóc.";
                case "open_autofill": return "Mở Tự động điền";
                case "replace_autofill": return "Đổi nhà cung cấp";
                case "chrome_autofill_step": return "Bật tự động điền Chrome";
                case "chrome_autofill_body": return "Cho Chrome dùng Senior Spending Guard Autofill đã chọn trong Android thay vì tự động điền mặc định của Chrome.";
                case "chrome_settings_guide_title": return "1. Mở Dịch vụ tự động điền";
                case "chrome_settings_guide_body": return "Mở Chrome, chạm menu góc trên bên phải, rồi vào Cài đặt. Trong Cài đặt, chọn Dịch vụ tự động điền.";
                case "chrome_options_guide_title": return "2. Chọn Tự động điền bằng một dịch vụ khác";
                case "chrome_options_guide_body": return "Trên trang Dịch vụ tự động điền, chọn Tự động điền bằng một dịch vụ khác.";
                case "chrome_options_mock_title": return "Tùy chọn tự động điền";
                case "chrome_options_mock_choice": return "Tự động điền bằng một dịch vụ khác";
                case "open_chrome_autofill_options": return "Mở tùy chọn Chrome";
                case "open_chrome_test": return "Thử trong Chrome";
                case "card_step": return "Thẻ được chấp thuận";
                case "name_on_card": return "Tên trên thẻ";
                case "card_number": return "Số thẻ";
                case "expiration": return "Hết hạn MM/YY";
                case "cvv": return "CVV";
                case "postal": return "Mã bưu chính";
                case "save_card": return "Lưu thẻ";
                case "clear_card": return "Xóa thẻ";
                case "test_card_hint": return "Chỉ dùng thẻ thử nghiệm, thẻ trả trước hoặc thẻ hạn mức thấp.";
                case "store_step": return "Thiết lập bên ngoài";
                case "store_step_body": return "Các màn hình này không thể được ứng dụng tự động thay đổi.";
                case "chrome_button": return "Mở Chrome";
                case "play_button": return "Mở Play Store";
                case "merchant_apps_hint": return "Gỡ thẻ đã lưu khỏi ứng dụng mua sắm, đồ ăn, gọi xe, trò chơi và đăng ký.";
                case "finish_step": return "Sẵn sàng";
                case "finish_step_body": return "Mỗi lần tự động điền thẻ đều cần PIN người chăm sóc.";
                case "spending_history_title": return "Lịch sử mua hàng";
                case "spending_history_body": return "Dùng ứng dụng ngân hàng hoặc thẻ để xem khoản tính tiền thật. Ứng dụng này không thay thế lịch sử đó.";
                case "back": return "Quay lại";
                case "next": return "Tiếp";
                case "done": return "Xong";
                case "incorrect_pin": return "PIN không đúng.";
                case "pin_locked": return "Thử quá nhiều lần. Chờ 5 phút.";
                case "unlocked_toast": return "Đã mở khóa thiết lập trong 5 phút.";
                case "current_pin_wrong": return "PIN hiện tại không đúng.";
                case "pin_rules": return "Dùng ít nhất 4 chữ số.";
                case "pin_mismatch": return "PIN không khớp.";
                case "pin_saved": return "Đã lưu PIN.";
                case "card_saved": return "Đã lưu thẻ an toàn.";
                case "card_cleared": return "Đã xóa thẻ.";
                case "enter_name": return "Nhập tên trên thẻ.";
                case "enter_card_number": return "Nhập số thẻ hợp lệ.";
                case "enter_expiration": return "Nhập ngày hết hạn tương lai hợp lệ theo MM/YY.";
                case "enter_cvv": return "Nhập CVV hợp lệ 3 hoặc 4 chữ số.";
                case "enter_postal": return "Nhập mã bưu chính hợp lệ.";
                case "autofill_settings_fallback": return "Chọn Senior Spending Guard Autofill trong Passwords and autofill.";
                case "chrome_fallback": return "Mở phương thức thanh toán và tắt điền thẻ đã lưu.";
                case "chrome_autofill_steps": return "Chrome > Cài đặt > Dịch vụ tự động điền > Tự động điền bằng một dịch vụ khác. Khởi động lại Chrome sau khi đổi.";
                case "play_steps": return "Ảnh hồ sơ > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "Đăng nhập Play Store trước nếu không thấy xác minh mua hàng.";
            }
        }
        if (isTagalog()) {
            switch (key) {
                case "language_button": return "Tagalog";
                case "language_section": return "Wika";
                case "selected_language_label": return "Napili";
                case "app_title": return "Bantay Gastos";
                case "senior_mode": return "Senior";
                case "caregiver_mode": return "Tagapag-alaga";
                case "senior_status": return "Kalagayan";
                case "protected_title": return "Protektado";
                case "protected_body": return "Kailangan ng pahintulot ng tagapag-alaga para sa payment autofill.";
                case "needs_setup_title": return "Kailangan ng setup";
                case "needs_setup_body": return "Hilingin sa tagapag-alaga na tapusin ang setup.";
                case "autofill": return "Autofill";
                case "card": return "Card";
                case "on": return "Naka-on";
                case "off": return "Naka-off";
                case "no_card": return "Walang naka-save na card";
                case "buying_rule": return "Bago bumili";
                case "buying_rule_body": return "Tawagan ang tagapag-alaga kung humihingi ang app ng bayad o pag-save ng card.";
                case "scam_limit_title": return "Mahalagang limitasyon";
                case "scam_limit_body": return "Binabawasan ng app na ito ang panganib ng aksidenteng card autofill at paggastos. Hindi nito mapipigilan ang lahat ng scam, pressure call, gift-card request, bank transfer, cash payment, o pagbiling mano-manong tinype.";
                case "caregiver": return "Tagapag-alaga";
                case "locked": return "Naka-lock";
                case "locked_body": return "Ilagay ang PIN bago baguhin ang setup.";
                case "default_pin_title": return "Unang setup";
                case "default_pin_body": return "Ang default PIN ay 1234. I-unlock, pagkatapos pumili ng bagong PIN.";
                case "unlocked": return "Na-unlock";
                case "unlocked_body": return "Maaaring baguhin ang setup sa loob ng 5 minuto.";
                case "caregiver_pin": return "PIN ng tagapag-alaga";
                case "unlock": return "I-unlock ang setup";
                case "forgot_pin_body": return "Nakalimutan ang PIN? I-clear ang data ng app na ito at i-set up muli.";
                case "setup_step": return "Hakbang";
                case "pin_step": return "Palitan ang PIN";
                case "pin_step_body": return "Gumamit ng PIN na hindi alam ng senior.";
                case "current_pin": return "Kasalukuyang PIN";
                case "new_pin": return "Bagong PIN";
                case "confirm_pin": return "Kumpirmahin ang PIN";
                case "save_pin": return "I-save ang PIN";
                case "autofill_step": return "I-on ang Senior Spending Guard Autofill";
                case "autofill_step_body": return "Piliin ang app na ito bilang autofill provider ng Android.";
                case "autofill_on": return "Naka-on ang Autofill";
                case "autofill_on_body": return "Dadaan na sa PIN ng tagapag-alaga ang payment autofill.";
                case "open_autofill": return "Buksan ang Autofill";
                case "replace_autofill": return "Palitan ang provider";
                case "chrome_autofill_step": return "I-on ang Chrome Autofill";
                case "chrome_autofill_body": return "Gawing gamitin ng Chrome ang Senior Spending Guard Autofill na pinili sa Android sa halip na default Autofill ng Chrome.";
                case "chrome_settings_guide_title": return "1. Buksan ang Mga serbisyo ng autofill";
                case "chrome_settings_guide_body": return "Buksan ang Chrome, tapikin ang menu sa kanang itaas, pagkatapos Settings. Sa Settings, piliin ang Mga serbisyo ng autofill.";
                case "chrome_options_guide_title": return "2. Piliin ang Autofill gamit ang ibang serbisyo";
                case "chrome_options_guide_body": return "Sa page ng Mga serbisyo ng autofill, piliin ang Autofill gamit ang ibang serbisyo.";
                case "chrome_options_mock_title": return "Mga opsyon sa autofill";
                case "chrome_options_mock_choice": return "Autofill gamit ang ibang serbisyo";
                case "open_chrome_autofill_options": return "Buksan ang Chrome options";
                case "open_chrome_test": return "Subukan sa Chrome";
                case "card_step": return "Aprubadong card";
                case "name_on_card": return "Pangalan sa card";
                case "card_number": return "Numero ng card";
                case "expiration": return "Expiration MM/YY";
                case "cvv": return "CVV";
                case "postal": return "ZIP/postal code";
                case "save_card": return "I-save ang card";
                case "clear_card": return "Burahin ang card";
                case "test_card_hint": return "Gumamit lang ng test, prepaid, o low-limit na card.";
                case "store_step": return "Mga setting sa labas";
                case "store_step_body": return "Hindi awtomatikong mababago ng app ang mga screen na ito.";
                case "chrome_button": return "Buksan ang Chrome";
                case "play_button": return "Buksan ang Play Store";
                case "merchant_apps_hint": return "Alisin ang naka-save na cards sa shopping, food, ride, game, at subscription apps.";
                case "finish_step": return "Handa na";
                case "finish_step_body": return "Kailangan ang PIN ng tagapag-alaga sa bawat card autofill.";
                case "spending_history_title": return "Kasaysayan ng pagbili";
                case "spending_history_body": return "Gamitin ang bank o card app para sa tunay na singil. Hindi pinapalitan ng app na ito ang history na iyon.";
                case "back": return "Bumalik";
                case "next": return "Susunod";
                case "done": return "Tapos";
                case "incorrect_pin": return "Maling PIN.";
                case "pin_locked": return "Masyadong maraming subok. Maghintay ng 5 minuto.";
                case "unlocked_toast": return "Na-unlock ang setup sa loob ng 5 minuto.";
                case "current_pin_wrong": return "Mali ang kasalukuyang PIN.";
                case "pin_rules": return "Gumamit ng hindi bababa sa 4 na digit.";
                case "pin_mismatch": return "Hindi magkatugma ang mga PIN.";
                case "pin_saved": return "Na-save ang PIN.";
                case "card_saved": return "Ligtas na na-save ang card.";
                case "card_cleared": return "Nabura ang card.";
                case "enter_name": return "Ilagay ang pangalan sa card.";
                case "enter_card_number": return "Ilagay ang valid na numero ng card.";
                case "enter_expiration": return "Ilagay ang valid na future expiration bilang MM/YY.";
                case "enter_cvv": return "Ilagay ang valid na 3 o 4 digit na CVV.";
                case "enter_postal": return "Ilagay ang valid na ZIP o postal code.";
                case "autofill_settings_fallback": return "Piliin ang Senior Spending Guard Autofill sa Passwords and autofill.";
                case "chrome_fallback": return "Buksan ang payment methods at i-off ang saved-card filling.";
                case "chrome_autofill_steps": return "Chrome > Settings > Mga serbisyo ng autofill > Autofill gamit ang ibang serbisyo. I-restart ang Chrome pagkatapos baguhin.";
                case "play_steps": return "Profile picture > Payments & subscriptions > Purchase verification.";
                case "play_signin": return "Mag-sign in muna sa Play Store kung wala ang purchase verification.";
            }
        }
        if (isTraditionalChinese()) {
            switch (key) {
                case "language_button": return "繁體中文";
                case "language_section": return "語言";
                case "selected_language_label": return "目前語言";
                case "app_title": return "長者消費保護";
                case "senior_mode": return "長者";
                case "caregiver_mode": return "照護者";
                case "senior_status": return "狀態";
                case "protected_title": return "已保護";
                case "protected_body": return "付款自動填入需要照護者核准。";
                case "needs_setup_title": return "需要設定";
                case "needs_setup_body": return "請照護者完成設定。";
                case "autofill": return "自動填入";
                case "card": return "卡片";
                case "on": return "開啟";
                case "off": return "關閉";
                case "no_card": return "未儲存卡片";
                case "buying_rule": return "購買前";
                case "buying_rule_body": return "如果應用程式要求付款或儲存卡片，請先聯絡照護者。";
                case "scam_limit_title": return "重要限制";
                case "scam_limit_body": return "本應用程式只能降低誤用卡片自動填入和誤付款的風險，無法阻止所有詐騙、電話誘導、禮品卡要求、銀行轉帳、現金付款或手動輸入的購買。";
                case "caregiver": return "照護者";
                case "locked": return "已鎖定";
                case "locked_body": return "輸入 PIN 後才能更改設定。";
                case "default_pin_title": return "首次設定";
                case "default_pin_body": return "預設 PIN 是 1234。解鎖後請設定新的 PIN。";
                case "unlocked": return "已解鎖";
                case "unlocked_body": return "你可以在 5 分鐘內更改設定。";
                case "caregiver_pin": return "照護者 PIN";
                case "unlock": return "解鎖設定";
                case "forgot_pin_body": return "忘記 PIN？清除本應用程式的資料，然後重新設定。";
                case "setup_step": return "步驟";
                case "pin_step": return "更改照護者 PIN";
                case "pin_step_body": return "使用長者不知道的 PIN。";
                case "current_pin": return "目前 PIN";
                case "new_pin": return "新 PIN";
                case "confirm_pin": return "確認 PIN";
                case "save_pin": return "儲存 PIN";
                case "autofill_step": return "開啟照護者自動填入";
                case "autofill_step_body": return "在 Android 中選擇本應用程式作為自動填入服務。";
                case "autofill_on": return "自動填入已開啟";
                case "autofill_on_body": return "付款自動填入會先要求照護者 PIN。";
                case "open_autofill": return "開啟自動填入";
                case "replace_autofill": return "更換服務";
                case "chrome_autofill_step": return "開啟 Chrome 自動填入";
                case "chrome_autofill_body": return "讓 Chrome 使用 Android 中選擇的 Senior Spending Guard Autofill，而不是 Chrome 預設的自動填入。";
                case "chrome_settings_guide_title": return "1. 開啟自動填入服務";
                case "chrome_settings_guide_body": return "開啟 Chrome，點右上角選單，然後點設定。在設定中選擇自動填入服務。";
                case "chrome_options_guide_title": return "2. 選擇使用其他自動填入服務";
                case "chrome_options_guide_body": return "在自動填入服務頁面選擇使用其他自動填入服務。";
                case "chrome_options_mock_title": return "自動填入選項";
                case "chrome_options_mock_choice": return "使用其他自動填入服務";
                case "open_chrome_autofill_options": return "開啟 Chrome 選項";
                case "open_chrome_test": return "在 Chrome 中測試";
                case "card_step": return "核准的卡片";
                case "name_on_card": return "持卡人姓名";
                case "card_number": return "卡號";
                case "expiration": return "有效期限 MM/YY";
                case "cvv": return "CVV";
                case "postal": return "郵遞區號";
                case "save_card": return "儲存卡片";
                case "clear_card": return "清除卡片";
                case "test_card_hint": return "只使用測試卡、預付卡或低額度卡片。";
                case "store_step": return "外部設定";
                case "store_step_body": return "這些畫面無法由本應用程式自動更改。";
                case "chrome_button": return "開啟 Chrome";
                case "play_button": return "開啟 Play 商店";
                case "merchant_apps_hint": return "從購物、外送、叫車、遊戲和訂閱應用程式中移除已儲存的卡片。";
                case "finish_step": return "準備好了";
                case "finish_step_body": return "每次卡片自動填入都需要照護者 PIN。";
                case "spending_history_title": return "消費紀錄";
                case "spending_history_body": return "真實扣款請查看銀行或信用卡應用程式。本應用程式不能取代帳單紀錄。";
                case "back": return "返回";
                case "next": return "下一步";
                case "done": return "完成";
                case "incorrect_pin": return "PIN 不正確。";
                case "pin_locked": return "嘗試次數過多。請等待 5 分鐘。";
                case "unlocked_toast": return "設定已解鎖 5 分鐘。";
                case "current_pin_wrong": return "目前 PIN 不正確。";
                case "pin_rules": return "請使用至少 4 位數字。";
                case "pin_mismatch": return "兩次輸入的 PIN 不一致。";
                case "pin_saved": return "PIN 已儲存。";
                case "card_saved": return "卡片已安全儲存。";
                case "card_cleared": return "卡片已清除。";
                case "enter_name": return "請輸入持卡人姓名。";
                case "enter_card_number": return "請輸入有效卡號。";
                case "enter_expiration": return "請輸入有效的未來有效期限，格式 MM/YY。";
                case "enter_cvv": return "請輸入 3 或 4 位 CVV。";
                case "enter_postal": return "請輸入有效郵遞區號。";
                case "autofill_settings_fallback": return "在 Passwords and autofill 中選擇 Senior Spending Guard Autofill。";
                case "chrome_fallback": return "開啟付款方式並關閉已儲存卡片填入。";
                case "chrome_autofill_steps": return "Chrome > 設定 > 自動填入服務 > 使用其他自動填入服務。更改後重新啟動 Chrome。";
                case "play_steps": return "頭像 > Payments & subscriptions > Purchase verification。";
                case "play_signin": return "如果看不到購買驗證，請先登入 Play 商店。";
            }
        }
        if (isChinese()) {
            switch (key) {
                case "language_button": return "English";
                case "language_section": return "语言";
                case "selected_language_label": return "当前语言";
                case "app_title": return "长者消费保护";
                case "senior_mode": return "长者";
                case "caregiver_mode": return "照护者";
                case "senior_status": return "状态";
                case "protected_title": return "已保护";
                case "protected_body": return "银行卡自动填充需要照护者批准。";
                case "needs_setup_title": return "需要设置";
                case "needs_setup_body": return "请照护者完成设置。";
                case "autofill": return "自动填充";
                case "card": return "银行卡";
                case "on": return "开启";
                case "off": return "关闭";
                case "no_card": return "未保存银行卡";
                case "buying_rule": return "付款前";
                case "buying_rule_body": return "如果应用要求付款或保存银行卡，请先联系照护者。";
                case "scam_limit_title": return "重要提醒";
                case "scam_limit_body": return "本应用只能降低误填银行卡和误付款的风险，不能阻止所有诈骗、电话诱导、礼品卡骗局、转账、现金付款或照护者手动输入付款信息。";
                case "caregiver": return "照护者";
                case "locked": return "已锁定";
                case "locked_body": return "输入 PIN 后才能更改设置。";
                case "default_pin_title": return "首次设置";
                case "default_pin_body": return "默认 PIN 是 1234。解锁后请设置新的 PIN。";
                case "unlocked": return "已解锁";
                case "unlocked_body": return "你可以在 5 分钟内更改设置。";
                case "caregiver_pin": return "照护者 PIN";
                case "unlock": return "解锁设置";
                case "forgot_pin_body": return "忘记 PIN？清除本应用的数据，然后重新设置。";
                case "setup_step": return "步骤";
                case "pin_step": return "更改照护者 PIN";
                case "pin_step_body": return "使用长者不知道的 PIN。";
                case "current_pin": return "当前 PIN";
                case "new_pin": return "新 PIN";
                case "confirm_pin": return "确认 PIN";
                case "save_pin": return "保存 PIN";
                case "autofill_step": return "开启照护者自动填充";
                case "autofill_step_body": return "在 Android 中选择本应用作为自动填充服务。";
                case "autofill_on": return "自动填充已开启";
                case "autofill_on_body": return "付款自动填充会先要求照护者 PIN。";
                case "open_autofill": return "打开自动填充设置";
                case "replace_autofill": return "更换服务";
                case "chrome_autofill_step": return "开启 Chrome 自动填充";
                case "chrome_autofill_body": return "让 Chrome 使用 Android 中选择的 Senior Spending Guard Autofill，而不是 Chrome 默认的自动填充。";
                case "chrome_settings_guide_title": return "1. 打开自动填充服务";
                case "chrome_settings_guide_body": return "打开 Chrome，点右上角菜单，然后点设置。在设置中选择自动填充服务。";
                case "chrome_options_guide_title": return "2. 选择使用其他自动填充服务";
                case "chrome_options_guide_body": return "在自动填充服务页面选择使用其他自动填充服务。";
                case "chrome_options_mock_title": return "自动填充选项";
                case "chrome_options_mock_choice": return "使用其他自动填充服务";
                case "open_chrome_autofill_options": return "打开 Chrome 选项";
                case "open_chrome_test": return "在 Chrome 中测试";
                case "card_step": return "批准的银行卡";
                case "name_on_card": return "持卡人姓名";
                case "card_number": return "卡号";
                case "expiration": return "有效期 MM/YY";
                case "cvv": return "CVV";
                case "postal": return "邮编";
                case "save_card": return "保存银行卡";
                case "clear_card": return "清除银行卡";
                case "test_card_hint": return "只使用测试卡、预付卡或低额度银行卡。";
                case "store_step": return "外部设置";
                case "store_step_body": return "这些设置不能由本应用自动更改。";
                case "chrome_button": return "打开 Chrome";
                case "play_button": return "打开 Play 商店";
                case "merchant_apps_hint": return "从购物、外卖、打车、游戏和订阅应用中移除已保存的银行卡。";
                case "finish_step": return "准备好了";
                case "finish_step_body": return "每次银行卡自动填充都需要照护者 PIN。";
                case "spending_history_title": return "消费记录";
                case "spending_history_body": return "真实扣款请查看银行或信用卡应用。本应用不能替代账单记录。";
                case "back": return "返回";
                case "next": return "下一步";
                case "done": return "完成";
                case "incorrect_pin": return "PIN 不正确。";
                case "pin_locked": return "尝试次数过多。请等待 5 分钟。";
                case "unlocked_toast": return "设置已解锁 5 分钟。";
                case "current_pin_wrong": return "当前 PIN 不正确。";
                case "pin_rules": return "请使用至少 4 位数字。";
                case "pin_mismatch": return "两次输入的 PIN 不一致。";
                case "pin_saved": return "PIN 已保存。";
                case "card_saved": return "银行卡已安全保存。";
                case "card_cleared": return "银行卡已清除。";
                case "enter_name": return "请输入持卡人姓名。";
                case "enter_card_number": return "请输入有效卡号。";
                case "enter_expiration": return "请输入有效的未来有效期，格式 MM/YY。";
                case "enter_cvv": return "请输入 3 或 4 位 CVV。";
                case "enter_postal": return "请输入有效邮编。";
                case "autofill_settings_fallback": return "在密码和自动填充中选择 Senior Spending Guard Autofill。";
                case "chrome_fallback": return "打开付款方式并关闭已保存银行卡填充。";
                case "chrome_autofill_steps": return "Chrome > 设置 > 自动填充服务 > 使用其他自动填充服务。更改后重启 Chrome。";
                case "play_steps": return "头像 > Payments & subscriptions > Purchase verification。";
                case "play_signin": return "如果看不到购买验证，请先登录 Play 商店。";
            }
        }
        if (isSpanish()) {
            switch (key) {
                case "language_button": return "中文";
                case "language_section": return "Idioma";
                case "selected_language_label": return "Idioma seleccionado";
                case "app_title": return "Protector de gastos";
                case "senior_mode": return "Persona mayor";
                case "caregiver_mode": return "Cuidador";
                case "senior_status": return "Estado";
                case "protected_title": return "Protegido";
                case "protected_body": return "Autofill de pagos requiere aprobacion del cuidador.";
                case "needs_setup_title": return "Falta configuracion";
                case "needs_setup_body": return "Pide al cuidador que termine la configuracion.";
                case "autofill": return "Autofill";
                case "card": return "Tarjeta";
                case "on": return "Activo";
                case "off": return "Inactivo";
                case "no_card": return "Sin tarjeta";
                case "buying_rule": return "Antes de comprar";
                case "buying_rule_body": return "Llama al cuidador si una app pide pagar o guardar una tarjeta.";
                case "scam_limit_title": return "Aviso importante";
                case "scam_limit_body": return "Esta app reduce el riesgo de pagos accidentales y autofill de tarjetas. No puede detener todas las estafas, llamadas de presion, tarjetas de regalo, transferencias, efectivo o pagos escritos manualmente.";
                case "caregiver": return "Cuidador";
                case "locked": return "Bloqueado";
                case "locked_body": return "Ingresa el PIN para cambiar la configuracion.";
                case "default_pin_title": return "Primera configuracion";
                case "default_pin_body": return "El PIN predeterminado es 1234. Desbloquea y luego elige un PIN nuevo.";
                case "unlocked": return "Desbloqueado";
                case "unlocked_body": return "Puedes cambiar la configuracion por 5 minutos.";
                case "caregiver_pin": return "PIN del cuidador";
                case "unlock": return "Desbloquear";
                case "forgot_pin_body": return "Si olvidas el PIN, borra los datos de la app y configuralo otra vez.";
                case "setup_step": return "Paso";
                case "pin_step": return "Cambiar PIN";
                case "pin_step_body": return "Usa un PIN que la persona mayor no conozca.";
                case "current_pin": return "PIN actual";
                case "new_pin": return "PIN nuevo";
                case "confirm_pin": return "Confirmar PIN";
                case "save_pin": return "Guardar PIN";
                case "autofill_step": return "Activar Autofill cuidador";
                case "autofill_step_body": return "Selecciona esta app como proveedor de Autofill.";
                case "autofill_on": return "Autofill activo";
                case "autofill_on_body": return "Los pagos pasan por el PIN del cuidador.";
                case "open_autofill": return "Abrir Autofill";
                case "replace_autofill": return "Cambiar proveedor";
                case "chrome_autofill_step": return "Activar Chrome";
                case "chrome_autofill_body": return "Haz que Chrome use Senior Spending Guard Autofill, el proveedor elegido en Android, en lugar del Autofill predeterminado de Chrome.";
                case "chrome_settings_guide_title": return "1. Abrir Servicios de autocompletado";
                case "chrome_settings_guide_body": return "Abre Chrome, toca el menu de arriba a la derecha y entra a Configuracion. En Configuracion, elige Servicios de autocompletado.";
                case "chrome_options_guide_title": return "2. Elegir Autocompletar con otro servicio";
                case "chrome_options_guide_body": return "En la pagina Servicios de autocompletado, elige Autocompletar con otro servicio.";
                case "chrome_options_mock_title": return "Opciones de autocompletar";
                case "chrome_options_mock_choice": return "Autocompletar con otro servicio";
                case "open_chrome_autofill_options": return "Abrir opciones de Chrome";
                case "open_chrome_test": return "Probar en Chrome";
                case "card_step": return "Tarjeta aprobada";
                case "name_on_card": return "Nombre en la tarjeta";
                case "card_number": return "Numero de tarjeta";
                case "expiration": return "Vence MM/AA";
                case "cvv": return "CVV";
                case "postal": return "Codigo postal";
                case "save_card": return "Guardar tarjeta";
                case "clear_card": return "Borrar tarjeta";
                case "test_card_hint": return "Usa solo tarjeta de prueba, prepago o bajo limite.";
                case "store_step": return "Ajustes externos";
                case "store_step_body": return "Estas pantallas no se pueden cambiar automaticamente.";
                case "chrome_button": return "Abrir Chrome";
                case "play_button": return "Abrir Play Store";
                case "merchant_apps_hint": return "Quita tarjetas guardadas en tiendas, comida, viajes y juegos.";
                case "finish_step": return "Listo";
                case "finish_step_body": return "Cada autofill de tarjeta requiere el PIN del cuidador.";
                case "spending_history_title": return "Historial de compras";
                case "spending_history_body": return "Usa la app del banco o tarjeta para ver cargos reales. Esta app no reemplaza ese historial.";
                case "back": return "Atras";
                case "next": return "Siguiente";
                case "done": return "Listo";
                case "chrome_autofill_steps": return "Chrome > Configuracion > Servicios de autocompletado > Autocompletar con otro servicio. Reinicia Chrome despues.";
            }
        }

        switch (key) {
            case "language_button": return "Espanol";
            case "language_section": return "Language";
            case "selected_language_label": return "Selected";
            case "app_title": return "Senior Spending Guard";
            case "senior_mode": return "Senior";
            case "caregiver_mode": return "Caregiver";
            case "senior_status": return "Status";
            case "protected_title": return "Protected";
            case "protected_body": return "Payment autofill requires caregiver approval.";
            case "needs_setup_title": return "Setup needed";
            case "needs_setup_body": return "Ask the caregiver to finish setup.";
            case "autofill": return "Autofill";
            case "card": return "Card";
            case "on": return "On";
            case "off": return "Off";
            case "no_card": return "No card";
            case "buying_rule": return "Before buying";
            case "buying_rule_body": return "Call the caregiver if an app asks to pay or save a card.";
            case "scam_limit_title": return "Important limit";
            case "scam_limit_body": return "This app lowers the risk of accidental card autofill and accidental spending. It cannot stop every scam, pressure call, gift-card request, bank transfer, cash payment, or manually typed purchase.";
            case "caregiver": return "Caregiver";
            case "locked": return "Locked";
            case "locked_body": return "Enter the PIN before changing setup.";
            case "default_pin_title": return "First-time setup";
            case "default_pin_body": return "The default PIN is 1234. Unlock setup, then choose a new PIN.";
            case "unlocked": return "Unlocked";
            case "unlocked_body": return "Setup changes are available for 5 minutes.";
            case "caregiver_pin": return "Caregiver PIN";
            case "unlock": return "Unlock setup";
            case "forgot_pin_body": return "Forgot the PIN? Clear this app's data and set it up again.";
            case "setup_step": return "Step";
            case "pin_step": return "Change caregiver PIN";
            case "pin_step_body": return "Use a PIN the senior does not know.";
            case "current_pin": return "Current PIN";
            case "new_pin": return "New PIN";
            case "confirm_pin": return "Confirm PIN";
            case "save_pin": return "Save PIN";
            case "autofill_step": return "Turn on Senior Spending Guard Autofill";
            case "autofill_step_body": return "Choose this app as Android's autofill provider.";
            case "autofill_on": return "Autofill is on";
            case "autofill_on_body": return "Payment autofill now goes through the caregiver PIN.";
            case "open_autofill": return "Open Autofill";
            case "replace_autofill": return "Replace provider";
            case "chrome_autofill_step": return "Turn on Chrome Autofill";
            case "chrome_autofill_body": return "Make Chrome use Senior Spending Guard Autofill, the provider selected in Android, instead of Chrome's default Autofill.";
            case "chrome_settings_guide_title": return "1. Open Chrome Autofill services";
            case "chrome_settings_guide_body": return "Open Chrome, tap the top-right menu, then Settings. In Settings, choose Autofill services.";
            case "chrome_options_guide_title": return "2. Choose Autofill using another service";
            case "chrome_options_guide_body": return "On the Autofill services page, choose Autofill using another service.";
            case "chrome_options_mock_title": return "Autofill services";
            case "chrome_options_mock_choice": return "Autofill using another service";
            case "open_chrome_autofill_options": return "Open Chrome options";
            case "open_chrome_test": return "Test in Chrome";
            case "card_step": return "Approved card";
            case "name_on_card": return "Name on card";
            case "card_number": return "Card number";
            case "expiration": return "Expiration MM/YY";
            case "cvv": return "CVV";
            case "postal": return "ZIP/postal code";
            case "save_card": return "Save card";
            case "clear_card": return "Clear card";
            case "test_card_hint": return "Use only a test, prepaid, or low-limit card.";
            case "store_step": return "Outside settings";
            case "store_step_body": return "These screens cannot be changed automatically.";
            case "chrome_button": return "Open Chrome";
            case "play_button": return "Open Play Store";
            case "merchant_apps_hint": return "Remove saved cards from shopping, food, ride, game, and subscription apps.";
            case "finish_step": return "Ready";
            case "finish_step_body": return "Every card autofill requires the caregiver PIN.";
            case "spending_history_title": return "Purchase history";
            case "spending_history_body": return "Use the bank or card app for real charges. This app does not replace that history.";
            case "back": return "Back";
            case "next": return "Next";
            case "done": return "Done";
            case "incorrect_pin": return "Incorrect PIN.";
            case "pin_locked": return "Too many tries. Wait 5 minutes.";
            case "unlocked_toast": return "Setup unlocked for 5 minutes.";
            case "current_pin_wrong": return "Current PIN is wrong.";
            case "pin_rules": return "Use at least 4 digits.";
            case "pin_mismatch": return "PINs do not match.";
            case "pin_saved": return "PIN saved.";
            case "card_saved": return "Card saved securely.";
            case "card_cleared": return "Card cleared.";
            case "enter_name": return "Enter the name on the card.";
            case "enter_card_number": return "Enter a valid card number.";
            case "enter_expiration": return "Enter a valid future expiration as MM/YY.";
            case "enter_cvv": return "Enter a valid 3 or 4 digit CVV.";
            case "enter_postal": return "Enter a valid ZIP or postal code.";
            case "autofill_settings_fallback": return "Choose Senior Spending Guard Autofill under Passwords and autofill.";
            case "chrome_fallback": return "Open payment methods and turn off saved-card filling.";
            case "chrome_autofill_steps": return "Chrome > Settings > Autofill services > Autofill using another service. Restart Chrome after changing it.";
            case "play_steps": return "Profile picture > Payments & subscriptions > Purchase verification.";
            case "play_signin": return "Sign in to Play Store first if purchase verification is missing.";
        }
        return key;
    }
}
