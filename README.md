# FaceSwap (Android, Kotlin + Jetpack Compose + ONNX Runtime)

On-device face swap for photos and videos, based on the insightface `inswapper_128` pipeline from your notebook.
Everything runs offline on the phone.

## Build on GitHub
Push this folder, then open **Actions -> Build APK**. Download `FaceSwap-debug-apk` or `FaceSwap-release-apk`.
(Optional) add secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign with your own key;
otherwise the release APK is signed with the debug key.

## Model files (picked on first launch)
| File | Where to get it |
|------|-----------------|
| `inswapper_128.onnx` (float32) | the Hugging Face repo used in your notebook |
| `det_10g.onnx`, `w600k_r50.onnx` | inside `buffalo_l.zip` (insightface releases); you can pick the zip directly |
| `gfpgan*.onnx` (optional) | any GFPGAN 1.4 ONNX export; enables "Enhance face" |

## Use
1. Add photos of your current face under **My face** (several clear, front-facing photos give the best identity).
2. Pick old photos or a video. Results are saved to Pictures/FaceSwap or Movies/FaceSwap as plain image/video files.

## Notes
- Needs a phone with ~6 GB RAM (the swapper model is ~550 MB). Video is processed frame by frame and is slow;
  use 720p/1080p for speed. The app keeps running in the background with a notification while processing.
- The swapper works on a 128 px face crop, so the swapped area is softer than a 4K original. "Enhance face" helps.
- `inswapper_128` is licensed for non-commercial research use only.
- Use only with faces you have the right to use.
