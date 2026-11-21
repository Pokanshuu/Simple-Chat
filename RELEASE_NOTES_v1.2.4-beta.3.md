# SimpleChat v1.2.4-beta.3

## Chat and Memory

- Add a per-conversation memory switch. A conversation with memory off neither uses nor supplies cross-chat memories. New conversations can opt out before their first message.
- Move Search settings under a distinct Search section in Conversation Behavior.
- Keep the speech entry above Conversation Behavior and show Off when read aloud is disabled.

## Image Generation

- Add image-model-aware aspect ratio choices and ratio previews, keep dropdown indicators visible, and align composer controls and configuration actions.
- Add image-reference selection to the image composer and include selected images in supported generation requests.
- Persist image request parameters for retries after interruption, retry dropped image connections once, and animate generated images using the selected aspect ratio.
- Refine full-screen image browsing across the active conversation and add original-image saving.
- Make image model catalogs endpoint-specific and selectable from the same menu as chat models, with manual model entry as a fallback.

## Model and Tool Compatibility

- Improve DeepSeek client-tool combinations for image generation and preserve image intent in the prompt.
- Improve native Gemini image routing and model-specific effort controls.

## Verification

- Android version 1.2.4-beta.3, build 46.
- 813 unit tests passed, with 10 skipped. The signed R8 APK installed over the previous version and launched on an emulator.
- The existing backup round-trip consistency check still fails in device regression. Third-party image generation endpoints have not been validated with live credentials.
