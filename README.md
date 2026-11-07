# WebView APK Template

A minimal Android WebView shell. Configure the app, build, done.

## Quick Start

### 1. Set your URL, package, app name, and icon

Edit **`app.properties`** in the project root:

```properties
app.url=https://your-domain.com/
app.package=com.yourcompany.yourapp
app.name=Your App Name
app.icon_url=https://your-domain.com/icon.png
# Or use a path relative to the project root:
# app.icon_url=branding/icon.xml
```

`app.icon_url` accepts an HTTP(S) URL, a project-relative local path, or an absolute local path.
It is optional: local builds use Android's system default app icon when empty, while GitHub Actions
first tries the website's `/favicon.ico` and continues with the system default icon if it is
unavailable. Paths supplied to GitHub Actions must point to files committed to the repository.
PNG, JPEG, WebP, GIF, BMP, ICO, SVG, AVIF, Android Vector Drawable XML, and other formats
supported by ImageMagick can be used. Vector Drawable XML is used directly without rasterization;
for animated or multi-resolution image files, the largest frame is selected.

### 2. Set up signing (for release builds)

Create **`keystore.properties`** in the project root (it is gitignored):

```properties
storeFile=keystore/release-signing.p12
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Place your `.p12` keystore file at `keystore/release-signing.p12` (or update `storeFile` to match
your path).

### 3. Build

**Option A — locally** (remote icons require `curl`; raster/SVG icons require ImageMagick, while
Android Vector Drawable XML requires `xmllint` from `libxml2-utils`):

```bash
./gradlew assembleDebug    # debug APK (no signing required)
./gradlew assembleRelease  # signed release APK
```

**Output paths:**

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

**Option B — GitHub Actions (no local Android setup needed):**

Go to **Actions → Build APK → Run workflow**, fill in the fields, pick `debug` or `release`,
and click **Run workflow**. The APK appears as a downloadable artifact when the job finishes.

For signed release builds, add these repository secrets first (Settings → Secrets and variables →
Actions):

| Secret              | Value                                     |
|---------------------|-------------------------------------------|
| `KEYSTORE_BASE64`   | `base64 -w0 keystore/release-signing.p12` |
| `KEYSTORE_PASSWORD` | store password                            |
| `KEY_ALIAS`         | key alias                                 |
| `KEY_PASSWORD`      | key password                              |

---

## Customization Reference

| What to change               | Where                               |
|------------------------------|-------------------------------------|
| WebView URL                  | `app.properties` → `app.url`        |
| App package / application ID | `app.properties` → `app.package`    |
| App display name             | `app.properties` → `app.name`       |
| App icon URL                 | `app.properties` → `app.icon_url`   |
| Signing keystore             | `keystore/` + `keystore.properties` |

> **Nothing in the Kotlin source needs editing** — configuration is applied at build time.

---

## Technical Details

- **Language:** Kotlin
- **Min API:** 24 (Android 7.0)
- **Target / Compile SDK:** 37
- **Edge-to-edge:** enabled, with inset-aware padding
- **Status bar color:** syncs dynamically with the page background color
- **Back button:** navigates back in WebView history before exiting
- **File upload:** supports gallery picker and camera capture
- **Permissions:** camera and microphone are granted to the WebView on request
- **Cleartext HTTP:** allowed for all hosts (see [Network Security](#network-security))
- **Local network access:** requested on launch on Android 17+ (see
  [Network Security](#network-security))
- **App icon:** downloaded and converted into legacy and adaptive launcher resources at build time
- **Signing:** v1 + v2 + v3 schemes enabled, v4 disabled

## Network Security

The template turns off two Android network protections by default so that it works with as many
sites as possible. If your site does not need them, turn the protections back on.

### Cleartext HTTP

`app/src/main/res/xml/network_security_config.xml` allows unencrypted `http://` traffic to every
host. Traffic sent this way can be read and modified by anyone on the network path.

To block cleartext traffic again, set `cleartextTrafficPermitted` to `false`:

```xml
<base-config cleartextTrafficPermitted="false">
```

To allow HTTP only for specific hosts, such as a device on your LAN, keep the base config blocked
and add a `domain-config`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.1.10</domain>
    </domain-config>
</network-security-config>
```

The WebView also uses `MIXED_CONTENT_COMPATIBILITY_MODE`, which lets an HTTPS page load some HTTP
resources such as images and media. To block all mixed content, change it to
`WebSettings.MIXED_CONTENT_NEVER_ALLOW` in `MainActivity.kt`.

### Local network access (Android 17+)

Apps that target SDK 37 cannot reach devices on the local network (private IP ranges, `.local`
hosts, and so on) unless the user grants `ACCESS_LOCAL_NETWORK`. WebView traffic uses the app's
permission, and WebView does not ask the app about it through `onPermissionRequest`. For this
reason the app declares the permission and requests it on first launch. If the user grants it, the
page reloads so that blocked requests run again. Any page the WebView loads can then reach the
user's local network.

If your site does not use the local network, delete this line from
`app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

The app requests the permission only when the manifest declares it, so no Kotlin changes are
needed. On Android 17 and later, local network requests from the WebView are then blocked. Older Android
versions do not have this permission and keep allowing local network access.

## Signing Notes

- `keystore.properties` is in `.gitignore` — never commit it
- The `keystore/` directory is tracked by git; committing your keystore file there is optional but
  convenient for private repos
- If `keystore.properties` is absent, release builds are unsigned (debug builds always work)
