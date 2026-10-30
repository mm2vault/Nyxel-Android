# Nyxel AI Architecture

Nyxel AI is designed as a provider-independent assistant. The Android UI talks to an internal provider contract; it must not know whether a reply came from a cloud API or an on-device model.

## Product goals

- Turkish-first assistant, with multilingual conversations.
- Optional voice input/output.
- Conversation history and user-controlled local memory.
- Safe tool execution for Nyxel features: music controls, timers, notes, reminders and device shortcuts.
- Cloud and local inference are separate choices. Core music playback and Dynamic Island controls never depend on AI availability.
- No paid service is enabled automatically. No provider key or model is bundled in the APK.

## Provider strategy

### Cloud: OpenRouter adapter

The first cloud adapter is OpenRouter's OpenAI-compatible chat-completions endpoint. It supports a user-selected model ID and the `openrouter/free` router. Free models have rate limits and can change; the app must show provider errors and never promise unlimited free usage.

The user's API key is entered by the user and encrypted with AES-GCM using a key held by Android Keystore. It is never committed to this repository or printed to logs. A future production backend proxy can be added if we need to protect centrally managed credentials; that backend must not be required for basic Nyxel use.

### Local: LiteRT-LM

The intended Android runtime is Google's LiteRT-LM Kotlin API. The model file is downloaded separately to app storage and is never packaged into the APK. The first candidate is the small Qwen3-0.6B LiteRT model for a low-storage starter option. A larger Gemma family model can be offered later on devices with enough memory and thermal headroom.

Model startup and generation must run off the main thread. Keep one owner for the engine/conversation lifecycle, close resources exactly once, and offer a CPU fallback when GPU support is unavailable. Device-specific performance must be measured before advertising a model as fast.

## Assistant layers

1. **Conversation layer** — messages, cancellation, context window management and local history.
2. **Provider layer** — cloud or local implementation behind a common interface.
3. **Memory layer** — explicit user-approved facts and preferences, stored locally with controls to inspect and clear them.
4. **Tool layer** — typed actions such as play/pause/next, start a timer, create a note or add a reminder.
5. **Safety layer** — validate tool arguments, request confirmation for consequential actions, and never execute arbitrary generated shell/code.
6. **Voice layer** — Android speech recognition and text-to-speech, with clear fallback when the device language pack is unavailable.

## Implementation status

- [x] Provider-neutral message and provider contracts.
- [x] OpenRouter chat-completions adapter.
- [x] API key encryption using Android Keystore (AES-GCM).
- [ ] AI settings screen (provider, model, key status, local model download).
- [ ] Assistant chat UI and streaming responses.
- [ ] LiteRT-LM local provider and model download/verification.
- [ ] Local conversation history and opt-in memory.
- [ ] Typed Nyxel tool calling with confirmation prompts.
- [ ] Voice input/output and language selection.

## Important constraints

- A free cloud model is not an unlimited or guaranteed service.
- Local model quality and speed depend on the phone's chipset, RAM, available storage and temperature.
- Training a capable general-purpose language model from scratch is not a realistic first milestone for a free mobile project. Nyxel can own its orchestration, memory, tools and small specialist models; model fine-tuning can be evaluated later.
- Do not store API keys in source code, HTML, BuildConfig, GitHub Actions logs or repository secrets that are shipped into the APK.
- AI failure must never break the player, media session, overlay or app startup.
