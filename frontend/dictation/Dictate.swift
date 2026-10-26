/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// On-device dictation for ByteQuay's composers. Electron main runs one command
// per process and reads one JSON object per stdout line:
//
//   check          support facts, without asking for anything
//   install        reserve the Mac's language and download its speech model
//   listen <file>  record the microphone into <file> and transcribe it live
//                  until stdin says "stop"; "cancel" or end of input discards
//   file <file>    transcribe a saved recording again

import AVFoundation
import Foundation
import Speech

struct Failure: Error {
    let code: String
    let message: String
}

let output = NSLock()

func emit(_ fields: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .withoutEscapingSlashes])
    output.withLock { FileHandle.standardOutput.write(data + Data("\n".utf8)) }
}

func language() async -> Locale? {
    await SpeechTranscriber.supportedLocale(equivalentTo: Locale.current)
}

func transcriber(_ locale: Locale) -> SpeechTranscriber {
    // Without fast results, live text lags two to three seconds and arrives in bursts.
    SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults, .fastResults], attributeOptions: [])
}

func model(_ locale: Locale) async -> String? {
    switch await AssetInventory.status(forModules: [transcriber(locale)]) {
    case .installed:
        return "ready"
    case .downloading:
        return "downloading"
    case .supported:
        // macOS may already hold the model for its own dictation; reserving it is then instant.
        let installed = await SpeechTranscriber.installedLocales.map(\.identifier)
        return installed.contains(locale.identifier) ? "ready" : "missing"
    case .unsupported:
        return nil
    @unknown default:
        return nil
    }
}

func check() async {
    let available = SpeechTranscriber.isAvailable
    let found = available ? await language() : nil
    let state = if let found { await model(found) } else { String?.none }
    // A language whose model macOS cannot install is as unsupported as no match at all.
    let locale = state == nil ? nil : found
    let named = locale?.identifier ?? Locale.current.language.languageCode?.identifier ?? Locale.current.identifier
    emit([
        "type": "check",
        "available": available,
        "language": locale?.identifier ?? NSNull(),
        "languageName": Locale.current.localizedString(forIdentifier: named) ?? named,
        "model": state ?? NSNull(),
        "microphone": AVCaptureDevice.default(for: .audio) != nil,
    ])
}

func readyLanguage() async throws -> Locale {
    guard SpeechTranscriber.isAvailable else {
        throw Failure(code: "unavailable", message: "This Mac can't run on-device speech recognition")
    }
    guard let locale = await language(), let state = await model(locale) else {
        throw Failure(code: "language-unsupported", message: "No on-device speech model for \(Locale.current.identifier)")
    }
    guard state == "ready" else {
        throw Failure(code: "model-missing", message: "The speech model for \(locale.identifier) is not installed")
    }
    return locale
}

func install() async throws {
    guard SpeechTranscriber.isAvailable, let locale = await language(), await model(locale) != nil else {
        throw Failure(code: "language-unsupported", message: "No on-device speech model for \(Locale.current.identifier)")
    }
    let module = transcriber(locale)
    if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
        let progress = request.progress
        let reporter = Task {
            while !Task.isCancelled {
                emit(["type": "progress", "fraction": progress.fractionCompleted])
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        defer { reporter.cancel() }
        try await request.downloadAndInstall()
    }
    emit(["type": "installed"])
}

func transcribe(_ module: SpeechTranscriber) -> Task<Void, Error> {
    Task {
        var finals = ""
        for try await result in module.results {
            let text = String(result.text.characters)
            if result.isFinal {
                finals += text
                emit(["type": "final", "text": text])
            } else {
                emit(["type": "partial", "text": text])
            }
        }
        emit(["type": "done", "text": finals.trimmingCharacters(in: .whitespacesAndNewlines)])
    }
}

enum Control {
    case stop
    case cancel
}

func control() async -> Control {
    do {
        for try await line in FileHandle.standardInput.bytes.lines {
            switch line.trimmingCharacters(in: .whitespaces) {
            case "stop": return .stop
            case "cancel": return .cancel
            default: continue
            }
        }
    } catch {}
    return .cancel
}

func listen(_ path: String) async throws {
    guard AVCaptureDevice.default(for: .audio) != nil else {
        throw Failure(code: "no-microphone", message: "No microphone is connected")
    }
    let allowed = switch AVCaptureDevice.authorizationStatus(for: .audio) {
    case .authorized: true
    case .notDetermined: await AVCaptureDevice.requestAccess(for: .audio)
    default: false
    }
    guard allowed else {
        throw Failure(code: "microphone-denied", message: "ByteQuay is not allowed to use the microphone")
    }
    let module = transcriber(try await readyLanguage())
    guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module]) else {
        throw Failure(code: "failed", message: "No audio format suits the speech model")
    }

    let engine = AVAudioEngine()
    let input = engine.inputNode
    let source = input.outputFormat(forBus: 0)
    guard let converter = AVAudioConverter(from: source, to: format) else {
        throw Failure(code: "failed", message: "Cannot convert \(source) to \(format)")
    }
    // Retry reads exactly the audio the analyzer heard.
    let recording = try AVAudioFile(forWriting: URL(fileURLWithPath: path), settings: format.settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
    let (stream, feed) = AsyncStream<AnalyzerInput>.makeStream()
    input.installTap(onBus: 0, bufferSize: 4096, format: source) { buffer, _ in
        let frames = AVAudioFrameCount((Double(buffer.frameLength) * format.sampleRate / source.sampleRate).rounded(.up))
        guard let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return }
        // convert(to:) calls the input block synchronously, so these never cross threads.
        nonisolated(unsafe) let pending = buffer
        nonisolated(unsafe) var delivered = false
        converter.convert(to: converted, error: nil) { _, status in
            if delivered {
                status.pointee = .noDataNow
                return nil
            }
            delivered = true
            status.pointee = .haveData
            return pending
        }
        try? recording.write(from: converted)
        feed.yield(AnalyzerInput(buffer: converted))
    }
    // Capture starts before the model loads, so the first words wait in the stream instead of being lost.
    try engine.start()
    emit(["type": "listening"])

    let analyzer = SpeechAnalyzer(modules: [module])
    let results = transcribe(module)
    try await analyzer.prepareToAnalyze(in: format)
    try await analyzer.start(inputSequence: stream)

    let how = await control()
    engine.stop()
    input.removeTap(onBus: 0)
    feed.finish()
    switch how {
    case .stop:
        try await analyzer.finalizeAndFinishThroughEndOfInput()
        try await results.value
    case .cancel:
        await analyzer.cancelAndFinishNow()
        results.cancel()
    }
}

func file(_ path: String) async throws {
    let module = transcriber(try await readyLanguage())
    let results = transcribe(module)
    let audio = try AVAudioFile(forReading: URL(fileURLWithPath: path))
    _ = try await SpeechAnalyzer(inputAudioFile: audio, modules: [module], finishAfterFile: true)
    try await results.value
}

@main
struct Dictate {
    static func main() async {
        let args = Array(CommandLine.arguments.dropFirst())
        do {
            switch (args.first, args.count) {
            case ("check", 1): await check()
            case ("install", 1): try await install()
            case ("listen", 2): try await listen(args[1])
            case ("file", 2): try await file(args[1])
            default:
                FileHandle.standardError.write(Data("usage: bytequay-dictate check | install | listen <file> | file <file>\n".utf8))
                exit(2)
            }
        } catch let failure as Failure {
            emit(["type": "error", "code": failure.code, "message": failure.message])
            exit(1)
        } catch {
            emit(["type": "error", "code": "failed", "message": "\(error)"])
            exit(1)
        }
    }
}
