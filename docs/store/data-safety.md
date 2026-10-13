# Play Console — Data safety form answers

The form asks whether the app collects or shares user data. For Easy Dashcam every answer is **No**.

## Summary selections

- **Does your app collect or share any of the required user data types?** → **No**
- **Is all of the user data collected by your app encrypted in transit?** → n/a (nothing is collected; camera traffic never leaves the local hotspot)
- **Do you provide a way for users to request that their data is deleted?** → n/a

If the console still requires per-type declarations (it shouldn't once "No" is chosen):

| Data type | Collected? | Shared? | Note |
|---|---|---|---|
| Location | No | No | Location permission is only used (on older Android) to read the Wi-Fi SSID for the camera connection — never recorded or transmitted |
| Photos & videos | No | No | Downloads go *to* the device, not from it |
| Files & docs | No | No | Same |
| Personal info | No | No | — |
| App activity | No | No | — |
| Device IDs | No | No | — |
| App performance / crash logs | No | No | The debug log stays on-device and is only shared manually via the share sheet |

## Notes for the reviewer (optional field)

"This app operates entirely offline on the camera's local Wi-Fi hotspot. It contains no analytics, ads, accounts, or cloud features and transmits no user data."
