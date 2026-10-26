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
//   languages      the languages one can speak, and those that language can be written in
//   install        reserve the spoken language and download its speech model
//   listen <file>  record the microphone into <file> and transcribe it live
//                  until stdin says "stop"; "cancel" or end of input discards
//   file <file>    transcribe a saved recording again
//   translation-download <from> <to>
//                  show Apple's download sheet for a translation pair in a small window
//
// --locale <id> picks the spoken language instead of the Mac's; --translate <language>
// writes finished text in that language.

import AVFoundation
import Foundation
import Speech
import SwiftUI
// Only the download window hands a session across actors, and it does so once, in sequence.
@preconcurrency import Translation

struct Failure: Error {
    let code: String
    let message: String
}

struct Options {
    var command: [String] = []
    var locale: String?
    var translate: String?

    init(_ arguments: [String]) {
        var rest = arguments[...]
        while let argument = rest.popFirst() {
            switch argument {
            case "--locale": locale = rest.popFirst()
            case "--translate": translate = rest.popFirst()
            default: command.append(argument)
            }
        }
    }
}

let output = NSLock()

func emit(_ fields: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .withoutEscapingSlashes])
    output.withLock { FileHandle.standardOutput.write(data + Data("\n".utf8)) }
}

func named(_ identifier: String) -> String {
    Locale.current.localizedString(forIdentifier: identifier) ?? identifier
}

