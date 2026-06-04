package li.vinkent.seniorpaymentguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.text.InputType;
import android.view.Gravity;
import android.view.autofill.AutofillManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class AutofillApprovalActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setContentView(buildContent());
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = new TextView(this);
        title.setText("Caregiver Autofill");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        TextView body = new TextView(this);
        body.setText("This phone uses Caregiver Autofill instead of the default saved-card provider. Enter the caregiver PIN to fill the approved low-limit card one time.");
        body.setTextColor(Color.rgb(51, 65, 85));
        body.setTextSize(18);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.15f);

        EditText pinInput = new EditText(this);
        pinInput.setHint("Caregiver PIN");
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTextSize(22);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setSingleLine(true);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        TextView error = new TextView(this);
        error.setTextColor(Color.rgb(185, 28, 28));
        error.setTextSize(16);
        error.setGravity(Gravity.CENTER);

        Button fill = new Button(this);
        fill.setText("Fill card once");
        fill.setAllCaps(false);
        fill.setTextSize(18);

        Runnable fillAction = () -> {
            if (!MainActivity.canTryPin(this)) {
                error.setText("Too many tries. Wait 5 minutes.");
                return;
            }
            if (!MainActivity.isCorrectPin(this, pinInput.getText().toString())) {
                MainActivity.recordPinFailure(this);
                error.setText("Incorrect PIN");
                return;
            }
            MainActivity.clearPinFailures(this);

            ArrayList<android.view.autofill.AutofillId> ids =
                    getIntent().getParcelableArrayListExtra(PaymentGuardAutofillService.EXTRA_AUTOFILL_IDS);
            ArrayList<String> roles =
                    getIntent().getStringArrayListExtra(PaymentGuardAutofillService.EXTRA_AUTOFILL_ROLES);
            Dataset dataset = PaymentGuardAutofillService.buildApprovedDataset(this, ids, roles);
            if (dataset == null) {
                Toast.makeText(this, "Set up a card profile first.", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
                return;
            }

            Intent result = new Intent();
            result.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset);
            setResult(RESULT_OK, result);
            finish();
        };
        fill.setOnClickListener(v -> fillAction.run());
        pinInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                fillAction.run();
                return true;
            }
            return false;
        });

        Button cancel = new Button(this);
        cancel.setText("Not now");
        cancel.setAllCaps(false);
        cancel.setTextSize(18);
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        root.addView(title, matchWrap());
        root.addView(body, matchWrap());
        root.addView(pinInput, matchWrap());
        root.addView(error, matchWrap());
        root.addView(fill, matchWrap());
        root.addView(cancel, matchWrap());
        return root;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
