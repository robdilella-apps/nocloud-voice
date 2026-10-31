# Third-party notices

NoCloud Voice's own code is MIT licensed (see `LICENSE`). It ships with, or downloads, the following third-party components. Licenses were checked against the upstream repositories / model pages in October 2026.

## Bundled in the APK

| Component | Version | License | Source |
|---|---|---|---|
| sherpa-onnx (Next-gen Kaldi / k2-fsa) | 2.0.1 prebuilt Android AAR (`app/libs/sherpa-onnx-2.0.1.aar`) | Apache License 2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (Microsoft), included inside the sherpa-onnx AAR as `libonnxruntime.so` | as bundled by sherpa-onnx 2.0.1 | MIT License, Copyright (c) Microsoft Corporation | https://github.com/microsoft/onnxruntime |
| Kotlin standard library (JetBrains) | 2.0.21 | Apache License 2.0 | https://github.com/JetBrains/kotlin |

Full license texts: Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0 · MIT https://opensource.org/license/mit

JUnit 4.13.2 (Eclipse Public License 1.0) is used only for unit tests and is not included in the APK.

## Speech models (downloaded by the app on first run, not included in this repo or the APK)

### NVIDIA Parakeet TDT 0.6B v2
- Original model: `nvidia/parakeet-tdt-0.6b-v2` by NVIDIA, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2
- Converted int8 ONNX files used by the app: `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` (from the sherpa-onnx project), https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
- License: **Creative Commons Attribution 4.0 International (CC-BY-4.0)**, https://creativecommons.org/licenses/by/4.0/ (both pages above carry the `cc-by-4.0` license tag).
- Attribution: "Parakeet TDT 0.6B v2" by NVIDIA, CC-BY-4.0. Converted to int8 ONNX by the sherpa-onnx project. NoCloud Voice does not modify the weights.

### Moonshine Base (English)
- Original model: Moonshine by Useful Sensors / Moonshine AI, https://github.com/moonshine-ai/moonshine
- Converted int8 ONNX files used by the app: `csukuangfj/sherpa-onnx-moonshine-base-en-int8`, https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8
- License: **MIT**. The upstream LICENSE says: "Moonshine models are released under the MIT License by default... This includes all streaming speech-to-text models and all English-language models." Some newer non-English models have different terms. Moonshine Base English falls under MIT.

## Trademarks
Google, Pixel, Android, and Gboard are trademarks of Google LLC. NVIDIA and Parakeet are trademarks of NVIDIA Corporation. NoCloud Voice is not affiliated with or endorsed by any of them.
