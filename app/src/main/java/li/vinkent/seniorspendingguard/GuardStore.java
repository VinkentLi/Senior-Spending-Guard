package li.vinkent.seniorspendingguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class GuardStore {
    private static final String KEY_ALIAS = "senior_spending_guard_card_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String SECURE_PREFIX = "secure_";
    private static final String FALLBACK_KEY = "local_crypto_key";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private GuardStore() {
    }

    static String getCardName(Context context) {
        return secureGet(context, MainActivity.PREF_CARD_NAME);
    }

    static String getCardNumber(Context context) {
        return secureGet(context, MainActivity.PREF_CARD_NUMBER);
    }

    static String getCardExpiration(Context context) {
        return secureGet(context, MainActivity.PREF_CARD_EXPIRATION);
    }

    static String getCardCvv(Context context) {
        return secureGet(context, MainActivity.PREF_CARD_CVV);
    }

    static String getCardPostal(Context context) {
        return secureGet(context, MainActivity.PREF_CARD_POSTAL);
    }

    static void saveCard(Context context, String name, String number, String expiration, String cvv, String postal) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putEncrypted(editor, MainActivity.PREF_CARD_NAME, name);
        putEncrypted(editor, MainActivity.PREF_CARD_NUMBER, number);
        putEncrypted(editor, MainActivity.PREF_CARD_EXPIRATION, expiration);
        putEncrypted(editor, MainActivity.PREF_CARD_CVV, cvv);
        putEncrypted(editor, MainActivity.PREF_CARD_POSTAL, postal);
        editor.remove(MainActivity.PREF_CARD_NAME)
                .remove(MainActivity.PREF_CARD_NUMBER)
                .remove(MainActivity.PREF_CARD_EXPIRATION)
                .remove(MainActivity.PREF_CARD_CVV)
                .remove(MainActivity.PREF_CARD_POSTAL)
                .apply();
    }

    static void clearCard(Context context) {
        prefs(context).edit()
                .remove(secureKey(MainActivity.PREF_CARD_NAME))
                .remove(secureKey(MainActivity.PREF_CARD_NUMBER))
                .remove(secureKey(MainActivity.PREF_CARD_EXPIRATION))
                .remove(secureKey(MainActivity.PREF_CARD_CVV))
                .remove(secureKey(MainActivity.PREF_CARD_POSTAL))
                .remove(MainActivity.PREF_CARD_NAME)
                .remove(MainActivity.PREF_CARD_NUMBER)
                .remove(MainActivity.PREF_CARD_EXPIRATION)
                .remove(MainActivity.PREF_CARD_CVV)
                .remove(MainActivity.PREF_CARD_POSTAL)
                .apply();
    }

    static boolean hasSavedCard(Context context) {
        return !TextUtils.isEmpty(getCardNumber(context))
                && !TextUtils.isEmpty(getCardExpiration(context));
    }

    static String cardSummary(Context context) {
        String number = getCardNumber(context);
        String expiration = getCardExpiration(context);
        if (TextUtils.isEmpty(number)) {
            return "No card saved";
        }
        String last4 = number.length() <= 4 ? number : number.substring(number.length() - 4);
        String brand = cardBrand(number);
        return brand + " ending in " + last4 + " | Exp " + expiration;
    }

    static String secureGet(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        String encrypted = prefs.getString(secureKey(key), "");
        if (!TextUtils.isEmpty(encrypted)) {
            String decrypted = decrypt(encrypted);
            if (decrypted != null) {
                return decrypted;
            }
        }
        return prefs.getString(key, "");
    }

    private static void putEncrypted(SharedPreferences.Editor editor, String key, String value) {
        String encrypted = encrypt(value == null ? "" : value);
        if (!TextUtils.isEmpty(encrypted)) {
            editor.putString(secureKey(key), encrypted);
        }
    }

    private static String secureKey(String key) {
        return SECURE_PREFIX + key;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    private static String encrypt(String plainText) {
        try {
            SecretKey key = getOrCreateKey();
            return "ks:" + encryptWithKey(key, plainText);
        } catch (Exception e) {
            try {
                return "fb:" + encryptWithKey(getOrCreateFallbackKey(), plainText);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static String decrypt(String encoded) {
        if (encoded.startsWith("ks:")) {
            return decryptWithKey(getKeyQuietly(), encoded.substring(3));
        }
        if (encoded.startsWith("fb:")) {
            return decryptWithKey(getFallbackKeyQuietly(), encoded.substring(3));
        }
        String legacy = decryptWithKey(getKeyQuietly(), encoded);
        return legacy == null ? decryptWithKey(getFallbackKeyQuietly(), encoded) : legacy;
    }

    private static String encryptWithKey(SecretKey key, String plainText) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decryptWithKey(SecretKey key, String encoded) {
        if (key == null) {
            return null;
        }
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey getKeyQuietly() {
        try {
            return getOrCreateKey();
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey getFallbackKeyQuietly() {
        try {
            return getOrCreateFallbackKey();
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
        if (entry != null) {
            return entry.getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    private static SecretKey getOrCreateFallbackKey() {
        SharedPreferences prefs = SeniorSpendingGuardApp.context().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String encoded = prefs.getString(FALLBACK_KEY, "");
        if (TextUtils.isEmpty(encoded)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            encoded = Base64.encodeToString(key, Base64.NO_WRAP);
            prefs.edit().putString(FALLBACK_KEY, encoded).apply();
        }
        return new javax.crypto.spec.SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "AES");
    }

    private static String cardBrand(String number) {
        if (number.startsWith("4")) {
            return "Visa";
        }
        if (number.startsWith("34") || number.startsWith("37")) {
            return "Amex";
        }
        if (number.startsWith("5")) {
            return "Mastercard";
        }
        if (number.startsWith("6")) {
            return "Discover";
        }
        return "Card";
    }
}
