package li.vinkent.seniorpaymentguard;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.service.autofill.AutofillService;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PaymentGuardAutofillService extends AutofillService {
    private static final String TAG = "CaregiverAutofill";
    private static final int MAX_FIELDS = 16;
    static final String EXTRA_AUTOFILL_IDS = "autofill_ids";
    static final String EXTRA_AUTOFILL_ROLES = "autofill_roles";
    private static PendingFill pendingFill;

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        if (request == null || cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }

        List<FillContext> contexts = request.getFillContexts();
        if (contexts == null || contexts.isEmpty()) {
            callback.onSuccess(null);
            return;
        }

        ArrayList<Field> fields = new ArrayList<>();
        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        String sourcePackage = structure != null && structure.getActivityComponent() != null
                ? structure.getActivityComponent().getPackageName()
                : "";
        if (getPackageName().equals(sourcePackage)) {
            callback.onSuccess(null);
            return;
        }
        collectPaymentFields(structure, fields);
        Log.d(TAG, "Fill request from " + sourcePackage + " found " + fields.size() + " payment fields");

        if (fields.isEmpty()) {
            callback.onSuccess(null);
            return;
        }

        if (!MainActivity.hasSavedCard(this)) {
            callback.onSuccess(buildSetupResponse(fields));
            return;
        }

        pendingFill = new PendingFill(fields);

        Intent authIntent = new Intent(this, AutofillApprovalActivity.class);
        authIntent.putParcelableArrayListExtra(EXTRA_AUTOFILL_IDS, fieldIds(fields));
        authIntent.putStringArrayListExtra(EXTRA_AUTOFILL_ROLES, fieldRoles(fields));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                2001,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        AutofillId[] ids = autofillIds(fields);
        callback.onSuccess(new FillResponse.Builder()
                .addDataset(buildLockedDataset(fields, pendingIntent))
                .build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();
    }

    static Dataset buildApprovedDataset(Context context) {
        return buildApprovedDataset(context, pendingFill == null ? null : pendingFill.fields);
    }

    static FillResponse buildApprovedResponse(Context context, ArrayList<AutofillId> ids, ArrayList<String> roles) {
        Dataset dataset = buildApprovedDataset(context, ids, roles);
        if (dataset == null) {
            return null;
        }
        return new FillResponse.Builder().addDataset(dataset).build();
    }

    static Dataset buildApprovedDataset(Context context, ArrayList<AutofillId> ids, ArrayList<String> roles) {
        if (pendingFill != null && pendingFill.fields != null && !pendingFill.fields.isEmpty()) {
            return buildApprovedDataset(context);
        }
        if (ids == null || roles == null || ids.isEmpty() || ids.size() != roles.size()) {
            return buildApprovedDataset(context);
        }

        ArrayList<Field> fields = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            fields.add(new Field(ids.get(i), FieldRole.fromName(roles.get(i))));
        }
        return buildApprovedDataset(context, fields);
    }

    private static Dataset buildApprovedDataset(Context context, ArrayList<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }

        if (!MainActivity.hasSavedCard(context)) {
            return null;
        }

        RemoteViews presentation = presentation(context, "Caregiver card approved");
        Dataset.Builder dataset = new Dataset.Builder(presentation);
        for (Field field : fields) {
            String value = valueForRole(field.role, context);
            if (!value.isEmpty()) {
                dataset.setValue(field.id, AutofillValue.forText(value), presentation);
            }
        }
        pendingFill = null;
        return dataset.build();
    }

    private ArrayList<AutofillId> fieldIds(ArrayList<Field> fields) {
        ArrayList<AutofillId> ids = new ArrayList<>();
        for (Field field : fields) {
            ids.add(field.id);
        }
        return ids;
    }

    private ArrayList<String> fieldRoles(ArrayList<Field> fields) {
        ArrayList<String> roles = new ArrayList<>();
        for (Field field : fields) {
            roles.add(field.role.name());
        }
        return roles;
    }

    private FillResponse buildSetupResponse(ArrayList<Field> fields) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                2002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new FillResponse.Builder()
                .setAuthentication(autofillIds(fields), pendingIntent.getIntentSender(), presentation("Caregiver Autofill active: set up card"))
                .build();
    }

    private Dataset buildLockedDataset(ArrayList<Field> fields, PendingIntent pendingIntent) {
        RemoteViews locked = presentation("Caregiver PIN required");
        Dataset.Builder dataset = new Dataset.Builder(locked);
        for (Field field : fields) {
            dataset.setValue(field.id, null, locked);
        }
        dataset.setAuthentication(pendingIntent.getIntentSender());
        return dataset.build();
    }

    private AutofillId[] autofillIds(ArrayList<Field> fields) {
        AutofillId[] ids = new AutofillId[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            ids[i] = fields.get(i).id;
        }
        return ids;
    }

    private void collectPaymentFields(AssistStructure structure, ArrayList<Field> fields) {
        if (structure == null) {
            return;
        }
        for (int i = 0; i < structure.getWindowNodeCount() && fields.size() < MAX_FIELDS; i++) {
            AssistStructure.WindowNode window = structure.getWindowNodeAt(i);
            if (window != null) {
                collectPaymentFields(window.getRootViewNode(), fields);
            }
        }
    }

    private void collectPaymentFields(AssistStructure.ViewNode node, ArrayList<Field> fields) {
        if (node == null || fields.size() >= MAX_FIELDS) {
            return;
        }

        FieldRole role = getFieldRole(node);
        if (node.getAutofillId() != null
                && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT
                && role != FieldRole.UNKNOWN) {
            fields.add(new Field(node.getAutofillId(), role));
        }

        for (int i = 0; i < node.getChildCount() && fields.size() < MAX_FIELDS; i++) {
            collectPaymentFields(node.getChildAt(i), fields);
        }
    }

    private FieldRole getFieldRole(AssistStructure.ViewNode node) {
        String text = searchableText(node);
        if (containsAny(text, "cvv", "cvc", "security code", "security_code", "creditcardsecuritycode")) {
            return FieldRole.CVV;
        }
        if (containsAny(text, "exp month", "expiration month", "expiry month", "cc-exp-month", "cc_exp_month", "creditcardexpirationmonth")) {
            return FieldRole.EXPIRATION_MONTH;
        }
        if (containsAny(text, "exp year", "expiration year", "expiry year", "cc-exp-year", "cc_exp_year", "creditcardexpirationyear")) {
            return FieldRole.EXPIRATION_YEAR;
        }
        if (containsAny(text, "exp", "expiry", "expiration", "cc-exp", "cc_exp", "creditcardexpiration")) {
            return FieldRole.EXPIRATION;
        }
        if (containsAny(text, "zip", "zip code", "postal", "postcode", "billing postal", "billing zip")) {
            return FieldRole.POSTAL;
        }
        if (containsAny(text, "name on card", "cardholder", "cardholder name", "creditcardname", "card name", "cc-name", "cc_name")) {
            return FieldRole.NAME;
        }
        if (containsAny(text, "card number", "cardnumber", "credit card number", "credit card", "cc-number", "cc_number", "ccnum", "creditcardnumber")) {
            return FieldRole.NUMBER;
        }
        return FieldRole.UNKNOWN;
    }

    private String searchableText(AssistStructure.ViewNode node) {
        StringBuilder builder = new StringBuilder();
        append(builder, node.getHint());
        append(builder, node.getText());
        append(builder, node.getIdEntry());
        append(builder, node.getContentDescription());
        append(builder, node.getClassName());
        String[] hints = node.getAutofillHints();
        if (hints != null) {
            for (String hint : hints) {
                append(builder, hint);
            }
        }
        ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();
        if (htmlInfo != null) {
            append(builder, htmlInfo.getTag());
            List<Pair<String, String>> attributes = htmlInfo.getAttributes();
            if (attributes != null) {
                for (Pair<String, String> attribute : attributes) {
                    if (attribute == null) {
                        continue;
                    }
                    append(builder, attribute.first);
                    append(builder, attribute.second);
                }
            }
        }
        return builder.toString().toLowerCase(Locale.US);
    }

    private void append(StringBuilder builder, CharSequence value) {
        if (value != null && value.length() > 0) {
            builder.append(value).append(' ');
        }
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String valueForRole(FieldRole role, Context context) {
        switch (role) {
            case NUMBER:
                return GuardStore.getCardNumber(context);
            case EXPIRATION:
                return GuardStore.getCardExpiration(context);
            case EXPIRATION_MONTH:
                return expirationPart(GuardStore.getCardExpiration(context), true);
            case EXPIRATION_YEAR:
                return expirationPart(GuardStore.getCardExpiration(context), false);
            case CVV:
                return GuardStore.getCardCvv(context);
            case NAME:
                return GuardStore.getCardName(context);
            case POSTAL:
                return GuardStore.getCardPostal(context);
            default:
                return "";
        }
    }

    private static String expirationPart(String expiration, boolean month) {
        if (expiration == null || expiration.length() != 5 || expiration.charAt(2) != '/') {
            return "";
        }
        return month ? expiration.substring(0, 2) : expiration.substring(3);
    }

    private RemoteViews presentation(String text) {
        return presentation(this, text);
    }

    private static RemoteViews presentation(Context context, String text) {
        RemoteViews presentation = new RemoteViews(context.getPackageName(), android.R.layout.simple_list_item_1);
        presentation.setTextViewText(android.R.id.text1, text);
        return presentation;
    }

    private static class PendingFill {
        final ArrayList<Field> fields;

        PendingFill(ArrayList<Field> fields) {
            this.fields = new ArrayList<>(fields);
        }
    }

    private static class Field {
        final AutofillId id;
        final FieldRole role;

        Field(AutofillId id, FieldRole role) {
            this.id = id;
            this.role = role;
        }
    }

    private enum FieldRole {
        UNKNOWN,
        NAME,
        NUMBER,
        EXPIRATION,
        EXPIRATION_MONTH,
        EXPIRATION_YEAR,
        CVV,
        POSTAL;

        static FieldRole fromName(String name) {
            if (name == null) {
                return UNKNOWN;
            }
            try {
                return FieldRole.valueOf(name);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }
}
