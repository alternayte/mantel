# Android

The Android client is a creator app. It signs in to an instance, makes albums, uploads photographs
and videos, and shares links. It is a pure consumer of the REST API in `docs/api.md`: everything it
does, the web client and an agent can do too.

It is sideloaded. There is no store listing, and none is planned.

## Install

Download `mantel-android-<version>.apk` from the [releases
page](https://github.com/alternayte/mantel/releases) and open it. Android asks once whether this
source may install applications.

On first launch the app asks for the address of your instance — `albums.example.com`, the same
address you open in a browser. It assumes `https`; a development instance on `http://localhost` is
the only exception.

## What it does

- **Sign in** with a magic link or with GitHub, in your own browser rather than in the app. The app
  never sees your password and no page inside it asks for one.
- **The library**: every photograph and video the account holds, newest first. An album is a
  selection from it, so adding one to an album costs no upload and no storage.
- **Albums**: make one, see what is in it, set a cover, caption an item, reorder by holding a
  photograph and dragging it.
- **Upload** through the system photo picker, so the app sees the files you chose and nothing else.
  The upload runs in the background with a notification, and resumes where it stopped if it is
  interrupted. A file the library already holds is never sent twice: the app hashes it first and the
  server answers that it has it.
- **Share** with a link, optionally behind a PIN and optionally expiring after 7, 30 or 90 days.
  Revoking a link is immediate: it returns 404 from the next request.
- **Back up** the phone's media to your library, if you turn it on. See below.

The viewer is the web. A recipient opens the link in whatever browser they have, which is the point
of the product.

## Backup

Backup is off. Turning it on is the only thing that makes the app ask to read the device's media;
picking photographs by hand uses the system picker, which needs no permission at all. An install
that never turns backup on is never asked for anything.

When it is on:

- It offers the folders on the device and starts with **Camera** and nothing else. The rest of a
  phone is screenshots, downloads and things people sent in a group chat.
- It runs every six hours, by default only on wi-fi and only while charging. Both are yours to
  change, and "Back up now" runs it immediately.
- It is **one-way and additive**. It only ever reads. Deleting a photograph on the phone leaves the
  backup standing, and nothing in the app can delete from the phone.
- Turning it off stops new uploads and removes nothing, here or on the phone.

Media backed up this way lands in the library with a thumbnail. The rest of its derivatives are
rendered when it first joins an album — that is, when somebody might actually look at it.

The library keeps whatever the camera produced, including formats this product cannot render. Those
appear as a filename with no thumbnail and cannot be put in an album, because an album is what a
recipient is shown. The original is kept byte for byte, so support for a format added later is a
re-render rather than a re-upload.

## Build it yourself

You need the Android SDK and a JDK 21. The client is its own Gradle build, so the server's `just
check` never needs an SDK.

```
just check-android          # lint, unit tests and a debug APK
```

The debug APK is at `android/build/outputs/apk/debug/mantel-android-debug.apk`.

### A signed release build

The app is sideloaded, so its signature is the only thing that says an update came from whoever
wrote the install. Make a key once, and keep it:

```
keytool -genkeypair -keystore mantel.jks -storepass <password> \
        -keyalg RSA -keysize 2048 -validity 36500 -alias mantel
```

Then:

```
export MANTEL_KEYSTORE=/absolute/path/to/mantel.jks
export MANTEL_KEYSTORE_PASSWORD=<password>
export MANTEL_KEY_ALIAS=mantel
just release-android
```

It prints the certificate it signed with. A build with no keystore in the environment still runs and
produces an **unsigned** APK, which Android will not install — that is deliberate, because a build
that quietly signs with a different key breaks every existing install's upgrade path.

Releases on GitHub carry the APK when the repository holds the signing secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`.

## What it does not do

Viewing an album is not in the app. Neither is anything on the deferred list in `SDD.md` §2.2: no
iOS, no face recognition, no collaborative upload, and no interception of share links opened on the
device. A share link opens in the browser, for the recipient and for you.
