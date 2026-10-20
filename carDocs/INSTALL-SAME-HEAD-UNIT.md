# Install guide — same head unit (Geely/Lynk FX11 · CS11_A4)

> Target head unit: `geely_fx11` / model `CS11_A4` (DesaySV, `msmnile` = Qualcomm SA8155, Android 11).
> Desktop is a customised Flyme Auto launcher; system bar `com.geely.statusbar`; factory CarPlay service `com.autolink.carplay`.
> This is an end-to-end "from zero to usable" guide. "See" links point to the detailed records.

---

## 0. Prerequisites

- **PC** with `adb`, head unit connected over USB. This unit's adbd **does not listen on TCP**, so USB only (`adb tcpip` will not work).
- **APK**: `mobile-release.apk`
  - package `com.shihab.diplay`, signed `CN=DiPlay Lynk` (self-signed, *not* the official release signature).
  - provided by the maintainer, or build it with `carDocs/build-lynk.sh` (it extracts the MFi assets from the copy already installed on the head unit).
- **Heads-up**: this unit's adbd **filters the literal word `install`** out of shell commands (Geely/Lynk anti-tamper), so the install command must be split — see step 1.
- **⚠️ Security**: the built APK embeds the **offline MFi identity extracted from your head unit** (Apple credentials). **Do not redistribute the APK.** Share only the source and scripts and let users build with their own unit's assets (`build-lynk.sh` extracts them automatically).

---

## 1. Install the APK (no plain `adb install` here)

The unit does not support incremental/streamed install, and adbd cuts off any command containing `install`. Do:

```bash
# (1) push
adb push mobile-release.apk /data/local/tmp/mobile-release.apk

# (2) call pm install with the keyword split (no literal "install" in the command text)
adb shell 'a=in; b=stall; pm ${a}${b} -r -t /data/local/tmp/mobile-release.apk'
# expected: Success
```

Or use the bundled script (it already splits internally):

```bash
./carDocs/install-apk.sh mobile-release.apk        # default -r -t
./carDocs/install-apk.sh mobile-release.apk -r -t -d
```

### Changing signature: uninstall first

Self-signed differs from the official signature. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall first (`uninstall` also contains `install`, so split it too):

```bash
adb shell 'a=ins; b=tall; pm un${a}${b} com.shihab.diplay'
```

> Uninstalling clears the old settings.

**See**: `车机安装与桌面说明.md` section 2.

---

## 2. Turn on Wi-Fi (required for wireless CarPlay)

This car has a **single Wi-Fi chip**, and the **car hotspot and the Wi-Fi client are mutually exclusive**. On boot the head unit opens the car hotspot by default, which kicks out the Wi-Fi client — so DiPlay cannot start wireless.

**Do this:**

1. On the head unit, **Settings → System → Connectivity → turn OFF the "car hotspot"**.
2. **Turn the system Wi-Fi switch ON in Settings → Wi-Fi.** The radio is not enough: with the switch
   off, the iPhone joins the group but never opens the AirPlay TCP, whichever phone you use
   (measured 2026-10-21, see below).
3. Then turn Wi-Fi on once (or run the command):

```bash
adb shell cmd wifi set-wifi-enabled enabled
adb shell settings get global wifi_on      # expect 1
```

- Once the car hotspot stays off, the **Wi-Fi state survives reboots**; no adb needed afterwards.
- While debugging over the PC, do not keep the head unit connected to another Wi-Fi AP (it occupies the P2P radio).
- `settings get global wifi_on` reading `1` does **not** prove the system switch is on. Always check
  the Settings → Wi-Fi toggle on the head unit itself before blaming the phone.

**Measured 2026-10-21 (wireless CarPlay failed with two phones until the switch was on)**

With the Wi-Fi switch off, iPhone XR and iPhone Air behaved identically:

| Step | Result |
| --- | --- |
| Wi-Fi P2P group | created every attempt (5 GHz ch149, `host=192.168.x.x`) |
| iPhone joins the group | yes, `ip neigh` showed it `REACHABLE` (iOS hides `DIRECT-*` from its Wi-Fi list) |
| Bluetooth iAP2 | connected, `0x4158` / `0xae01` received |
| AirPlay TCP 7000 | never dialled: `first TCP timeout` after `FIRST_TCP_MILLIS = 30000` |
| mDNS | `bonjourAdded=0 bonjourResolved=0` |

The retry loop stops after `MAX_STARTUP_RETRIES = 5` and the screen stays on "connecting iPhone",
so a stuck screen usually means the attempts are already spent — restart DiPlay after fixing the switch.