func language(_ options: Options) async -> Locale? {
    await SpeechTranscriber.supportedLocale(equivalentTo: options.locale.map(Locale.init(identifier:)) ?? Locale.current)
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

/** The written language, or nil when it is the spoken one and nothing needs translating. */
func target(_ locale: Locale, _ options: Options) -> Locale.Language? {
    guard let translate = options.translate else { return nil }
    let language = Locale.Language(identifier: translate)
    return language.maximalIdentifier == locale.language.maximalIdentifier ? nil : language
}

func translation(_ locale: Locale, _ options: Options) async -> String? {
    guard let target = target(locale, options) else { return nil }
    switch await LanguageAvailability().status(from: locale.language, to: target) {
    case .installed: return "ready"
    case .supported: return "missing"
    case .unsupported: return "unsupported"
    @unknown default: return "unsupported"
    }
}

func check(_ options: Options) async {
    let available = SpeechTranscriber.isAvailable
    let found = available ? await language(options) : nil
    let state = if let found { await model(found) } else { String?.none }
    // A language whose model macOS cannot install is as unsupported as no match at all.
    let locale = state == nil ? nil : found
    let wanted = options.locale.map(Locale.init(identifier:)) ?? Locale.current
    let translating = if let locale { await translation(locale, options) } else { String?.none }
    emit([
        "type": "check",
        "available": available,
        "language": locale?.identifier ?? NSNull(),
        "languageName": named(locale?.identifier ?? wanted.language.languageCode?.identifier ?? wanted.identifier),
        "model": state ?? NSNull(),
        "translation": translating ?? NSNull(),
        "microphone": AVCaptureDevice.default(for: .audio) != nil,
    ])
}

func languages(_ options: Options) async {
    let spoken = await SpeechTranscriber.supportedLocales.map { ["id": $0.identifier, "name": named($0.identifier)] }
    var written: [[String: String]] = []
    if let locale = await language(options) {
        let availability = LanguageAvailability()
        for language in await availability.supportedLanguages where language.maximalIdentifier != locale.language.maximalIdentifier {
            if await availability.status(from: locale.language, to: language) != .unsupported {
                written.append(["id": language.minimalIdentifier, "name": named(language.minimalIdentifier)])
            }
        }
    }
    let byName = { (left: [String: String], right: [String: String]) in left["name", default: ""] < right["name", default: ""] }
    emit(["type": "languages", "spoken": spoken.sorted(by: byName), "written": written.sorted(by: byName)])
}

func readyLanguage(_ options: Options) async throws -> Locale {
    guard SpeechTranscriber.isAvailable else {
        throw Failure(code: "unavailable", message: "This Mac can't run on-device speech recognition")
    }
    guard let locale = await language(options), let state = await model(locale) else {
        throw Failure(code: "language-unsupported", message: "No on-device speech model for \(options.locale ?? Locale.current.identifier)")
    }
    guard state == "ready" else {
        throw Failure(code: "model-missing", message: "The speech model for \(locale.identifier) is not installed")
    }
    return locale
}

/** The language pair to translate between, checked installed up front so a dictation fails before it starts. */
func translation(pair locale: Locale, _ options: Options) async throws -> (Locale.Language, Locale.Language)? {
    guard let target = target(locale, options) else { return nil }
    guard await LanguageAvailability().status(from: locale.language, to: target) == .installed else {
        throw Failure(code: "translation-missing", message: "Translation from \(locale.identifier) to \(target.minimalIdentifier) is not installed")
    }
    return (locale.language, target)
}

func install(_ options: Options) async throws {
    guard SpeechTranscriber.isAvailable, let locale = await language(options), await model(locale) != nil else {
        throw Failure(code: "language-unsupported", message: "No on-device speech model for \(options.locale ?? Locale.current.identifier)")
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

/** Live words stay in the spoken language; each finished segment is translated before it is sent on. */
func transcribe(_ module: SpeechTranscriber, pair: (Locale.Language, Locale.Language)?) -> Task<Void, Error> {
    Task {
        // The session is not Sendable, so it lives in the one task that uses it.
        let translator = pair.map { TranslationSession(installedSource: $0.0, target: $0.1) }
        let spacing = spaced(pair?.1 ?? module.selectedLocales.first?.language)
        var finals = ""
        for try await result in module.results {
            let text = String(result.text.characters)
            guard result.isFinal else {
                emit(["type": "partial", "text": text])
                continue
            }
            guard let translator, !text.trimmingCharacters(in: .whitespaces).isEmpty else {
                finals += text
                emit(["type": "final", "text": text])
                continue
            }
            let translated = try await translator.translate(text).targetText
            // Translated segments arrive bare; languages written with spaces need one between them.
            let piece = finals.isEmpty || !spacing ? translated : " \(translated)"
            finals += piece
            emit(["type": "final", "text": piece, "original": text])
        }
        emit(["type": "done", "text": finals.trimmingCharacters(in: .whitespacesAndNewlines)])
    }
}

func spaced(_ language: Locale.Language?) -> Bool {
    !["zh", "ja", "yue"].contains(language?.languageCode?.identifier ?? "")
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

func listen(_ path: String, _ options: Options) async throws {
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
    let locale = try await readyLanguage(options)
    let pair = try await translation(pair: locale, options)
    let module = transcriber(locale)
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
    let results = transcribe(module, pair: pair)
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

func file(_ path: String, _ options: Options) async throws {
    let locale = try await readyLanguage(options)
    let module = transcriber(locale)
    let results = transcribe(module, pair: try await translation(pair: locale, options))
    let audio = try AVAudioFile(forReading: URL(fileURLWithPath: path))
    _ = try await SpeechAnalyzer(inputAudioFile: audio, modules: [module], finishAfterFile: true)
    try await results.value
}

// Apple downloads a translation pair only from a session a window owns, after asking the user in its own sheet.
final class DownloadDelegate: NSObject, NSApplicationDelegate {
    // Closing the window without downloading ends the command with no "installed" line.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

struct DownloadView: View {
    let from: Locale.Language
    let to: Locale.Language

    var body: some View {
        Text("ByteQuay dictation needs \(named(from.minimalIdentifier)) ↔ \(named(to.minimalIdentifier)) translation.")
            .padding(24)
            .frame(width: 420)
            .translationTask(TranslationSession.Configuration(source: from, target: to)) { session in
                do {
                    try await session.prepareTranslation()
                    emit(["type": "installed"])
                    exit(0)
                } catch {
                    emit(["type": "error", "code": "translation-missing", "message": "\(error)"])
                    exit(1)
                }
            }
    }
}

// AppKit owns the window: SwiftUI's own scenes take the command's words for files to open and show no window.
@MainActor
func showDownload(from: Locale.Language, to: Locale.Language) -> Never {
    let app = NSApplication.shared
    let delegate = DownloadDelegate()
    app.delegate = delegate
    // A bare executable shows no windows until it says it is an app; accessory keeps it out of the Dock.
    app.setActivationPolicy(.accessory)
    let window = NSWindow(contentViewController: NSHostingController(rootView: DownloadView(from: from, to: to)))
    window.title = "ByteQuay"
    window.center()
    window.makeKeyAndOrderFront(nil)
    app.activate()
    app.run()
    exit(0)
}

func run(_ options: Options) async {
    let command = options.command
    do {
        switch (command.first, command.count) {
        case ("check", 1): await check(options)
        case ("languages", 1): await languages(options)
        case ("install", 1): try await install(options)
        case ("listen", 2): try await listen(command[1], options)
        case ("file", 2): try await file(command[1], options)
        default:
            FileHandle.standardError.write(Data("""
                usage: bytequay-dictate check | languages | install | listen <file> | file <file> [--locale <id>] [--translate <language>]
                       bytequay-dictate translation-download <from> <to>

                """.utf8))
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

@main
struct Dictate {
    // Not async: the download window's run loop must own the main thread, and an async main would already hold it.
    static func main() {
        let options = Options(Array(CommandLine.arguments.dropFirst()))
        if options.command.first == "translation-download", options.command.count == 3 {
            MainActor.assumeIsolated {
                showDownload(from: Locale.Language(identifier: options.command[1]), to: Locale.Language(identifier: options.command[2]))
            }
        }
        Task {
            await run(options)
            exit(0)
        }
        dispatchMain()
    }
}
