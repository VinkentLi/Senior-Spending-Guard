# Senior Spending Guard

Senior Spending Guard is an Android prototype for reducing accidental spending by older adults. The product model is setup-first: replace the default saved-card autofill path, remove one-tap money paths, add caregiver rules, and keep optional caregiver-approved card filling for convenience.

The app does not try to detect or block every checkout screen. That approach is brittle and creates false positives. Instead, it helps the caregiver harden the device and, optionally, fill a prepaid or low-limit card only after PIN approval.

## What This Prototype Does

- Shows a guided setup assistant for the real causes of accidental spending.
- Guides caregivers to Chrome payment settings and Google Play purchase authentication.
- Makes `Caregiver Autofill` the first setup step because Android can let the caregiver replace the default autofill provider.
- Tracks checklist progress locally.
- Stores a caregiver PIN, default `1234`.
- Registers an optional Android Autofill Service named `Caregiver Autofill`.
- Lets a caregiver save a low-limit card profile for convenience.
- Requires the caregiver PIN before the saved card is filled.

Do not save a primary real card in this prototype. Use a test, prepaid, or low-limit card.

## What It No Longer Does

- No Accessibility Service.
- No purchase-screen watcher.
- No manual-entry blocker.
- No repeated Home/back actions.
- No claim that every payment flow can be blocked.

## Build

Use Android Studio's bundled JBR:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install to the current emulator:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Launch:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n li.vinkent.seniorpaymentguard/.MainActivity
```

## What Can Be Automatic

Android does not let a normal app silently change Chrome, Google Play, bank, or merchant-app payment settings. The app can open the closest settings screen and show exact steps, but the caregiver must confirm those changes.

Android does let the caregiver choose this app as the default Autofill Service. Once selected, the normal Google/Chrome saved-card provider is no longer the active provider. Payment autofill suggestions then come from Caregiver Autofill and require the caregiver PIN.

## Recommended Setup

1. Open Senior Spending Guard.
2. Tap `Choose provider` and select `Caregiver Autofill`.
3. Set a caregiver PIN.
4. Optional: save a prepaid or low-limit card profile.
5. Work through the setup assistant.
6. Remove or restrict saved cards in Chrome.
7. Require Google Play purchase verification. In the Play Store app, tap profile picture, then `Payments & subscriptions`, then `Purchase verification`. Choose `Always`, or choose `All payment methods` if your account shows payment-method based verification.
8. Remove saved payment methods from high-risk merchant apps.
9. Turn on bank alerts for every charge and declined transaction.

## Autofill Model

Caregiver Autofill is the most automatic protection this app can offer.

When Android sends the app a payment form through the Autofill Framework, the service detects likely card fields. If a card profile exists, Android shows a dataset labeled `Ask caregiver to fill card`. Selecting it opens the PIN screen. A correct PIN returns a one-time dataset for the detected fields.

If no card profile exists, selecting a payment field can still show that Caregiver Autofill is active and route the caregiver back to setup. Because this app is the selected autofill provider, the previous default provider should not offer its normal saved-card suggestions.

The app stores the card profile locally in this prototype. A production version should use stronger storage, clear caregiver consent, and ideally a prepaid or low-limit card only.

## Product Direction

The durable safety strategy is:

- Reduce saved-card exposure.
- Require purchase authentication where the platform supports it.
- Use low-limit financial instruments.
- Use bank alerts as the final safety net.
- Make caregiver rules explicit.
- Keep autofill optional and caregiver-approved.

This is a prevention and friction layer, not a bank-grade payment control.
