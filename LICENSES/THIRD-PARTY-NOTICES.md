# Third-party code in In Arabic

| What | Where in this project | From | License |
|---|---|---|---|
| LG TV network-remote pairing manifest and the list of LG TV certificates | `android/app/src/main/assets/lg-remote-pairing.json`, `android/app/src/main/java/com/inarabic/lg/LgKeepAwake.kt` | [lgtv2](https://github.com/hobbyquaker/lgtv2) 2.1.1, © Sebastian Raff | MIT — `MIT-lgtv2.txt` |
| The Edge read-aloud access token (Sec-MS-GEC v2) and the voice request format, ported to Kotlin | `android/app/src/main/java/com/inarabic/voice/EdgeToken.kt`, `EdgeTts.kt` | [edge-tts](https://github.com/rany2/edge-tts) 7.3.1, © rany2 and contributors | LGPL-3.0 — `LGPL-3.0-edge-tts.txt` |

The rest of the project is under `AGPL-3.0-only-OpenSSL.txt` (from chiaki-ng). LGPL-3.0 and MIT code may be
combined with AGPL-3.0 code; the notices above and the license files next to this one are kept with it.