**See**: `车机兼容记录.md` section 7.

---

## 3. Steering-wheel mic key → Siri (disable HiCar)

The wheel keys are **vendor-private key codes**; DiPlay subscribes to them through the head unit's input service (works automatically). But the **mic key long-press is grabbed by HiCar** (`startHiCarMainActivity`), which DiPlay cannot intercept — so **disable HiCar**:

```bash
adb shell pm disable-user --user 0 com.geely.hicar

# verify: expect enabled=3 (DISABLED_USER), stopped=true
adb shell dumpsys package com.geely.hicar | grep -E "enabled=|stopped="

# to restore later
adb shell pm enable com.geely.hicar
```

> This is a **system-level setting**, not an in-app toggle; a system OTA may require redoing it.
> After this: long-press the mic key → `requestSiri()`; short-press is still the car's own voice assistant (not interceptable from an app).

**See**: `车机兼容记录.md` section 6.

---

## 4. Grant the head-unit microphone (or Siri hears nothing)

The head unit's own permission service **denies third-party microphone by default**, so DiPlay records DTX silence. Grant it:

```bash
adb shell settings put secure app_permission_mic_com.shihab.diplay 0
```

> `0` = granted, `-1` = denied (default). The branch also switches Siri's capture source to `VOICE_COMMUNICATION` (same as the car's own assistant).

**See**: `车机兼容记录.md` section 8.

---

## 5. Launcher icon and branding

- The desktop is a heavily customised launcher with **no third-party shortcut receiver**, so broadcast-added icons do not work. In "My Apps", **long-press the DiPlay icon → add to desktop** (or drag it to the home card area).
- The lynk branch already brands user-visible text/icons as **lynkPlay / LYNK & CO**, and the CarPlay vehicle button label is changed from `BYD` to `LYNK & CO`.

**See**: `车机安装与桌面说明.md` section 3.

---

## 6. ⚠️ Known limitation: realtime audio (important)

**This head unit's Wi-Fi receive path drops most inbound UDP (measured ~77–87%)**, therefore:

- CarPlay **realtime audio is UDP with no retransmission** → music / navigation / calls / Siri **stop after a few seconds** (a head-unit firmware issue; not fixable in the app).
- Working paths:
  - **Settings → Advanced → Buffered music (experimental)**: media goes over TCP (`type=103`). **Covers media only; navigation prompts have no buffered stream.**
  - **Settings → Advanced → CarPlay audio over the car's Bluetooth (experimental)**: everything goes over the car's Bluetooth. Covers navigation/calls too, but **navigation is mixed into the A2DP media channel — no separate navigation channel, no independent navigation volume.**
- Both are **experimental and off by default**; turn on the one you need.

**See**: `车机兼容记录.md` sections 9–10 (cross-AP measurements, upstream issues #165/#131).

---

## 7. Acceptance checklist

- [ ] Package list shows `com.shihab.diplay` with the expected version (`adb shell pm list packages | grep shihab`)
- [ ] `settings get global wifi_on` = 1, and the car hotspot is OFF in settings
- [ ] Wireless CarPlay connects (video + touch OK)
- [ ] Steering wheel: next/previous works; long-press mic key raises Siri and speech is recognised
- [ ] For media sound, enable "Buffered music" or "CarPlay audio over the car's Bluetooth"

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `adb install` fails / `error: closed` | no streamed install + adbd filters `install` | use the push + split method (step 1) |
| signature mismatch on install | official vs self-signed | uninstall first (end of step 1) |
| DiPlay says "please turn on Wi-Fi in the head-unit settings" | car hotspot kicked the Wi-Fi client | turn off the car hotspot, then enable Wi-Fi (step 2) |
| long-press mic key wakes HiCar instead of Siri | HiCar owns the key | disable HiCar (step 3) |
| Siri wakes but does not hear you | head-unit mic permission denied | grant it (step 4) |
| music plays ~1–2 s then goes silent | head-unit Wi-Fi UDP loss | use buffered music / car Bluetooth (step 6) |
| `waiting for device` loops | USB transport confused | replug / `adb kill-server && adb start-server` |

## 9. Related documents

- `车机安装与桌面说明.md` — install mechanism (keyword filter), desktop environment, command reference
- `车机兼容记录.md` — measured root causes and fixes (Wi-Fi/hotspot, wheel keys, microphone, realtime audio, cross-AP measurements)
- `车机桌面集成结论.md` — desktop integration conclusions
- Scripts: `carDocs/build-lynk.sh`, `carDocs/install-apk.sh`
- Chinese edition: `同款车机安装指南.md`
