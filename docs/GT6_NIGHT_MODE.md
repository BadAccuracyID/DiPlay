# GT6 headlight-linked CarPlay appearance

GT6 builds default to **Follow headlights**. Both DiPlay settings and the in-CarPlay three-finger settings menu offer **Follow headlights**, **Follow Android**, **Day**, and **Night**. Other hardware retains its existing Android dark-mode behaviour.

The foreground connection service reads the OEM illumination state every two seconds and also observes provider changes. It queries only `KSW_DATA_SMALL_LIGHT_ON` from the OEM SysVar provider. The local EventCenter reference shows that this value is updated from the MCU illumination bit and used for ZLink day/night signalling. If the provider is unavailable, DiPlay reads `rw.out.dark`, the property set by that same OEM path. If neither read returns exactly 0 or 1, Follow headlights uses Android appearance until a valid reading returns.

This is a read-only integration. It does not write OEM settings, modify MCU messages, enable ZLink, or change system brightness. The headunit retains its own brightness controls. The car's illumination signal may cover parking lights as well as headlights; its physical meaning must be checked on this GT6.

Appearance updates use the persistent CarPlay controller's serialized command worker. Repeated polling does not send duplicate successful commands. A failed event-channel write is retried, and a replacement CarPlay session receives the current mode. Monitoring continues while another app covers CarPlay or the host activity is destroyed, and ends with the connection service.

## Local verification

Tests exercise the actual provider query and a changing simulated light value, manual overrides, Android fallback, GT6-only defaults, encrypted CarPlay commands, duplicate suppression, unavailable-channel retry, and stale-session protection. Local builds and these tests do not establish that the OEM provider/property is readable by the installed app or that the iPhone follows the mode on this car.

## Car test

1. Install the standalone GT6 candidate over the existing patched DiPlay package. Keep the working previous APK for rollback.
2. Connect CarPlay with headlights already on; confirm night appearance without reconnecting.
3. Switch lights off/on while CarPlay is visible, then repeat with the camera or another Android app in front. Return to CarPlay and check its appearance.
4. Test Day, Night, and Follow Android overrides, then restore Follow headlights.
5. Disconnect/reconnect and test after an ignition wake with lights already on.

The iPhone's own CarPlay appearance choice may affect the visible result. If the OEM read fails, the DiPlay log identifies the source as Android fallback. Check the source, the selected DiPlay mode, and the iPhone setting before diagnosing the receiver command.
